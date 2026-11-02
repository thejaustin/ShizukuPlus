package af.shizuku.manager.home.changelog

/**
 * Splits a GitHub release body (markdown headings + bullet lists) into categorised sections.
 * Anything before the first heading lands in an "Other" section; the "Recent Releases" digest
 * appended to old release bodies is dropped.
 */
object ChangelogParser {
    private val HEADING = Regex("""^#{2,4}\s+(.+?)\s*$""")
    private val BULLET = Regex("""^\s*[-*•+]\s+(.+)$""")
    private val COMMIT_HASH =
        Regex("""\s+\(\[?[0-9a-f]{7,40}\]?(\([^)]+\))?\)\s*$|\s+\([0-9a-f]{7,40}\)\s*$""")
    private val CC_PREFIX =
        Regex(
            """^(fix|feat|chore|refactor|perf|test|docs|build|ci|style|revert|i18n)(\([^)]+\))?!?:\s*""",
            RegexOption.IGNORE_CASE,
        )

    // "...; feat(x): ..." - split where a new conventional-commit subject starts after a ';'.
    private val SPLIT_BEFORE_COMMIT =
        Regex(""";\s+(?=(?:fix|feat|chore|refactor|perf|test|docs|build|ci|style|revert|i18n)(?:\([^)]+\))?!?:)""", RegexOption.IGNORE_CASE)
    private val UI_SCOPE = Regex("""ui|ux|icon|theme|m3e|style|design|layout|animation|widget|dialog|settings""")
    private val FIX_WORDS = Regex("""^(fix|fixed|fixes|resolve[sd]?|repair|patch|prevent|stop|avoid|handle|correct|restore|re-?coerce|keep|make)\b|\bcrash|\bnpe\b|\bregression|\bbug\b|not working|broken|no longer|\bto fix\b|verifyerror|\b404\b""")
    private val UI_WORDS = Regex("""\b(ui|ux|themes?|theming|icons?|animations?|haptics?|layout|dialogs?|colou?rs?|typography|m3e?|design|polish|screens?|cards?|chips?|buttons?|switch(es)?|dark mode|fonts?|titles?|search|about|navigation|oled)\b""")
    private val FEATURE_WORDS = Regex("""^(add|added|adds|new|introduce[sd]?|support|supports|implement(ed)?|enable[sd]?|allow|auto|expand|unify|let|use|scope|improve[sd]?)\b|\bfeature\b|auto-""")
    private val DOCS_WORDS = Regex("""\b(readme|docs?|documentation|changelog|wiki|translat\w*|devlog|ai_devlog|i18n|locali[sz]\w*)\b""")

    private val README_ONLY = Regex("""\breadme(\.md)?\b""", RegexOption.IGNORE_CASE)

