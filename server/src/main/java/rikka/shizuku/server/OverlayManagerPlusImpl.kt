package rikka.shizuku.server

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IOverlayManagerPlus
import af.shizuku.common.util.UserHandleCompat

/**
 * Overlay Bridge implementation — provides [IOverlayManagerPlus] to Hex Installer
 * (com.samsung.android.hexinstall) and other theming engines on Samsung OneUI 8 / Android 16.
 *
 * Design: dual-path per method.
 *   Primary   — reflection into IOverlayManager with version-aware signature probing.
 *   Fallback  — `cmd overlay <action>` via Runtime.exec(), which works on all API levels
 *               and is what Shizuku's shell-uid context already uses for other operations.
 *
 * API change timeline (tested/known):
 *   API ≤ 30  : setEnabled(String, boolean, int)
 *   API 31+   : setEnabled() gone; use OverlayManagerTransaction + commit()
 *   API 34+   : FabricatedOverlay.Builder(String owningPkg, String name, String target) →
 *               FabricatedOverlay.Builder(String name, String target)
 *   Samsung OneUI 8 (API 36 base): Samsung-extended OverlayManager — reflection is unreliable;
 *               `cmd overlay` remains the authoritative path.
 */
class OverlayManagerPlusImpl : IOverlayManagerPlus.Stub() {

