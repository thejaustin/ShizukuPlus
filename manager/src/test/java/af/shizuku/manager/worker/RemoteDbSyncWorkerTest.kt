package af.shizuku.manager.worker

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll

class RemoteDbSyncWorkerTest :
    FunSpec({

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

            val worker = RemoteDbSyncWorker(context, workerParams)
            worker.fetchDb = { throw RuntimeException("Simulated network error") }
            val result = worker.doWork()

            result shouldBe Result.retry()
        }
    })
