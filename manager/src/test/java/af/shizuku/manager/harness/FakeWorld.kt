package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Looper
import android.provider.Settings
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything outside the manager process that the one-prompt rule depends on: the wall clock,
 * durable storage, adbd, the server's binder and WorkManager. The manager's own code runs for real
 * against it; [processDeath] drops exactly what a dead process loses.
 */
class FakeWorld(
    val app: Application,
) : Closeable {
    private val clock = AtomicLong(START_MS)
    val now: Long get() = clock.get()

    val prefs = FakePrefs()
    val adbd = FakeAdbd(onShell = { serverUp() })

    /** Input of every AdbStartWorker WorkManager ran, oldest first: what a rerun repeats. */
    val startInputs = CopyOnWriteArrayList<Data>()

    private val workExecutor = Executors.newCachedThreadPool()
    val workManager: WorkManager get() = WorkManager.getInstance(app)

    fun install() {
        if (Security.getProvider(ANDROID_KEYSTORE) == null) Security.addProvider(UnusableAndroidKeyStore())
        ShizukuApplication::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(null, app)
        Starter.initialize(app)
        ShizukuSettings.setPreferencesForTesting(prefs)
        AdbAuthWait.clockMs = { clock.get() }
        AdbAuthWait.elapsedMs = { clock.get() }
        AdbAuthWait.timeoutMs = AUTH_TIMEOUT_MS
        timers.clear()
        AdbAuthWait.schedule = { delayMs, task ->
            val timer = VirtualTimer(clock.get() + delayMs, task)
            timers += timer
            val cancel: () -> Unit = {
                timer.cancelled = true
                timers.remove(timer)
            }
            cancel
        }
        serverDown()
        // Robolectric reuses one sandbox (and so every app singleton) across the tests of a class.
        ShizukuReceiverStarter.resetForTesting()
        AdbAuthWait.resetForTesting()
        ShizukuStateMachine.resetForTesting()

        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        // Skips the worker's post-boot settling delay.
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1)
        // A saved port that answers the starter's probe routes the worker straight to adbd, with
        // no Wi-Fi constraint and no mDNS.
        prefs
            .edit()
            .putInt("mode", ShizukuSettings.LaunchMethod.ADB)
            .putInt("last_adb_port", adbd.port)
            .commit()

        val config =
            Configuration
                .Builder()
                .setExecutor(workExecutor)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? {
                            if (workerClassName == AdbStartWorker::class.java.name) startInputs += workerParameters.inputData
                            return null
                        }
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
    }

    private class VirtualTimer(
        val dueAt: Long,
        val task: () -> Unit,
    ) {
        @Volatile
        var cancelled = false
    }

    private val timers = CopyOnWriteArrayList<VirtualTimer>()

    /** Tasks the manager has scheduled (AdbAuthWait.schedule) that have neither run nor been cancelled. */
    val pendingTimers: Int get() = timers.count { !it.cancelled }

    /** Moves the wall and monotonic clocks on, running every scheduled task that falls due. */
    fun advanceTime(ms: Long) {
        val now = clock.addAndGet(ms)
        val due = timers.filter { it.dueAt <= now }.sortedBy { it.dueAt }
        timers.removeAll(due.toSet())
        due.filterNot { it.cancelled }.forEach { it.task() }
    }

    fun serverUp() = setShizukuBinder(Binder())

    fun serverDown() = setShizukuBinder(null)

    private fun setShizukuBinder(binder: Binder?) =
        Shizuku::class.java.getDeclaredField("binder").apply { isAccessible = true }.set(null, binder)

    // The test thread is Robolectric's main thread, which WorkManager hands workers to, so every
    // wait runs the main looper instead of blocking it.
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    fun waitUntil(
        what: () -> String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out after ${timeoutMs}ms waiting for ${what()}" }
            pump()
            Thread.sleep(10)
        }
    }

    /** Every render and enqueue decision queued so far has run. */
    fun settle() {
        pump()
        waitUntil({ "the start notification thread to go idle" }) { ShizukuReceiverStarter.awaitIdleForTesting(50) }
    }

    fun awaitOffer() = adbd.awaitOffer(pump = ::pump)

    /** Waits for the unique start work to finish; null if none was ever enqueued. */
    fun awaitStartWork(): WorkInfo.State? {
        settle()
        var last: List<WorkInfo> = emptyList()
        waitUntil({ "the start work to finish (last seen ${last.map { it.state }})" }) {
            last = workManager.getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME).get()
            last.all { it.state.isFinished }
        }
        return last.lastOrNull()?.state
    }

    /** WorkManager runs the given start request again, as after the system stopped it. */
    fun workerRerun(input: Data = startInputs.last()): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder
                .from(app, AdbStartWorker::class.java)
                .setInputData(input)
                .setRunAttemptCount(1)
                .build()
        return runBlocking { worker.doWork() }.also { settle() }
    }

    /**
     * The manager process dies: its connections close, in-memory state is lost and unflushed
     * apply()s never reach disk. WorkManager's database and the server (another process) survive.
     */
    fun processDeath() {
        adbd.dropAll()
        awaitStartWork()
        prefs.killProcess()
        AdbAuthWait.resetForTesting()
        ShizukuStateMachine.resetForTesting()
        ShizukuReceiverStarter.resetForTesting()
    }

    override fun close() {
        adbd.close()
        runCatching { awaitStartWork() }
        runCatching { settle() }
        workExecutor.shutdownNow()
        AdbAuthWait.clockMs = System::currentTimeMillis
        AdbAuthWait.elapsedMs = { android.os.SystemClock.elapsedRealtime() }
        AdbAuthWait.timeoutMs = AdbAuthWait.TIMEOUT_MS
        AdbAuthWait.schedule = AdbAuthWait.realSchedule
        timers.clear()
        serverDown()
    }

    private companion object {
        const val START_MS = 1_800_000_000_000L

        // Real seconds: a denied or unanswered dialog ends only at AdbClient's deadline.
        const val AUTH_TIMEOUT_MS = 3_000
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Robolectric has no AndroidKeyStore. AdbKey looks it up outside its try, so without a provider the
 * key cannot be built at all; one whose load fails takes AdbKey's real BouncyCastle fallback.
 */
@Suppress("DEPRECATION")
internal class UnusableAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "unusable AndroidKeyStore for tests") {
    init {
        put("KeyStore.AndroidKeyStore", UnusableKeyStoreSpi::class.java.name)
    }
}

