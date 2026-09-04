package rikka.shizuku.server

import af.shizuku.server.IPackageGovernorPlus

class PackageGovernorPlusImpl : IPackageGovernorPlus.Stub() {

    private fun exec(vararg args: String): Boolean = try {
        Runtime.getRuntime().exec(args).waitFor() == 0
    } catch (_: Exception) { false }

    private fun execOutput(vararg args: String): String = try {
        Runtime.getRuntime().exec(args).inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }

    override fun grantPermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return exec("pm", "grant", "--user", "0", packageName, permission)
    }

    override fun revokePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        return exec("pm", "revoke", "--user", "0", packageName, permission)
    }

    override fun getGrantedPermissions(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        val output = execOutput("pm", "dump", packageName)
        val granted = mutableListOf<String>()
        var inGrantedSection = false
        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "granted permissions:" -> inGrantedSection = true
                inGrantedSection && trimmed.startsWith("android.permission.") -> granted.add(trimmed)
                inGrantedSection && !trimmed.startsWith("android.") && trimmed.isNotEmpty() -> inGrantedSection = false
            }
        }
        return granted
    }

    override fun uninstallForUser(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "uninstall", "--user", "0", packageName)
    }

    override fun restoreSystemApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "install-existing", "--user", "0", packageName)
    }

    override fun suspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "suspend", "--user", "0", packageName)
    }

    override fun unsuspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return exec("pm", "unsuspend", "--user", "0", packageName)
    }

    override fun isAppSuspended(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val output = execOutput("pm", "dump", packageName)
        return output.lines().any { it.trim() == "suspended=true" }
    }

    override fun installApk(apkPath: String?): Boolean {
        if (apkPath.isNullOrBlank()) return false
        return exec("pm", "install", "-g", apkPath)
    }

    override fun isAppDebuggable(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // run-as exits 0 only when the app is debuggable; non-zero means not debuggable or not installed.
        return exec("run-as", packageName, "true")
    }

    override fun isBackupAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val output = execOutput("pm", "dump", packageName)
        // pm dump shows "allowBackup=true" or "allowBackup=false" in the package info block
        return output.lines().any { it.trim().equals("allowBackup=true", ignoreCase = true) }
    }

    override fun getAppDataDir(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val output = execOutput("pm", "dump", packageName)
        // Parse "dataDir=/data/data/com.example.app" from pm dump output
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("dataDir=")) {
                val dir = trimmed.removePrefix("dataDir=").trim()
                if (dir.isNotEmpty()) return dir
            }
        }
        return null
    }
}
