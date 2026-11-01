package rikka.shizuku.server.util

/**
 * Decides whether a tar archive is safe to extract into a directory. App data commonly holds
 * symlinks (the `lib` link into /data/app, links apps make themselves), so those are allowed;
 * what isn't is an entry written through one, which is how a crafted archive escapes the
 * target. Hard links, device nodes and fifos, absolute names and `..` are refused outright.
 */
object ArchiveVetting {

    /**
     * @param names entry names from `tar -tf`.
     * @param verbose the matching `tar -tvf` lines, in the same order; the first character is
     *     the entry type, and links print "->" (toybox) or "link to" (GNU) after the name.
     */
    fun isSafe(names: List<String>, verbose: List<String>): Boolean {
        if (names.size != verbose.size) return false
        val normalized = names.map { normalize(it) ?: return false }
        val links = mutableSetOf<String>()
        verbose.forEachIndexed { i, line ->
            when (line.firstOrNull()) {
                'd' -> Unit
                // A regular-file mode with a link target is a hard link.
                '-' -> if (" -> " in line || " link to " in line) return false
                'l' -> if (normalized[i].isEmpty()) return false else links += normalized[i]
                else -> return false
            }
        }
        return normalized.withIndex().none { (i, name) ->
            links.any { link -> name.startsWith("$link/") || (name == link && verbose[i][0] != 'l') }
        }
    }

    /** "./a//b/" -> "a/b"; null when absolute or climbing out with "..". */
    private fun normalize(name: String): String? {
        if (name.startsWith("/")) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