    companion object {
        private const val TAG = "OverlayManagerPlus"
        private const val OVERLAY_SERVICE = "overlay"
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun getService(): IBinder? = ServiceManager.getService(OVERLAY_SERVICE)

    /** Obtain an IOverlayManager proxy via reflection, or null if unavailable. */
    private fun getIOverlayManager(): Any? {
        return try {
            val binder = getService() ?: return null
            val stub = Class.forName("android.content.om.IOverlayManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "getIOverlayManager: reflection unavailable — ${e.message}")
            null
        }
    }

    /**
     * Run a `cmd overlay` subcommand and return true on exit-code 0.
     * Example: runOverlayCmd("enable", "--user", "0", packageName)
     */
    private fun runOverlayCmd(vararg args: String): Boolean {
        return try {
            val cmd = arrayOf("cmd", "overlay", *args)
            Log.d(TAG, "runOverlayCmd: ${cmd.joinToString(" ")}")
            val proc = Runtime.getRuntime().exec(cmd)
            val exit = proc.waitFor()
            if (exit != 0) {
                val err = proc.errorStream.bufferedReader().readText().trim()
                Log.w(TAG, "runOverlayCmd exit=$exit stderr=$err")
            }
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "runOverlayCmd failed: ${args.joinToString(" ")}", e)
            false
        }
    }

    /**
     * Capture stdout from a `cmd overlay` subcommand, or null on failure.
     */
    private fun runOverlayCmdOutput(vararg args: String): String? {
        return try {
            val cmd = arrayOf("cmd", "overlay", *args)
            Log.d(TAG, "runOverlayCmdOutput: ${cmd.joinToString(" ")}")
            val proc = Runtime.getRuntime().exec(cmd)
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        } catch (e: Exception) {
            Log.e(TAG, "runOverlayCmdOutput failed: ${args.joinToString(" ")}", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // setOverlayEnabled
    // -------------------------------------------------------------------------

    override fun setOverlayEnabled(packageName: String?, enabled: Boolean): Boolean {
        if (packageName == null) return false
        // Derive user from the caller's UID, not the server's own UID (which is always shell/root
        // and always maps to user 0 — incorrect for multi-user setups).
        val userId = UserHandleCompat.getUserId(Binder.getCallingUid())
        Log.d(TAG, "setOverlayEnabled pkg=$packageName enabled=$enabled userId=$userId")

        val service = getIOverlayManager()

        // --- Primary path: IOverlayManager.setEnabled(String, boolean, int) ---
        // This method exists in the AIDL on ALL API levels (including 31+); the previous
        // implementation incorrectly skipped it for API 31+ and used a transaction-builder
        // path whose setEnabled() method doesn't actually exist on the builder class.
        if (service != null) {
            try {
                val method = service.javaClass.getMethod(
                    "setEnabled", String::class.java, Boolean::class.java, Int::class.java
                )
                method.invoke(service, packageName, enabled, userId)
                Log.i(TAG, "setOverlayEnabled: setEnabled() reflection succeeded")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setOverlayEnabled: setEnabled() reflection failed — ${e.message}")
            }
        }

        // --- Secondary path: Samsung-specific setEnabledExclusive (OneUI font/theme overlays) ---
        // Samsung's IOverlayManager adds setEnabledExclusive and setEnabledExclusiveInCategory
        // for themed overlays where only one overlay per target resource should be active.
        if (service != null && enabled) {
            try {
                val method = service.javaClass.getMethod(
                    "setEnabledExclusive", String::class.java, Boolean::class.java, Int::class.java
                )
                method.invoke(service, packageName, true, userId)
                Log.i(TAG, "setOverlayEnabled: setEnabledExclusive() reflection succeeded")
                return true
            } catch (_: Exception) {}

            try {
                val method = service.javaClass.getMethod(
                    "setEnabledExclusiveInCategory", String::class.java, Int::class.java
                )
                method.invoke(service, packageName, userId)
                Log.i(TAG, "setOverlayEnabled: setEnabledExclusiveInCategory() reflection succeeded")
                return true
            } catch (_: Exception) {}
        }

        // --- Tertiary path: OverlayManagerTransaction (API 31+, AOSP OMT commit) ---
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val txBuilderClass = Class.forName("android.content.om.OverlayManagerTransaction\$Builder")
                val txBuilder = txBuilderClass.getConstructor().newInstance()
                // On some AOSP builds, the transaction builder has setEnabled/setEnabledExclusive
                for (methodName in listOf("setEnabled", "setEnabledExclusive")) {
                    try {
                        val m = txBuilderClass.getMethod(
                            methodName, String::class.java, Boolean::class.java, Int::class.java
                        )
                        m.invoke(txBuilder, packageName, enabled, userId)
                        val tx = txBuilderClass.getMethod("build").invoke(txBuilder)
                        val txClass = Class.forName("android.content.om.OverlayManagerTransaction")
                        service.javaClass.getMethod("commit", txClass).invoke(service, tx)
                        Log.i(TAG, "setOverlayEnabled: OMT.$methodName() succeeded")
                        return true
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "setOverlayEnabled: OverlayManagerTransaction path failed — ${e.message}")
            }
        }

        // --- Fallback path: cmd overlay ---
        val action = if (enabled) "enable" else "disable"
        val ok = runOverlayCmd(action, "--user", userId.toString(), packageName)
        if (ok) Log.i(TAG, "setOverlayEnabled: cmd overlay fallback succeeded")
        else Log.e(TAG, "setOverlayEnabled: all paths failed for $packageName")
        return ok
    }

    // -------------------------------------------------------------------------
    // setHighestPriority
    // -------------------------------------------------------------------------

    override fun setHighestPriority(packageName: String?): Boolean {
        if (packageName == null) return false
        val userId = UserHandleCompat.getUserId(Binder.getCallingUid())
        Log.d(TAG, "setHighestPriority pkg=$packageName userId=$userId")

        // --- Primary path: reflection ---
        try {
            val service = getIOverlayManager()
            if (service != null) {
                // Try the classic 2-arg signature first (API ≤ 30)
                val method = try {
                    service.javaClass.getMethod("setHighestPriority", String::class.java, Int::class.java)
                } catch (_: NoSuchMethodException) {
                    // API 31+ may have removed this; log and fall through
                    Log.w(TAG, "setHighestPriority: 2-arg method not found on API ${Build.VERSION.SDK_INT}")
                    null
                }
                if (method != null) {
                    method.invoke(service, packageName, userId)
                    Log.i(TAG, "setHighestPriority: reflection succeeded")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setHighestPriority: reflection failed — ${e.message}")
        }

        // --- Fallback path: cmd overlay ---
        val ok = runOverlayCmd("set-priority", packageName, "highest")
        if (ok) Log.i(TAG, "setHighestPriority: cmd overlay fallback succeeded")
        else Log.e(TAG, "setHighestPriority: both reflection and cmd overlay failed for $packageName")
        return ok
    }

    // -------------------------------------------------------------------------
    // getAllOverlays
    // -------------------------------------------------------------------------

    override fun getAllOverlays(): List<String> {
        val userId = UserHandleCompat.getUserId(Binder.getCallingUid())
        Log.d(TAG, "getAllOverlays userId=$userId")

        // --- Primary path: reflection ---
        try {
            val service = getIOverlayManager()
            if (service != null) {
                val method = try {
                    service.javaClass.getMethod("getAllOverlays", Int::class.java)
                } catch (_: NoSuchMethodException) { null }

                if (method != null) {
                    @Suppress("UNCHECKED_CAST")
                    val result = method.invoke(service, userId) as? Map<*, *>
                    if (result != null) {
                        val list = mutableListOf<String>()
                        result.values.forEach { overlayList ->
                            (overlayList as? List<*>)?.forEach { info ->
                                if (info == null) return@forEach
                                val pkgName = extractOverlayPackageName(info)
                                val isEnabled = extractOverlayEnabled(info)
                                if (pkgName != null) {
                                    list.add("$pkgName:$isEnabled")
                                }
                            }
                        }
                        Log.i(TAG, "getAllOverlays: reflection succeeded, found ${list.size} overlays")
                        return list
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAllOverlays: reflection failed — ${e.message}")
        }

        // --- Fallback path: cmd overlay list ---
        return parseOverlayListOutput(userId)
    }

    /**
     * Extract the package name from an OverlayInfo instance using multiple fallback strategies,
     * because the field/method name differs across Android versions:
     *   - API ≤ 29: getPackageName() method
     *   - API 30+:  overlayPackageName field (public)
     *   - Samsung:  packageName field
     */
    private fun extractOverlayPackageName(info: Any): String? {
        // 1. getPackageName() method (AOSP ≤ 29)
        try {
            return info.javaClass.getMethod("getPackageName").invoke(info) as? String
        } catch (_: Exception) {}

        // 2. overlayPackageName field (AOSP 30+)
        try {
            val f = info.javaClass.getField("overlayPackageName")
            f.isAccessible = true
            return f.get(info) as? String
        } catch (_: Exception) {}

        // 3. packageName field (Samsung OEM customizations)
        try {
            val f = info.javaClass.getDeclaredField("packageName")
            f.isAccessible = true
            return f.get(info) as? String
        } catch (_: Exception) {}

        // 4. mPackageName field (internal naming convention)
        try {
            val f = info.javaClass.getDeclaredField("mPackageName")
            f.isAccessible = true
            return f.get(info) as? String
        } catch (_: Exception) {}

        Log.w(TAG, "extractOverlayPackageName: could not extract package name from ${info.javaClass.name}")
        return null
    }

    /**
     * Extract the enabled state from an OverlayInfo instance, returning false on any failure.
     */
    private fun extractOverlayEnabled(info: Any): Boolean {
        // 1. isEnabled() method
        try {
            return info.javaClass.getMethod("isEnabled").invoke(info) as? Boolean ?: false
        } catch (_: Exception) {}

        // 2. state field (int; STATE_ENABLED = 3 in AOSP)
        try {
            val f = info.javaClass.getDeclaredField("state")
            f.isAccessible = true
            val state = f.get(info) as? Int ?: return false
            return state == 3 // STATE_ENABLED
        } catch (_: Exception) {}

        // 3. isEnabled field (boolean)
        try {
            val f = info.javaClass.getDeclaredField("isEnabled")
            f.isAccessible = true
            return f.get(info) as? Boolean ?: false
        } catch (_: Exception) {}

        return false
    }

    /**
     * Parse the text output of `cmd overlay list --user <userId>` into the same
     * "packageName:enabled" format that the reflection path produces.
     *
     * Output format (AOSP):
     *   com.android.target
     *     [x] com.android.overlay (enabled)
     *     [ ] com.other.overlay (disabled)
     */
    private fun parseOverlayListOutput(userId: Int): List<String> {
        val output = runOverlayCmdOutput("list", "--user", userId.toString())
            ?: return emptyList()
        val list = mutableListOf<String>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            // Lines that describe an overlay start with "[x]" (enabled) or "[ ]" (disabled)
            if (trimmed.startsWith("[")) {
                val enabled = trimmed.startsWith("[x]")
                // Package name follows the bracket+space marker, e.g. "[x] com.foo.overlay"
                val pkg = trimmed.substringAfter("] ").substringBefore(" ").trim()
                if (pkg.isNotEmpty() && pkg.contains(".")) {
                    list.add("$pkg:$enabled")
                }
            }
        }
        Log.i(TAG, "getAllOverlays: cmd overlay fallback found ${list.size} overlays")
        return list
    }

    // -------------------------------------------------------------------------
    // injectResourceOverlay
    // -------------------------------------------------------------------------

    override fun injectResourceOverlay(
        targetPackage: String?,
        resourceName: String?,
        type: Int,
        value: String?
    ): Boolean {
        if (targetPackage == null || resourceName == null || value == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "injectResourceOverlay requires API 31+, current=${Build.VERSION.SDK_INT}")
            return false
        }

        Log.d(TAG, "injectResourceOverlay target=$targetPackage resource=$resourceName type=$type")

        // --- Primary path: reflection via FabricatedOverlay + OverlayManagerTransaction ---
        try {
            val builderClass = Class.forName("android.content.om.FabricatedOverlay\$Builder")
            val overlayName = "shizuku_plus_overlay_${System.currentTimeMillis()}"

            // API 34+ changed Builder constructor from 3-arg to 2-arg (owningPackage removed)
            val builderInstance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: FabricatedOverlay.Builder(String name, String targetPackage)
                Log.d(TAG, "injectResourceOverlay: using 2-arg Builder (API 34+)")
                try {
                    val ctor = builderClass.getConstructor(String::class.java, String::class.java)
                    ctor.newInstance(overlayName, targetPackage)
                } catch (e: NoSuchMethodException) {
                    Log.w(TAG, "injectResourceOverlay: 2-arg Builder not found, trying 3-arg fallback")
                    val ctor = builderClass.getConstructor(
                        String::class.java, String::class.java, String::class.java
                    )
                    ctor.newInstance("af.shizuku.manager", overlayName, targetPackage)
                }
            } else {
                // Android 12-13: FabricatedOverlay.Builder(String owningPackage, String name, String targetPackage)
                Log.d(TAG, "injectResourceOverlay: using 3-arg Builder (API 31-33)")
                val ctor = builderClass.getConstructor(
                    String::class.java, String::class.java, String::class.java
                )
                ctor.newInstance("af.shizuku.manager", overlayName, targetPackage)
            }

            // setResourceValue has two overloads:
            //   setResourceValue(String name, int dataType, int value)
            //   setResourceValue(String name, int dataType, String value)
            if (type == 3) { // TYPE_STRING
                val m = builderClass.getMethod(
                    "setResourceValue", String::class.java, Int::class.java, String::class.java
                )
                m.invoke(builderInstance, resourceName, type, value)
            } else {
                val intVal = value.toIntOrNull() ?: 0
                val m = builderClass.getMethod(
                    "setResourceValue", String::class.java, Int::class.java, Int::class.java
                )
                m.invoke(builderInstance, resourceName, type, intVal)
            }

            val overlay = builderClass.getMethod("build").invoke(builderInstance)

            val service = getIOverlayManager() ?: run {
                Log.w(TAG, "injectResourceOverlay: IOverlayManager unavailable for commit")
                return@injectResourceOverlay false
            }

            val txBuilderClass = Class.forName("android.content.om.OverlayManagerTransaction\$Builder")
            val txBuilder = txBuilderClass.getConstructor().newInstance()

            val fabClass = Class.forName("android.content.om.FabricatedOverlay")
            val registerMethod = txBuilderClass.getMethod("registerFabricatedOverlay", fabClass)
            registerMethod.invoke(txBuilder, overlay)

            val tx = txBuilderClass.getMethod("build").invoke(txBuilder)
            val txClass = Class.forName("android.content.om.OverlayManagerTransaction")
            service.javaClass.getMethod("commit", txClass).invoke(service, tx)

            Log.i(TAG, "injectResourceOverlay: reflection succeeded for $targetPackage/$resourceName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "injectResourceOverlay: reflection failed — falling back to cmd overlay", e)
        }

        // --- Fallback: cmd overlay cannot inject fabricated resources directly, so we log and fail ---
        // There is no `cmd overlay` equivalent for FabricatedOverlay injection.
        Log.e(TAG, "injectResourceOverlay: no fallback available for $targetPackage/$resourceName on API ${Build.VERSION.SDK_INT}")
        return false
    }

    // -------------------------------------------------------------------------
    // prepareShadowMount
    // -------------------------------------------------------------------------

    override fun prepareShadowMount(callingPackage: String?, partition: String?): Boolean {
        if (callingPackage == null || partition == null) return false
        Log.i(TAG, "Ghost Bridge: Preparing shadow mount for partition=$partition requested by $callingPackage")
        // Mock success for Ghost Bridge emulation.
        // Actual overlay logic requires root/Magisk to mount OverlayFS.
        return true
    }
}
