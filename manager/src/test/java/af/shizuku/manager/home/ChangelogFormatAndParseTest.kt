package af.shizuku.manager.home

import af.shizuku.manager.home.changelog.ChangeKind
import af.shizuku.manager.home.changelog.ChangelogParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogFormatAndParseTest {
    @Test
    fun formatNotes_stripsDetailsTagsAndPreservesCommits() {
        val raw =
            """
            # 13.7.0.r3210

            ## What's new

            <details><summary>All commits in this release</summary>

            ### Fixed
            - fix(settings): title overlap and padding desync (640a1b2c)

            ### Other
            - docs: update setup instructions (011aaeba)

            </details>

            ## 📦 Recent Releases
            > 🚀 **Most recent major release — [13.7.0.r3210](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3210)**
            """.trimIndent()

        val formatted = ChangelogDialogFragment.formatNotes(raw)
        assertFalse("Should not contain details HTML tag", formatted.contains("<details>"))
        assertFalse("Should not contain summary HTML tag", formatted.contains("<summary>"))
        assertTrue("Should include fixed section", formatted.contains("Title overlap and padding desync"))
        assertTrue("Should strip commit hash suffix", !formatted.contains("640a1b2c"))
    }

    @Test
    fun formatNotes_fallsBackToRecentHighlightsWhenPrimaryIsReadmeOnly() {
        // Reproduces 13.7.0.r3216 where the only commits were README updates
        val raw =
            """
            # 13.7.0.r3216

            ### Other
            - Update README.md (6e75b679)
            - Update README.md (3aaea0a4)
            - Update README.md (dec9632c)

            ## 📦 Recent Releases

            > 🚀 **Most recent major release — [13.7.0.r3210](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3210)**
            > Quick safety check when an app asks for access
            > [Full changelog](https://github.com/thejaustin/ShizukuPlus/blob/master/CHANGELOG.md)

            | Release | Highlight |
            |:--|:--|
            | [13.7.0.r3216](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3216) | Update README.md |
            | [13.7.0.r3213](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3213) | Fewer crashes from old crash reports |
            """.trimIndent()

        val formatted = ChangelogDialogFragment.formatNotes(raw)
        assertFalse("Should prune empty ### Other header", formatted.contains("### Other"))
        assertTrue("Should contain maintenance banner", formatted.contains("Maintenance and stability updates."))
        assertTrue("Should contain Recent Highlights", formatted.contains("### Recent Highlights"))
        assertTrue("Should contain major release highlight", formatted.contains("13.7.0.r3210"))
    }

    @Test
    fun formatNotes_fallsBackToRecentHighlightsWhenPrimaryIsEmpty() {
        // Reproduces 13.7.0.r3215 where no commits existed before Recent Releases
        val raw =
            """
            # 13.7.0.r3215

            ## 📦 Recent Releases

            > 🚀 **Most recent major release — [13.7.0.r3210](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3210)**
            > Quick safety check when an app asks for access

            | Release | Highlight |
            |:--|:--|
            | [13.7.0.r3215](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3215) | Update README.md |
            """.trimIndent()

        val formatted = ChangelogDialogFragment.formatNotes(raw)
        assertTrue("Should contain Recent Highlights", formatted.contains("### Recent Highlights"))
        assertTrue("Should contain major release callout", formatted.contains("13.7.0.r3210"))
    }

    @Test
    fun changelogParser_parsesUnicodeBulletAndExtractsRecentHighlightsFallback() {
        val raw =
            """
            # 13.7.0.r3216

            ### Other
            • Update README.md (6e75b679)

            ## 📦 Recent Releases

            | Release | Highlight |
            |:--|:--|
            | [13.7.0.r3213](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3213) | fix: fewer crashes from old crash reports |
            | [13.7.0.r3210](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3210) | feat: quick safety check when an app asks |
            """.trimIndent()

        val sections = ChangelogParser.parse(raw)
        assertFalse("Sections list must not be empty", sections.isEmpty())
        assertTrue("Should classify fix highlight", sections.any { it.kind == ChangeKind.FIX })
        assertTrue("Should classify feature highlight", sections.any { it.kind == ChangeKind.FEATURE })
    }

    @Test
    fun changelogParser_usesAppNoteAndTrimsLongEntriesToFirstSentence() {
        val raw =
            """
            # 14.0.0

            ## What's new

            ### ✨ Features
            - **Scopes** — Dev wording about binder transactions. <!--app: **Choose what each app can do** — Limit an app to what it needs. -->
            - **Long one** — Plain first sentence here. Then a lot of technical detail about the AIDL stub and the binder cache that goes on well past the limit so it must be trimmed ([#12](https://github.com/x/y/issues/12)).
            """.trimIndent()

        val items = ChangelogParser.parse(raw).flatMap { it.items }
        assertTrue(items.any { it == "**Choose what each app can do** — Limit an app to what it needs." })
        assertTrue(items.any { it == "**Long one** — Plain first sentence here." })
    }
}
