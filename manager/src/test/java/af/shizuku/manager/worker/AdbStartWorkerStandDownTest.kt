package af.shizuku.manager.worker

import af.shizuku.manager.adb.AdbAuthWait
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class AdbStartWorkerStandDownTest :
    FunSpec({

        lateinit var nm: NotificationManager
        lateinit var context: Context
        lateinit var workerParams: WorkerParameters

        beforeTest {
            nm = mockk(relaxed = true)
            context = mockk(relaxed = true)
            workerParams = mockk(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
            AdbAuthWait.tryBegin() shouldBe true
        }

        afterTest {
            AdbAuthWait.end()
        }

        test("stands down without touching the shared notification") {
            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { nm.notify(any<Int>(), any<Notification>()) }
            verify(exactly = 0) { nm.cancel(any<Int>()) }
        }

        test("stands down before publishing progress, so it never shows over the holder's prompt") {
            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            verify(exactly = 0) { workerParams.progressUpdater }
        }

        test("a stood-down worker is not left counted as start work in flight") {
            val before = AdbAuthWait.starts.state.value.running

            AdbStartWorker(context, workerParams).doWork() shouldBe Result.failure()

            AdbAuthWait.starts.state.value.running shouldBe before
        }
    })
