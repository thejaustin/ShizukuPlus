package af.shizuku.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ServerEventRedactionTest {
    @Test
    fun commandsPackagesPathsAndTokensAreRedacted() {
        val out =
            redactServerEventMessage(
                "Catastrophic command blocked from com.example.evil.app: pm grant com.bank.app x; curl -H Authorization: Bearer abc123 /data/data/com.bank.app/db",
            )
        assertFalse(out.contains("com.example.evil.app"), out)
        assertFalse(out.contains("com.bank.app"), out)
        assertFalse(out.contains("abc123"), out)
        assertFalse(out.contains("/data/data"), out)
    }

    @Test
    fun plainMessagesStayReadable() {
        assertEquals("Binder died", redactServerEventMessage("Binder died"))
        assertEquals("Failed to bind: <redacted>", redactServerEventMessage("Failed to bind: uid=10123"))
    }

    @Test
    fun stackTracesKeepFramesAndDropDetails() {
        val out =
            redactServerStackTrace(
                "java.lang.SecurityException: denied for com.bank.app\n" +
                    "\tat rikka.shizuku.server.ShizukuService.foo(ShizukuService.java:12)\n" +
                    "Caused by: java.io.IOException: open failed /data/data/com.bank.app/x",
            )
        assertFalse(out.contains("com.bank.app"), out)
        assertFalse(out.contains("/data/data"), out)
        assertEquals(true, out.contains("ShizukuService.foo(ShizukuService.java:12)"), out)
        assertEquals(true, out.contains("Caused by: java.io.IOException"), out)
    }
}
