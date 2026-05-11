package af.shizuku.manager.worker

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.AppContextManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.net.URL
import timber.log.Timber

class RemoteDbSyncWorkerTest : FunSpec({

    val context: Context = mockk(relaxed = true)
    val workerParams: WorkerParameters = mockk(relaxed = true)

    beforeTest {
        mockkStatic(ShizukuSettings::class)
        mockkObject(AppContextManager)
    }

    afterTest {
        unmockkAll()
    }

    test("doWork returns success when cache is fresh") {
        // Return a recent timestamp to skip fetch
        every { ShizukuSettings.getLastDbUpdate() } returns System.currentTimeMillis() - 1000L

        val worker = RemoteDbSyncWorker(context, workerParams)
        val result = worker.doWork()

        result shouldBe Result.success()
    }

    test("doWork returns retry when fetch throws exception") {
        every { ShizukuSettings.getLastDbUpdate() } returns 0L

        mockkConstructor(URL::class)
        every { anyConstructed<URL>().openConnection() } throws RuntimeException("Simulated network error")

        val worker = RemoteDbSyncWorker(context, workerParams)
        val result = worker.doWork()

        result shouldBe Result.retry()
    }
})
