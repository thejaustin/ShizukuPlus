package af.shizuku.manager.harness

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbAuthPendingException
import af.shizuku.manager.adb.AdbAuthTimeoutException
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.AdbClient
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.worker.AdbStartWorker
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The same scenarios on plain JVM, for as long as Robolectric cannot boot in this module: the real
 * AdbClient and AdbKey hold a real connection to [FakeAdbd] and record through the real
 * AdbAuthWait into [FakePrefs]; the last guard is the real AdbStartWorker. What this cannot reach
 * (receivers, WorkManager, the state machine) is [OnePromptScenariosTest]'s.
 */
class OnePromptCoreScenariosTest {
    private val prefs = FakePrefs()
    private val adbd = FakeAdbd()
    private val clock = AtomicLong(1_800_000_000_000L)
    private val clients = Executors.newCachedThreadPool()

    @Before
    fun setUp() {
        ShizukuSettings.setPreferencesForTesting(prefs)
        AdbAuthWait.clockMs = { clock.get() }
        AdbAuthWait.elapsedMs = { clock.get() }
        // A denied dialog ends only at AdbClient's deadline (real seconds).
        AdbAuthWait.timeoutMs = 3_000
        AdbAuthWait.resetForTesting()
        ShizukuReceiverStarter.resetForTesting()
    }

    @After
    fun tearDown() {
        adbd.close()
        clients.shutdownNow()
        AdbAuthWait.resetForTesting()
        AdbAuthWait.clockMs = System::currentTimeMillis
        AdbAuthWait.elapsedMs = { android.os.SystemClock.elapsedRealtime() }
        AdbAuthWait.timeoutMs = AdbAuthWait.TIMEOUT_MS
        unmockkAll()
    }

    /** A connection as AdbStarter.startAdb makes it: connect() returns once adbd accepts the key. */
    private fun connect(): Future<Unit> = clients.submit<Unit> { AdbClient("127.0.0.1", adbd.port, key).use { it.connect() } }

    private fun Future<Unit>.failure(): Throwable? =
        try {
            get(30, TimeUnit.SECONDS)
            null
        } catch (e: ExecutionException) {
            e.cause
        }

    private fun processDeath(inFlight: Future<Unit>? = null) {
        adbd.dropAll()
        inFlight?.failure()
        prefs.killProcess()
        AdbAuthWait.resetForTesting()
        ShizukuReceiverStarter.resetForTesting()
    }

    /** WorkManager runs a start request again; only its stand-down guards can be reached here. */
    private fun workerRerun(
        explicit: Boolean,
        requestedAt: Long,
    ): ListenableWorker.Result {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf("explicit" to explicit, "requested_at" to requestedAt)
        return runBlocking { AdbStartWorker(context, params).doWork() }
    }

    @Test
    fun `marker-write-fails-no-offer`() {
        prefs.commitResult = false
        val failure = connect().failure()
        // Raised inside the offer's try, so it reaches callers as the non-retried auth failure.
        assertTrue(
            "start fails closed, got $failure",
            failure is AdbAuthTimeoutException && failure.message.orEmpty().contains("could not be recorded"),
        )
        assertEquals("key offers", 0, adbd.offers)
        assertFalse("wait slot released", AdbAuthWait.isWaiting())
    }

    @Test
    fun `two-starts-race-wait-slot`() {
        val holder = connect()
        adbd.awaitOffer()
        val loser = connect().failure()
        assertTrue("loser stands down before offering, got $loser", loser is AdbAuthPendingException)
        assertEquals("key offers", 1, adbd.offers)
        adbd.accept()
        assertEquals("holder connected", null, holder.failure())
        assertFalse("marker cleared on acceptance", AdbAuthWait.isUnanswered())
    }

    @Test
    fun `process-death-mid-wait`() {
        val start = connect()
        adbd.awaitOffer()
        processDeath(inFlight = start)
        assertTrue("marker committed before the key went out", prefs.onDisk(MARKER))
        assertEquals("rerun of the background request refused", ListenableWorker.Result.failure(), workerRerun(false, clock.get()))
        assertEquals("key offers", 1, adbd.offers)
    }

    @Test
    fun `tile-tap-older-marker`() {
        assertTrue(AdbAuthWait.markUnanswered())
        clock.addAndGet(1_000)
        val requestedAt = clock.get()
        adbd.onHello = { clock.addAndGet(1_000) }
        val start = connect()
        adbd.awaitOffer()
        adbd.reject()
        assertNotNull("rejected", start.failure())
        assertTrue("marker newer than the tile's request", AdbAuthWait.unansweredStamp() > requestedAt)
        assertEquals("rerun of the tile request refused", ListenableWorker.Result.failure(), workerRerun(true, requestedAt))
        assertEquals("key offers", 1, adbd.offers)
    }

    // clearUnanswered() is as durable as the set (both commit()).
    @Test
    fun `accepted-then-killed-before-flush`() {
        val start = connect()
        adbd.awaitOffer()
        adbd.accept()
        assertEquals("connected", null, start.failure())
        processDeath()
        assertEquals("key offers", 1, adbd.offers)
        assertFalse("marker after the key was accepted, once the process died before the flush", AdbAuthWait.isUnanswered())
    }

    // isUnanswered() fails closed like unansweredStamp() does.
    @Test
    fun `prefs-unreadable-fail-closed`() {
        prefs.failOnRead = true
        assertEquals("unansweredStamp() fails closed", Long.MAX_VALUE, AdbAuthWait.unansweredStamp())
        assertEquals("the worker's guard refuses", ListenableWorker.Result.failure(), workerRerun(true, clock.get()))
        assertTrue("isUnanswered() with unreadable storage (fail-open guards let starts through)", AdbAuthWait.isUnanswered())
    }

    private companion object {
        const val MARKER = "adb_auth_unanswered_at"

        // AdbKey's constructor needs BouncyCastle, whose jar this module's unit-test classpath
        // cannot load (see OnePromptScenariosTest), so the key is assembled without it: a JDK RSA
        // key for sign(), and any public-key blob, which FakeAdbd only counts.
        val key: AdbKey by lazy {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val key = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, AdbKey::class.java) as AdbKey
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            fun set(
                field: String,
                value: Any,
            ) = AdbKey::class.java.getDeclaredField(field).apply { isAccessible = true }.set(key, value)
            set("privateKey", pair.private)
            set("publicKey", pair.public)
            set("adbPublicKey\$delegate", lazyOf("QAAAAFake= shizuku+\u0000".toByteArray()))
            key
        }
    }
}