    fun parse(body: String): List<ChangeSection> {
        val unwrapped =
            body
                .replace(Regex("""<details>\s*<summary>(.*?)</summary>""", RegexOption.IGNORE_CASE), "\n\n#### $1\n\n")
                .replace(Regex("""</details>""", RegexOption.IGNORE_CASE), "")

        val cleaned = unwrapped.substringBefore("## 📦 Recent Releases").lines()
        val classified = mutableListOf<Pair<ChangeKind, String>>()
        var title = ""
        var items = mutableListOf<String>()
        var pending: StringBuilder? = null

        fun flushPending() {
            pending
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { items.add(it) }
            pending = null
        }

        fun flushSection() {
            flushPending()
            val headingKind = kindOfHeading(title)
            for (raw in items) {
                for (piece in splitCommits(raw)) {
                    // README edits are repo housekeeping, not user-facing changes.
                    if (README_ONLY.containsMatchIn(piece)) continue
                    classified.add(classify(piece, headingKind) to clean(piece))
                }
            }
            items = mutableListOf()
        }

        for (line in cleaned) {
            val h = HEADING.matchEntire(line)
            if (h != null) {
                flushSection()
                title = h.groupValues[1]
                continue
            }
            if (line.startsWith("# ")) continue
            val b = BULLET.matchEntire(line)
            if (b != null) {
                flushPending()
                pending = StringBuilder(b.groupValues[1])
            } else if (pending != null && line.isNotBlank() && line.startsWith("  ")) {
                pending!!.append(' ').append(line.trim())
            } else if (line.isBlank()) {
                flushPending()
            }
        }
        flushSection()

        // Fallback: If no classified sections were produced from the primary body,
        // extract highlights from the recent releases table/rollup.
        if (classified.isEmpty() && unwrapped.contains("## 📦 Recent Releases")) {
            val recentPart = unwrapped.substringAfter("## 📦 Recent Releases")
            val tableRowRegex = Regex("""\|\s*\[([^\]]+)\]\([^)]+\)[^|]*\|\s*([^|]+)\|""")
            for (line in recentPart.lines()) {
                val match = tableRowRegex.find(line) ?: continue
                val tag = match.groupValues[1].trim()
                val subj = match.groupValues[2].trim()
                if (subj.isBlank() || subj.equals("Highlight", ignoreCase = true) || README_ONLY.containsMatchIn(subj)) continue
                val desc = "$tag: $subj"
                val kind = classify(subj, null)
                classified.add(kind to clean(desc))
            }
        }

        if (classified.isEmpty()) {
            classified.add(ChangeKind.OTHER to "Maintenance and stability updates")
        }

        val order = ChangeKind.values()
        return classified
            .groupBy({ it.first }, { it.second })
            .map { (kind, list) -> ChangeSection(kind, "", list.distinct()) }
            .sortedBy { order.indexOf(it.kind) }
    }

    /**
     * Older release notes are one "Other" list of commit subjects, often several joined by ";" in a
     * single bullet. Split those so each change gets its own tag.
     */
    private fun splitCommits(raw: String): List<String> {
        val body = COMMIT_HASH.replace(raw.trim(), "")
        val parts = body.split(SPLIT_BEFORE_COMMIT).map { it.trim().trimEnd(';').trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(body) }
    }

    /** Tag for one change: its own conventional prefix first, then the heading, then its wording. */
    private fun classify(
        text: String,
        headingKind: ChangeKind?,
    ): ChangeKind {
        CC_PREFIX.find(text)?.let { m ->
            val type = m.groupValues[1].lowercase()
            val scope = m.groupValues[2].lowercase()
            val uiScope = UI_SCOPE.containsMatchIn(scope)
            return when (type) {
                "fix", "revert" -> ChangeKind.FIX
                "feat" -> if (uiScope) ChangeKind.UI else ChangeKind.FEATURE
                "style" -> ChangeKind.UI
                "docs" -> ChangeKind.DOCS
                "perf" -> ChangeKind.FIX
                else -> if (uiScope) ChangeKind.UI else ChangeKind.OTHER
            }
        }
        if (headingKind != null && headingKind != ChangeKind.OTHER) return headingKind
        return inferFromWording(text)
    }

    private fun inferFromWording(text: String): ChangeKind {
        val t = text.lowercase()
        return when {
            FIX_WORDS.containsMatchIn(t) -> ChangeKind.FIX
            UI_WORDS.containsMatchIn(t) -> ChangeKind.UI
            FEATURE_WORDS.containsMatchIn(t) -> ChangeKind.FEATURE
            DOCS_WORDS.containsMatchIn(t) -> ChangeKind.DOCS
            else -> ChangeKind.OTHER
        }
    }

    private fun kindOfHeading(heading: String): ChangeKind? {
        if (heading.isBlank()) return null
        val h = heading.lowercase()
        return when {
            "fix" in h || "bug" in h || "crash" in h -> ChangeKind.FIX
            "feat" in h || "added" in h || "new" in h || "enhanc" in h || "✨" in h -> ChangeKind.FEATURE
            "ui" in h || "visual" in h || "design" in h || "polish" in h || "🎨" in h -> ChangeKind.UI
            "doc" in h || "📖" in h || "📚" in h -> ChangeKind.DOCS
            h == "other" || h == "misc" -> null
            else -> null
        }
    }

    private fun clean(text: String): String {
        val noHash = COMMIT_HASH.replace(text.trim(), "")
        val stripped = CC_PREFIX.replaceFirst(noHash, "")
        return stripped.replaceFirstChar { it.uppercase() }
    }
}
