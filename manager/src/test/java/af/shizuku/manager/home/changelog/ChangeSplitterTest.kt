package af.shizuku.manager.home.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeSplitterTest {
    @Test
    fun boldLeadWithEmDashSplits() {
        val s = ChangeSplitter.split("**Dhizuku limited mode** — Settings → Experimental. Offers Profile Owner.")
        assertEquals("Dhizuku limited mode", s.headline)
        assertEquals("Settings → Experimental. Offers Profile Owner.", s.detail)
    }

    @Test
    fun boldLeadWithColonSplits() {
        val s = ChangeSplitter.split("**Backups**: now encrypted")
        assertEquals("Backups", s.headline)
        assertEquals("now encrypted", s.detail)
    }

    @Test
    fun boldOnlyHasNoDetail() {
        val s = ChangeSplitter.split("**Quick sorting**")
        assertEquals("Quick sorting", s.headline)
        assertNull(s.detail)
    }

    @Test
    fun plainTextIsAllHeadline() {
        val s = ChangeSplitter.split("Null-check in FooBar.init")
        assertEquals("Null-check in FooBar.init", s.headline)
        assertNull(s.detail)
    }

    @Test
    fun plainHeadlineStripsMarkdown() {
        assertEquals("Use rish now", ChangeSplitter.plainHeadline("**Use `rish` now** — more"))
    }
}
