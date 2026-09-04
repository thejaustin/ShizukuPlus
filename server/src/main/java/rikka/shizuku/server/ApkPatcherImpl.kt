package rikka.shizuku.server

import android.os.ParcelFileDescriptor
import af.shizuku.server.IApkPatcher
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ApkPatcherImpl : IApkPatcher.Stub() {

    companion object {
        private const val TMP_DIR = "/data/local/tmp/splus_td"
    }

    // pkg → list of saved original APK paths (base first, then splits)
    private val sessions = ConcurrentHashMap<String, List<String>>()

    private fun exec(vararg args: String): String = try {
        val proc = Runtime.getRuntime().exec(args)
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out
    } catch (_: Exception) { "" }

    private fun execCode(vararg args: String): Int = try {
        Runtime.getRuntime().exec(args).waitFor()
    } catch (_: Exception) { -1 }

    private fun pipe(vararg args: String): ParcelFileDescriptor? = try {
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

    private fun pipeFrom(pfd: ParcelFileDescriptor, vararg args: String): Boolean = try {
        val proc = ProcessBuilder(*args).start()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { src ->
                    proc.outputStream.use { dst -> src.copyTo(dst) }
                }
            } catch (_: Exception) {
                proc.outputStream.runCatching { close() }
            }
        }.also { it.isDaemon = true }.start()
        proc.waitFor() == 0
    } catch (_: Exception) { false }

    private fun findAllApks(packageName: String): List<String> {
        val out = exec("pm", "path", packageName)
        return out.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
    }

    /**
     * Install a list of APK files atomically via a pm install session.
     * Works for both single APKs and split APK sets.
     * All APKs must be signed with the same certificate.
     */
    private fun installViaSession(apkPaths: List<String>, grantPerms: Boolean = true): Boolean {
        if (apkPaths.isEmpty()) return false

        val sessionArgs = buildList {
            add("pm"); add("install-create")
            if (grantPerms) add("-g")
        }
        val sessionOut = exec(*sessionArgs.toTypedArray())
        val sessionId = Regex("\\[(\\d+)]").find(sessionOut)?.groupValues?.get(1)?.toIntOrNull()
            ?: return false

        for (path in apkPaths) {
            // Derive the canonical split name from the filename (strip our temp prefix)
            val fileName = File(path).name
            val splitName = when {
                fileName.contains("_orig_") -> fileName.substringAfter("_orig_")
                fileName.contains("_dbg_")  -> fileName.substringAfter("_dbg_")
                else                        -> fileName
            }
            if (execCode("pm", "install-write", sessionId.toString(), splitName, path) != 0) {
                execCode("pm", "install-abandon", sessionId.toString())
                return false
            }
        }
        return execCode("pm", "install-commit", sessionId.toString()) == 0
    }

    override fun prepareTempDebug(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (sessions.containsKey(packageName)) return true

        val apkPaths = findAllApks(packageName)
        if (apkPaths.isEmpty()) return false

        File(TMP_DIR).mkdirs()

        // Save all original APKs to temp dir
        val origPaths = mutableListOf<String>()
        for (path in apkPaths) {
            val fileName = File(path).name  // base.apk, split_config.arm64_v8a.apk, etc.
            val dest = "$TMP_DIR/${packageName}_orig_$fileName"
            if (execCode("cp", path, dest) != 0) {
                origPaths.forEach { File(it).delete() }
                return false
            }
            origPaths.add(dest)
        }

        // Patch base APK manifest; sign ALL APKs with the same ephemeral key.
        // Android requires all APKs in a session to share a certificate, so signing
        // must happen before uninstall, and all with the same ApkSigner instance.
        val patchedPaths = mutableListOf<String>()
        try {
            for (origPath in origPaths) {
                val origBytes = File(origPath).readBytes()
                val fileName = File(origPath).name
                val isBase = fileName.endsWith("_orig_base.apk") || !fileName.contains("split_")
                val toSign = if (isBase) ApkBinaryXmlPatcher.patch(origBytes) else origBytes
                val signed = ApkSigner.sign(toSign)
                val patchedPath = "$TMP_DIR/${packageName}_dbg_${fileName.substringAfter("_orig_")}"
                File(patchedPath).writeBytes(signed)
                patchedPaths.add(patchedPath)
            }
        } catch (_: Exception) {
            origPaths.forEach { File(it).delete() }
            patchedPaths.forEach { File(it).delete() }
            return false
        }

        // pm uninstall -k preserves all app data
        if (execCode("pm", "uninstall", "--user", "0", "-k", packageName) != 0) {
            origPaths.forEach { File(it).delete() }
            patchedPaths.forEach { File(it).delete() }
            return false
        }

        val installed = installViaSession(patchedPaths)
        patchedPaths.forEach { File(it).delete() }

        if (!installed) {
            // Attempt recovery by reinstalling the originals
            installViaSession(origPaths)
            origPaths.forEach { File(it).delete() }
            return false
        }

        sessions[packageName] = origPaths
        return true
    }

    override fun streamDataDir(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        if (!sessions.containsKey(packageName)) return null
        return pipe("run-as", packageName, "tar", "-czf", "-", "-C", "/data/data/$packageName", ".")
    }

    override fun restoreDataDir(packageName: String?, tarStream: ParcelFileDescriptor?): Boolean {
        if (packageName.isNullOrBlank() || tarStream == null) return false
        if (!sessions.containsKey(packageName)) return false
        return pipeFrom(tarStream, "run-as", packageName, "tar", "-xzf", "-", "-C", "/data/data/$packageName")
    }

    override fun restoreOriginal(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val origPaths = sessions[packageName] ?: return false

        val ok = execCode("pm", "uninstall", "--user", "0", "-k", packageName) == 0 &&
                 installViaSession(origPaths)

        sessions.remove(packageName)
        origPaths.forEach { File(it).delete() }
        return ok
    }

    override fun streamOriginalApk(packageName: String?): ParcelFileDescriptor? {
        if (packageName.isNullOrBlank()) return null
        // Stream the base APK (prefer explicit base.apk, fall back to first)
        val origPath = sessions[packageName]
            ?.firstOrNull { it.endsWith("_orig_base.apk") }
            ?: sessions[packageName]?.firstOrNull()
            ?: return null
        return pipe("cat", origPath)
    }

    override fun isTempDebugging(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return sessions.containsKey(packageName)
    }

    override fun cleanupAllTempDebug() {
        for (pkg in sessions.keys.toList()) {
            restoreOriginal(pkg)
        }
        // Remove any orphaned temp files from a previous server crash
        val tracked = sessions.values.flatten().toSet()
        File(TMP_DIR).listFiles()?.forEach { f ->
            if ((f.name.contains("_orig_") || f.name.contains("_dbg_")) &&
                !tracked.contains(f.absolutePath)) {
                f.delete()
            }
        }
    }
}
