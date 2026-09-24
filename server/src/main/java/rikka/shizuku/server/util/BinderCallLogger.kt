package rikka.shizuku.server.util

import android.util.Log

/**
 * Resolves Binder transaction codes to human-readable method names for log output.
 *
 * AIDL-generated Stub classes declare public static int TRANSACTION_methodName fields whose
 * values are the transaction codes. We look those up once per Stub class and cache the result
 * so per-call lookups are O(1) after the first call on each interface.
 */
object BinderCallLogger {

    private const val TAG = "BinderCall"

    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, Map<Int, String>>()

    private fun methodNameFor(stubClass: Class<*>, code: Int): String {
        val map = cache.getOrPut(stubClass) {
            stubClass.fields
                .filter { it.name.startsWith("TRANSACTION_") }
                .associate { field ->
                    runCatching { (field.get(null) as? Int) ?: -1 }.getOrDefault(-1) to
                        field.name.removePrefix("TRANSACTION_")
                }
                .filterKeys { it >= 0 }
        }
        return map[code] ?: "unknown($code)"
    }

    /**
     * Log a Binder call if the [enabled] flag is true.
     *
     * @param enabled  Whether binder_logging feature is currently on.
     * @param stubClass The generated `I*.Stub` class — used to resolve [code] to a name.
     * @param code     The raw transaction code from [android.os.IBinder.onTransact].
     * @param callingUid The caller's UID, typically from [android.os.Binder.getCallingUid].
     */
    @JvmStatic
    fun log(enabled: Boolean, stubClass: Class<*>, code: Int, callingUid: Int) {
        if (!enabled) return
        val method = methodNameFor(stubClass, code)
        val iface = stubClass.declaringClass?.simpleName ?: stubClass.simpleName
            .removeSuffix("\$Stub").removeSuffix("Stub")
        Log.i(TAG, "uid=$callingUid → $iface.$method (code=$code)")
    }

    /**
     * Convenience for Java callers that pass a descriptor string instead of a Stub class.
     * Falls back to logging just the code.
     */
    @JvmStatic
    fun log(enabled: Boolean, descriptor: String, code: Int, callingUid: Int) {
        if (!enabled) return
        val iface = descriptor.substringAfterLast(".").removeSuffix("\$Stub")
        Log.i(TAG, "uid=$callingUid → $iface (code=$code)")
    }
}
