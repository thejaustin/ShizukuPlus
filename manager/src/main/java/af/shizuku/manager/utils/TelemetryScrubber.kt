package af.shizuku.manager.utils

/**
 * Removes things from crash-report text that identify the user's network or person: IP addresses
 * (v4 and v6), MAC addresses, Wi-Fi network names and email addresses.
 */
object TelemetryScrubber {
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    // Full form (8 groups), or compressed with "::" (fe80::1, ::1). Not 2-3 groups: that is a time.
    private val ipv6Full = Regex("""\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""")
    private val ipv6Compressed =
        Regex("""(?<![\w:])(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?::(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?(?![\w:])""")
    private val mac = Regex("""\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\b""")
    private val ssid = Regex("""(?i)(ssid[=:\s]+)("[^"]*"|\S+)""")
    private val email = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")

    fun scrub(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        // Cheap pre-check (this runs for every breadcrumb, on the main thread during start): every
        // pattern below needs one of these characters, or the word ssid.
        if (text.none { it.isDigit() || it == '@' || it == ':' || it == '-' } && !text.contains("ssid", ignoreCase = true)) return text
        return text
            .replace(email, "<email>")
            .replace(mac, "<mac>")
            .replace(ipv4, "<ip>")
            .replace(ipv6Full, "<ip>")
            .replace(ipv6Compressed) { if (it.value == "::") it.value else "<ip>" }
            .replace(ssid) { it.groupValues[1] + "<ssid>" }
    }
}