internal class UnusableKeyStoreSpi : KeyStoreSpi() {
    override fun engineLoad(
        stream: InputStream?,
        password: CharArray?,
    ): Unit = throw java.io.IOException("AndroidKeyStore is unavailable in tests")

    override fun engineGetKey(
        alias: String?,
        password: CharArray?,
    ): Key? = null

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String?): Certificate? = null

    override fun engineGetCreationDate(alias: String?): Date? = null

    override fun engineSetKeyEntry(
        alias: String?,
        key: Key?,
        password: CharArray?,
        chain: Array<out Certificate>?,
    ) = Unit

    override fun engineSetKeyEntry(
        alias: String?,
        key: ByteArray?,
        chain: Array<out Certificate>?,
    ) = Unit

    override fun engineSetCertificateEntry(
        alias: String?,
        cert: Certificate?,
    ) = Unit

    override fun engineDeleteEntry(alias: String?) = Unit

    override fun engineAliases(): Enumeration<String> = Collections.emptyEnumeration()

    override fun engineContainsAlias(alias: String?): Boolean = false

    override fun engineSize(): Int = 0

    override fun engineIsKeyEntry(alias: String?): Boolean = false

    override fun engineIsCertificateEntry(alias: String?): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate?): String? = null

    override fun engineStore(
        stream: OutputStream?,
        password: CharArray?,
    ) = Unit
}
