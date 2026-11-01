package rikka.shizuku.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rikka.shizuku.server.util.ArchiveVetting

class ArchiveVettingTest {
    // (mode string, name, link target or null), rendered like a toybox `tar -tv` line.
    private fun safe(vararg entries: Triple<String, String, String?>): Boolean {
        val names = entries.map { it.second }
        val verbose = entries.map { (mode, name, target) ->
            "$mode u0_a1/u0_a1 0 2026-11-01 00:00 $name" + (target?.let { " -> $it" } ?: "")
        }
        return ArchiveVetting.isSafe(names, verbose)
    }

    private fun dir(name: String) = Triple("drwx------", name, null)
    private fun file(name: String) = Triple("-rw-------", name, null)
    private fun link(name: String, target: String) = Triple("lrwxrwxrwx", name, target)

    @Test
    fun typicalAppDataWithLibLinkIsAccepted() {
        assertTrue(safe(dir("./"), link("./lib", "/data/app/~~x/com.example-1/lib/arm64"),
            dir("./shared_prefs/"), file("./shared_prefs/a.xml"), file("./databases/db")))
    }

    @Test
    fun writingThroughALinkIsRefused() {
        assertFalse(safe(link("./escape", "/data/data/com.victim"), file("./escape/shared_prefs/x.xml")))
        assertFalse(safe(link("escape", "/"), dir("escape/etc/")))
        // A file entry replacing the link name is refused too.
        assertFalse(safe(link("./a", "/tmp"), file("a")))
    }

    @Test
    fun escapingNamesAreRefused() {
        assertFalse(safe(file("../outside")))
        assertFalse(safe(file("./a/../../outside")))
        assertFalse(safe(file("/etc/hosts")))
    }

    @Test
    fun hardLinksDevicesAndMismatchesAreRefused() {
        assertFalse(safe(Triple("-rw-------", "hard", "/data/data/com.victim/x")))
        assertFalse(safe(Triple("crw-------", "dev", null)))
        assertFalse(safe(Triple("prw-------", "fifo", null)))
        assertFalse(ArchiveVetting.isSafe(listOf("a", "b"), listOf("-rw------- a")))
    }
}
