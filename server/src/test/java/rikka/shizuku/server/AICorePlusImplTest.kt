package rikka.shizuku.server

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import android.util.Log

class AICorePlusImplTest {

    private lateinit var aiCorePlusImpl: AICorePlusImpl
    private lateinit var runtimeMock: Runtime
    private lateinit var processMock: Process

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        aiCorePlusImpl = AICorePlusImpl()
        runtimeMock = mockk(relaxed = true)
        processMock = mockk(relaxed = true)

        mockkStatic(Runtime::class)
        every { Runtime.getRuntime() } returns runtimeMock
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `simulateTouch returns true when process exits with 0`() {
        // Arrange
        val x = 100.0f
        val y = 200.0f
        val expectedArgs = arrayOf("input", "tap", "100.0", "200.0")

        every { runtimeMock.exec(expectedArgs) } returns processMock
        every { processMock.waitFor() } returns 0

        // Act
        val result = aiCorePlusImpl.simulateTouch(x, y)

        // Assert
        assertTrue("simulateTouch should return true when process exits with 0", result)
        verify { runtimeMock.exec(expectedArgs) }
        verify { processMock.waitFor() }
    }

    @Test
    fun `simulateTouch returns false when process exits with non-zero`() {
        // Arrange
        val x = 100.0f
        val y = 200.0f
        val expectedArgs = arrayOf("input", "tap", "100.0", "200.0")

        every { runtimeMock.exec(expectedArgs) } returns processMock
        every { processMock.waitFor() } returns 1

        // Act
        val result = aiCorePlusImpl.simulateTouch(x, y)

        // Assert
        assertFalse("simulateTouch should return false when process exits with non-zero", result)
        verify { runtimeMock.exec(expectedArgs) }
        verify { processMock.waitFor() }
    }

    @Test
    fun `simulateTouch returns false when an exception occurs`() {
        // Arrange
        val x = 100.0f
        val y = 200.0f
        val expectedArgs = arrayOf("input", "tap", "100.0", "200.0")

        every { runtimeMock.exec(any<Array<String>>()) } throws RuntimeException("Mocked exception")

        // Act
        val result = aiCorePlusImpl.simulateTouch(x, y)

        // Assert
        assertFalse("simulateTouch should return false when an exception occurs", result)
        verify { runtimeMock.exec(expectedArgs) }
    }
}
