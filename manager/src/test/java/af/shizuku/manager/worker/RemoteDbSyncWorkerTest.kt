package af.shizuku.manager.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import af.shizuku.manager.ShizukuSettings
import org.junit.Assert.assertEquals
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, instrumentedPackages=["androidx.work.testing"])
class RemoteDbSyncWorkerTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(ShizukuSettings::class)
        every { ShizukuSettings.getLastDbUpdate() } returns 0L // Force fetch
    }

    @After
    fun teardown() {
        unmockkStatic(ShizukuSettings::class)
    }

    @Test
    fun testDoWorkThrowsExceptionReturnsRetry() = runTest {
        val worker = spyk(TestListenableWorkerBuilder<RemoteDbSyncWorker>(context).build())

        // Mock fetch to throw Exception
        every { worker["fetch"](any<String>()) } throws RuntimeException("Mock network failure")

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
    }
}
