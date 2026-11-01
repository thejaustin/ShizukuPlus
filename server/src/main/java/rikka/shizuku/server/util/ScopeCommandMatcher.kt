package rikka.shizuku.server.util

/**
 * Maps a newProcess command line to the per-app API scopes (#645) it exercises.
 *
 * Clients rarely exec `pm` directly: aShell and most terminals run `sh -c "<command>"`, often
 * with full paths (`/system/bin/pm`), chains (`cd x; pm list`) or quotes. Matching on a leading
 * "pm " or " pm " missed all of those (#648), so tools are matched as whole words anywhere in the
 * joined command, with shell separators and path slashes as word boundaries.
 *
 * Commands written to an interactive shell's stdin later, or built at run time, can't be seen
 * here; [isOpaque] flags those so the caller can refuse them for an app with a scope turned off.
 */
object ScopeCommandMatcher {
    const val PACKAGE_MANAGER = "package_manager"
    const val ACTIVITY_MANAGER = "activity_manager"
    const val APPOPS = "appops"
    const val SYSTEM_SETTINGS = "system_settings"

    // Start of string, whitespace, a shell separator or quote, or the '/' of a path.
    private const val B = """(?:^|[\s;&|()`'"/])"""

    // Followed by end of string, whitespace, a separator or a quote.
    private const val E = """(?=$|[\s;&|()`'"])"""

    private val rules: List<Pair<String, Regex>> =
        listOf(
            PACKAGE_MANAGER to Regex("${B}(?:pm${E}|cmd\\s+package${E})"),
            ACTIVITY_MANAGER to Regex("${B}(?:am${E}|cmd\\s+(?:activity|activity_task)${E})"),
            APPOPS to Regex("${B}(?:appops${E}|cmd\\s+appops${E})"),
            SYSTEM_SETTINGS to Regex("${B}(?:settings${E}|svc${E}|cmd\\s+(?:settings|power)${E})"),
        )

    @JvmField
    val TOOL_SCOPES = listOf(PACKAGE_MANAGER, ACTIVITY_MANAGER, APPOPS, SYSTEM_SETTINGS)

    private val SHELLS = setOf("sh", "bash", "mksh", "ash", "dash", "zsh")

    // Run-time expansion, indirection, a nested shell or a raw binder call: what actually runs
    // can't be read from the command line. `.` counts only in command position (`. script`).
    private val OPAQUE =
        Regex("""\$[({A-Za-z_]|`|${B}(?:eval|source|xargs|base64|service\s+call|${SHELLS.joinToString("|")})$E|(?:^|[;&|(]\s*)\.\s""")

    @JvmStatic
    fun requiredScopes(cmd: Array<String>?): Set<String> {
        if (cmd.isNullOrEmpty()) return emptySet()
        val full = cmd.joinToString(" ")
        // Shell quoting and escapes don't change the word that runs: p"m", 'p'm and p\m are all pm.
        val unquoted = full.replace("\"", "").replace("'", "").replace("\\", "")
        return rules.filter { (_, re) -> re.containsMatchIn(full) || re.containsMatchIn(unquoted) }
            .mapTo(LinkedHashSet()) { it.first }
    }

    /**
     * Whether [requiredScopes] can't vouch for this command: a shell started without `-c`
     * (commands arrive on stdin later, or come from a script file), or a command line that
     * expands or builds what it runs.
     */
    @JvmStatic
    fun isOpaque(cmd: Array<String>?): Boolean {
        if (cmd.isNullOrEmpty()) return false
        // `toybox sh` / `busybox sh` are shells too.
        val multiCall = cmd[0].substringAfterLast('/') in setOf("toybox", "busybox")
        val args = if (multiCall) cmd.drop(1) else cmd.toList()
        val program = args.firstOrNull()?.substringAfterLast('/') ?: return false
        val rest = args.drop(1)
        if (program in SHELLS) {
            if (rest.none { it == "-c" || (it.startsWith("-") && !it.startsWith("--") && 'c' in it) }) return true
            // Per argument, so the `-c` script's own start counts as command position.
            return rest.any { OPAQUE.containsMatchIn(it) }
        }
        return OPAQUE.containsMatchIn(args.joinToString(" "))
    }
}
