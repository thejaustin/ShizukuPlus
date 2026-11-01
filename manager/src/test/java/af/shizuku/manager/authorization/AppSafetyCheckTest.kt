package af.shizuku.manager.authorization

import af.shizuku.manager.authorization.AppSafetyCheck.Source
import af.shizuku.manager.authorization.AppSafetyCheck.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppSafetyCheckTest {
    private fun vt(stats: String) = """{"data":{"attributes":{"last_analysis_stats":$stats}}}"""

    @Test
    fun virusTotalVerdicts() {
        assertEquals(Verdict.Clean(66), AppSafetyCheck.parseVirusTotal(vt("""{"malicious":0,"suspicious":0,"undetected":60,"harmless":6}""")))
        assertEquals(Verdict.Flagged(4, 70, null), AppSafetyCheck.parseVirusTotal(vt("""{"malicious":3,"suspicious":1,"undetected":66}""")))
        // No engine ran: that's "unknown", never "clean".
        assertEquals(Verdict.Unknown, AppSafetyCheck.parseVirusTotal(vt("""{"malicious":0,"undetected":0}""")))
        assertNull(AppSafetyCheck.parseVirusTotal("""{"error":"x"}"""))
    }

    @Test
    fun pithusVerdicts() {
        assertEquals(
            Verdict.Flagged(12, 64, "Joker"),
            AppSafetyCheck.parsePithus("""{"report":{"vt":{"malicious":12,"undetected":52},"malware_bazaar":{"signature":"Joker"}}}"""),
        )
        assertEquals(Verdict.Clean(60), AppSafetyCheck.parsePithus("""{"report":{"vt":{"malicious":0,"undetected":60},"malware_bazaar":{}}}"""))
        // A MalwareBazaar match alone still counts as flagged.
        assertEquals(Verdict.Flagged(1, 1, "SpyNote"), AppSafetyCheck.parsePithus("""{"report":{"malware_bazaar":{"signature":"SpyNote"}}}"""))
        assertEquals(Verdict.Unknown, AppSafetyCheck.parsePithus("""{"report":{"apkid":{}}}"""))
    }

    @Test
    fun installSources() {
        assertEquals(Source.STORE, AppSafetyCheck.source("com.android.vending"))
        assertEquals(Source.OPEN_SOURCE_STORE, AppSafetyCheck.source("org.fdroid.fdroid"))
        assertEquals(Source.OPEN_SOURCE_STORE, AppSafetyCheck.source("dev.imranr.obtainium.plus"))
        assertEquals(Source.FILE, AppSafetyCheck.source("com.google.android.packageinstaller"))
        assertEquals(Source.OTHER_APP, AppSafetyCheck.source("com.termux"))
        assertEquals(Source.UNKNOWN, AppSafetyCheck.source(null))
    }
}
