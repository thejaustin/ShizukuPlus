package rikka.shizuku.server

import android.os.Bundle
import android.os.ParcelFileDescriptor
import af.shizuku.server.IAppInspector
import java.io.File
import java.nio.file.Files

class AppInspectorImpl : IAppInspector.Stub() {

    private fun execOutput(vararg args: String): String = try {
        Runtime.getRuntime().exec(args).inputStream.bufferedReader().readText().trim()
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

    override fun backupViaSystemAgent(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        // 'bu' is /system/bin/bu — the backup unit command used internally by 'adb backup'.
        // On Android ≤ 11, shell uid can call it without BACKUP permission because it was
        // designed for ADB (which already holds elevated trust). Android 12+ tightened this.
        // Output is a raw ADB backup stream: "ANDROID BACKUP" header + gzip-compressed tar.
        return pipeProcess("bu", "backup", "-noapk", packageName)
    }

    override fun dumpHeap(pid: Int, destPath: String?): Boolean {
        if (pid <= 0 || destPath.isNullOrBlank()) return false
        return try {
            Runtime.getRuntime().exec(arrayOf("am", "dumpheap", pid.toString(), destPath))
                .waitFor() == 0
        } catch (_: Exception) { false }
    }

    override fun readLogcat(packageName: String?, maxLines: Int): String {
        val lines = maxLines.coerceIn(1, 10_000)
        return try {
            if (packageName.isNullOrBlank()) {
                // No filter: dump last N lines from the global log
                execOutput("logcat", "-d", "-t", lines.toString())
            } else {
                // Find PIDs for this package via 'ps', then filter logcat by pid
                val psOutput = execOutput("ps", "-A", "-o", "PID,NAME")
                val pids = psOutput.lines()
                    .drop(1) // skip "PID NAME" header
                    .filter { it.contains(packageName) }
                    .mapNotNull { it.trim().split("\\s+".toRegex()).firstOrNull()?.toIntOrNull() }

                if (pids.isEmpty()) {
                    // No running process — filter by package name substring in log lines
                    execOutput("logcat", "-d", "-t", lines.toString())
                        .lines()
                        .filter { it.contains(packageName) }
                        .takeLast(lines)
                        .joinToString("\n")
                } else {
                    val pidArgs = pids.flatMap { listOf("--pid=$it") }
                    val cmd = (listOf("logcat", "-d", "-t", lines.toString()) + pidArgs).toTypedArray()
                    execOutput(*cmd)
                }
            }
        } catch (_: Exception) { "" }
    }

    override fun getOpenFiles(pid: Int): List<String> {
        if (pid <= 0) return emptyList()
        // /proc/<pid>/fd/ contains one symlink per open fd pointing at the real path.
        // Shell can traverse this for any user process on all stock AOSP builds.
        val fdDir = File("/proc/$pid/fd")
        if (!fdDir.exists() || !fdDir.canRead()) return emptyList()
        return try {
            fdDir.listFiles()?.mapNotNull { fd ->
                try {
                    Files.readSymbolicLink(fd.toPath()).toString()
                        .takeIf { it.isNotBlank() && !it.startsWith("socket:") && !it.startsWith("anon_inode:") }
                } catch (_: Exception) { null }
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    override fun getExportedProviders(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        val output = execOutput("pm", "dump", packageName)
        val authorities = mutableListOf<String>()
        // pm dump prints provider blocks like:
        //   ContentProviderRecord{...} u0 com.pkg/.Provider
        //     exported=true
        //     authority=com.pkg.provider
        var pendingExported = false
        for (line in output.lines()) {
            val t = line.trim()
            when {
                t.startsWith("ContentProviderRecord") -> pendingExported = false
                t == "exported=true" -> pendingExported = true
                pendingExported && t.startsWith("authority=") -> {
                    val auth = t.removePrefix("authority=").trim()
                    if (auth.isNotEmpty()) authorities.add(auth)
                    pendingExported = false
                }
                t.startsWith("authority=") && !pendingExported -> {
                    // Also catch single-line entries where exported is implicit (some OEMs)
                    // We'll skip these to avoid false positives
                }
            }
        }
        return authorities.distinct()
    }

    override fun callContentProvider(uri: String?, method: String?, arg: String?): Bundle {
        val result = Bundle()
        if (uri.isNullOrBlank() || !uri.startsWith("content://")) return result
        val cmd = mutableListOf("content", "call", "--uri", uri)
        if (!method.isNullOrBlank()) { cmd += listOf("--method", method) }
        if (!arg.isNullOrBlank()) { cmd += listOf("--arg", arg) }
        val output = execOutput(*cmd.toTypedArray())
        result.putString("raw", output)
        // Parse simple "Bundle[{key=value}]" form
        val inner = output.removePrefix("Bundle[{").removeSuffix("}]")
        for (pair in inner.split(", ")) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                result.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
            }
        }
        return result
    }

    override fun queryContentProvider(uri: String?, projection: String?): List<Bundle> {
        if (uri.isNullOrBlank() || !uri.startsWith("content://")) return emptyList()
        val cmd = mutableListOf("content", "query", "--uri", uri)
        if (!projection.isNullOrBlank()) { cmd += listOf("--projection", projection) }
        val output = execOutput(*cmd.toTypedArray())
        // Each row: "Row: N col1=val1, col2=val2, ..."
        return output.lines()
            .filter { it.trimStart().startsWith("Row:") }
            .map { row ->
                val bundle = Bundle()
                val content = row.substringAfter("Row:").trimStart().substringAfter(" ")
                for (pair in content.split(", ")) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        bundle.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
                    }
                }
                bundle
            }
    }

    override fun getDumpsys(serviceName: String?): String {
        if (serviceName.isNullOrBlank()) return ""
        // Alphanumeric + underscore + dot only — no shell injection possible
        if (!serviceName.matches(Regex("[a-zA-Z0-9_.\\-]+"))) return ""
        return execOutput("dumpsys", serviceName)
    }

    override fun readProcFile(pid: Int, filename: String?): String {
        if (pid <= 0 || filename.isNullOrBlank()) return ""
        // Strict whitelist — we expose only files that are useful for inspection and
        // cannot be used for exploitation (no exe, environ, mem, etc.).
        val allowed = setOf(
            "maps", "status", "cmdline", "comm", "oom_score", "oom_adj",
            "smaps_rollup", "net/tcp", "net/tcp6", "net/unix", "net/udp6"
        )
        if (filename !in allowed) return ""
        return try {
            File("/proc/$pid/$filename").readText()
        } catch (_: Exception) { "" }
    }

    override fun getRunningAppPids(): Bundle {
        val bundle = Bundle()
        try {
            val output = execOutput("ps", "-A", "-o", "PID,NAME")
            for (line in output.lines().drop(1)) {
                val parts = line.trim().split("\\s+".toRegex(), 2)
                val pid = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val name = parts.getOrNull(1) ?: continue
                // Package names always contain at least one dot
                if (name.contains('.') && !name.startsWith('/')) {
                    bundle.putInt(name, pid)
                }
            }
        } catch (_: Exception) {}
        return bundle
    }
}
