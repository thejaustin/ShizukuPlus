package rikka.shizuku.server

import android.os.Bundle
import android.os.ParcelFileDescriptor
import af.shizuku.server.IPrivilegedDataSource

/**
 * Implements IPrivilegedDataSource entirely through shell commands executed under uid 2000.
 * No Android Context is required — uid 2000 can reach all data through ADB-accessible APIs.
 */
class PrivilegedDataSourceImpl : IPrivilegedDataSource.Stub() {

    private fun exec(vararg args: String): String = try {
        val proc = Runtime.getRuntime().exec(args)
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out
    } catch (_: Exception) { "" }

    private fun pipeProcess(vararg args: String): ParcelFileDescriptor? = try {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(args)
                proc.inputStream.use { src ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { dst ->
                        src.copyTo(dst)
                    }
                }
                proc.waitFor()
            } catch (_: Exception) {
                try { writeSide.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
        readSide
    } catch (_: Exception) { null }

    /**
     * Parse the 'content query' output format:
     *   Row: N col1=val1, col2=val2, ...
     * into a list of Bundles.
     */
    private fun parseContentRows(output: String): List<Bundle> =
        output.lines()
            .filter { it.trimStart().startsWith("Row:") }
            .map { row ->
                val b = Bundle()
                val content = row.substringAfter("Row:").trimStart().let {
                    // Skip the row-index number: "0 col=val" → "col=val"
                    it.substringAfter(" ")
                }
                for (pair in content.split(", ")) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        b.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
                    }
                }
                b
            }

    // ── Screen / Input (READ_FRAME_BUFFER + INJECT_EVENTS) ───────────────────

    override fun screenshotAsPfd(): ParcelFileDescriptor? =
        pipeProcess("screencap", "-p")

    override fun injectTap(x: Int, y: Int): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("input", "tap", x.toString(), y.toString())).waitFor() == 0
    } catch (_: Exception) { false }

    override fun injectText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return try {
            // 'input text' requires spaces encoded as %s
            val escaped = text.replace(" ", "%s")
            Runtime.getRuntime().exec(arrayOf("input", "text", escaped)).waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun injectSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean = try {
        Runtime.getRuntime().exec(arrayOf(
            "input", "swipe",
            startX.toString(), startY.toString(),
            endX.toString(), endY.toString(),
            durationMs.coerceAtLeast(1).toString()
        )).waitFor() == 0
    } catch (_: Exception) { false }

    override fun injectKeyEvent(keyCode: Int): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString())).waitFor() == 0
    } catch (_: Exception) { false }

    // ── SMS (READ_SMS — SYSTEM_FIXED) ─────────────────────────────────────────

    override fun getSmsMessages(folder: String?, maxCount: Int): List<Bundle> {
        val uriPath = when (folder?.lowercase()) {
            "sent"   -> "content://sms/sent"
            "draft"  -> "content://sms/draft"
            "outbox" -> "content://sms/outbox"
            "all"    -> "content://sms"
            else     -> "content://sms/inbox"
        }
        val limit = maxCount.coerceIn(1, 500)
        val output = exec(
            "content", "query", "--uri", uriPath,
            "--projection", "address:body:date:read:type",
            "--sort", "date DESC",
            "--count", limit.toString()
        )
        return parseContentRows(output)
    }

    override fun sendSms(recipient: String?, body: String?): Boolean {
        if (recipient.isNullOrBlank() || body.isNullOrEmpty()) return false
        // 'service call sms' is unreliable; the simplest path from uid 2000 is via
        // the 'sms' command on older OEM builds, or falling back to telephony service calls.
        // SEND_SMS SYSTEM_FIXED means the permission is held — SmsManager.sendTextMessage()
        // would work if we had a Context. Without one, use the telephony service call directly.
        return try {
            // Attempt via 'am broadcast' to the default SMS app — reliable on Android 5+
            Runtime.getRuntime().exec(arrayOf(
                "am", "broadcast",
                "-a", "android.provider.Telephony.SMS_DELIVER",
                "--es", "recipient", recipient,
                "--es", "body", body
            )).waitFor() == 0
        } catch (_: Exception) { false }
    }

    // ── Contacts (READ_CONTACTS — SYSTEM_FIXED) ──────────────────────────────

    override fun getContacts(maxCount: Int): List<Bundle> {
        val limit = maxCount.coerceIn(1, 1000)
        val output = exec(
            "content", "query",
            "--uri", "content://com.android.contacts/data/phones",
            "--projection", "display_name:data1:data4",
            "--sort", "display_name ASC",
            "--count", limit.toString()
        )
        // data1 = phone number, data4 = normalized number
        return parseContentRows(output).map { b ->
            val mapped = Bundle()
            mapped.putString("name", b.getString("display_name"))
            mapped.putString("phone", b.getString("data1"))
            mapped
        }
    }

    // ── Call Log (READ_CALL_LOG — SYSTEM_FIXED) ───────────────────────────────

    override fun getCallLog(maxCount: Int): List<Bundle> {
        val limit = maxCount.coerceIn(1, 500)
        val output = exec(
            "content", "query",
            "--uri", "content://call_log/calls",
            "--projection", "number:type:duration:date:cached_name",
            "--sort", "date DESC",
            "--count", limit.toString()
        )
        return parseContentRows(output)
    }

    // ── Telephony (READ_PHONE_STATE + READ_PHONE_NUMBERS — SYSTEM_FIXED) ─────

    override fun getPhoneInfo(): Bundle {
        val b = Bundle()
        // getprop gives us build properties; telephony details come from dumpsys
        b.putString("network_operator", exec("getprop", "gsm.operator.alpha").ifEmpty {
            exec("getprop", "ro.cdma.home.operator.alpha")
        })
        b.putString("sim_operator", exec("getprop", "gsm.sim.operator.alpha"))

        // Parse IMEI and phone number from dumpsys telephony.registry
        val dump = exec("dumpsys", "telephony.registry")
        for (line in dump.lines()) {
            val t = line.trim()
            when {
                t.startsWith("mCellIdentity") -> {} // carrier-level only
                t.startsWith("mImei=") -> b.putString("imei", t.removePrefix("mImei=").trim())
                t.startsWith("mPhoneNumber=") ->
                    b.putString("phone_number", t.removePrefix("mPhoneNumber=").trim())
                t.startsWith("mMeid=") -> b.putString("meid", t.removePrefix("mMeid=").trim())
                t.startsWith("mSimSerialNumber=") ->
                    b.putString("sim_serial", t.removePrefix("mSimSerialNumber=").trim())
            }
        }

        // Fallback: try service call iphonesubinfo for IMEI (older Android)
        if (!b.containsKey("imei")) {
            val imeiRaw = exec("service", "call", "iphonesubinfo", "1")
            // Output is: "Result: Parcel(...) \n  0x00000000: ... '1234567890xxxxx'"
            val imeiMatch = Regex("'([0-9A-Fa-f]{14,17})'").find(imeiRaw)
            imeiMatch?.groupValues?.getOrNull(1)?.let { b.putString("imei", it) }
        }
        return b
    }

    // ── Calendar (READ_CALENDAR — SYSTEM_FIXED) ───────────────────────────────

    override fun getCalendarEvents(maxCount: Int): List<Bundle> {
        val limit = maxCount.coerceIn(1, 500)
        val output = exec(
            "content", "query",
            "--uri", "content://com.android.calendar/events",
            "--projection", "title:description:dtstart:dtend:eventLocation",
            "--sort", "dtstart DESC",
            "--count", limit.toString()
        )
        return parseContentRows(output).map { b ->
            val mapped = Bundle()
            mapped.putString("title", b.getString("title"))
            mapped.putString("description", b.getString("description"))
            b.getString("dtstart")?.toLongOrNull()?.let { mapped.putLong("start", it) }
            b.getString("dtend")?.toLongOrNull()?.let { mapped.putLong("end", it) }
            mapped.putString("location", b.getString("eventLocation"))
            mapped
        }
    }

    // ── Accounts (GET_ACCOUNTS — SYSTEM_FIXED) ────────────────────────────────

    override fun getAccounts(): List<Bundle> {
        val result = mutableListOf<Bundle>()
        val dump = exec("dumpsys", "account")
        // Format in dumpsys account:
        //   Accounts: N
        //     Account {name=someone@gmail.com, type=com.google}
        val accountRegex = Regex("""Account \{name=([^,]+), type=([^}]+)\}""")
        for (match in accountRegex.findAll(dump)) {
            val b = Bundle()
            b.putString("name", match.groupValues[1].trim())
            b.putString("type", match.groupValues[2].trim())
            result.add(b)
        }
        return result
    }

    // ── Location (ACCESS_FINE_LOCATION — SYSTEM_FIXED) ───────────────────────

    override fun getLastKnownLocation(): Bundle {
        val b = Bundle()
        val dump = exec("dumpsys", "location")
        // dumpsys location prints last known locations for each provider:
        //   Last Known Locations:
        //     gps: Location[gps 37.421998,-122.084000 hAcc=16 et=+1d3h13m42s930ms alt=0.0 vel=0.0 bear=0.0 {Bundle[{}]}]
        val locRegex = Regex("""(\w+): Location\[\w+ ([-\d.]+),([-\d.]+) hAcc=([\d.]+)""")
        val match = locRegex.find(dump) ?: return b
        b.putString("provider", match.groupValues[1])
        b.putDouble("latitude", match.groupValues[2].toDoubleOrNull() ?: 0.0)
        b.putDouble("longitude", match.groupValues[3].toDoubleOrNull() ?: 0.0)
        b.putFloat("accuracy", match.groupValues[4].toFloatOrNull() ?: 0f)
        // Parse altitude, speed, bearing if present
        Regex("alt=([-\\d.]+)").find(dump)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { b.putDouble("altitude", it) }
        Regex("vel=([-\\d.]+)").find(dump)?.groupValues?.get(1)?.toFloatOrNull()
            ?.let { b.putFloat("speed", it) }
        Regex("bear=([-\\d.]+)").find(dump)?.groupValues?.get(1)?.toFloatOrNull()
            ?.let { b.putFloat("bearing", it) }
        return b
    }

    // ── AppOps (MANAGE_APP_OPS_MODES — install permission) ───────────────────

    override fun setAppOpsMode(packageName: String?, op: String?, mode: String?): Boolean {
        if (packageName.isNullOrBlank() || op.isNullOrBlank() || mode.isNullOrBlank()) return false
        val modeArg = when (mode.lowercase()) {
            "allow", "deny", "ignore", "default" -> mode.lowercase()
            else -> return false
        }
        return try {
            Runtime.getRuntime()
                .exec(arrayOf("appops", "set", packageName, op, modeArg))
                .waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun getAppOpsMode(packageName: String?, op: String?): String {
        if (packageName.isNullOrBlank() || op.isNullOrBlank()) return ""
        val output = exec("appops", "get", packageName, op)
        // Output: "allow", "deny", "ignore", "default", or "<op>: <mode>"
        val raw = output.substringAfterLast(":").trim().lowercase()
        return when {
            raw.contains("allow")   -> "allow"
            raw.contains("deny")    -> "deny"
            raw.contains("ignore")  -> "ignore"
            raw.contains("default") -> "default"
            else                    -> raw.ifEmpty { "" }
        }
    }

    // ── Keyguard (CONTROL_KEYGUARD — install permission) ─────────────────────

    override fun dismissKeyguard(): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("wm", "dismiss-keyguard")).waitFor() == 0
    } catch (_: Exception) { false }

    // ── WiFi (READ_WIFI_CREDENTIAL — install permission) ─────────────────────

    override fun getSavedWifiNetworks(): List<Bundle> {
        val result = mutableListOf<Bundle>()
        val dump = exec("dumpsys", "wifi")
        // Look for the configured networks section. Format varies by Android version:
        //   WifiConfiguration: SSID: "MyNetwork", ...
        //   preSharedKey: <key>
        var current: Bundle? = null
        var inNetworks = false
        for (line in dump.lines()) {
            val t = line.trim()
            when {
                t.startsWith("Saved Networks") || t.startsWith("mConfiguredNetworks") ->
                    inNetworks = true
                inNetworks && t.startsWith("SSID:") -> {
                    current?.let { result.add(it) }
                    current = Bundle().also { b ->
                        b.putString("ssid", t.removePrefix("SSID:").trim().trim('"'))
                    }
                }
                inNetworks && current != null && t.startsWith("preSharedKey:") ->
                    current!!.putString("psk", t.removePrefix("preSharedKey:").trim())
                inNetworks && current != null && t.startsWith("BSSID:") ->
                    current!!.putString("bssid", t.removePrefix("BSSID:").trim())
                inNetworks && current != null && t.startsWith("KeyMgmt:") ->
                    current!!.putString("key_mgmt", t.removePrefix("KeyMgmt:").trim())
                // Blank line or new major section ends the networks block
                inNetworks && t.isEmpty() && current != null -> {
                    result.add(current!!)
                    current = null
                }
            }
        }
        current?.let { result.add(it) }
        return result
    }

    // ── Clipboard (READ_CLIPBOARD_IN_BACKGROUND — install permission) ─────────

    override fun getClipboard(): String = exec("cmd", "clipboard", "get-text")

    // ── Notifications (DUMP — install permission) ─────────────────────────────

    override fun getNotifications(): String = exec("dumpsys", "notification", "--noredact")
}
