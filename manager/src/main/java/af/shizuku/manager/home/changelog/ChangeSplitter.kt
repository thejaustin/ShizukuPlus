package af.shizuku.manager.home.changelog

/** One change split for scanning: a short [headline] always shown, and the longer [detail] behind a tap. */
data class SplitChange(
    val headline: String,
    val detail: String?,
)

object ChangeSplitter {
    // "**Title** — body", "**Title**: body" or "**Title** body". The bold lead is the headline.
    private val BOLD_LEAD = Regex("""^\*\*(.+?)\*\*\s*(?:[—–:]|-{1,2})?\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun split(text: String): SplitChange {
        val t = text.trim()
        val m = BOLD_LEAD.matchEntire(t)
        if (m != null) {
            val detail = m.groupValues[2].trim().ifEmpty { null }
            return SplitChange(m.groupValues[1].trim(), detail)
        }
        return SplitChange(t, null)
    }

    /** A headline with markdown stripped, for places that show plain text (the launch summary). */
    fun plainHeadline(text: String): String =
        split(text).headline.replace("`", "").replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")
}
