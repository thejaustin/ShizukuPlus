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
}
