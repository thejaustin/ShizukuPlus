package rikka.shizuku.server

import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.INetworkGovernorPlus
import rikka.hidden.compat.ActivityManagerApis
import af.shizuku.common.compat.Android17Compat
import rikka.shizuku.server.api.IContentProviderUtils
import rikka.shizuku.server.util.InputValidationUtils
import af.shizuku.common.util.UserHandleCompat

class NetworkGovernorPlusImpl : INetworkGovernorPlus.Stub() {

    companion object {
        private const val TAG = "NetworkGovernorPlus"
    }

    override fun setPrivateDns(mode: String?, hostname: String?): Boolean {
        // mode: "off", "opportunistic", "hostname"
        
        // Validate DNS mode parameter
        if (mode != null && !InputValidationUtils.isValidDnsMode(mode)) {
            throw IllegalArgumentException(
                "Invalid DNS mode: $mode. Valid modes are: off, opportunistic, hostname"
            )
        }
        
        // Validate and sanitize DNS hostname parameter
        val sanitizedHostname = if (hostname != null) {
            InputValidationUtils.validateAndSanitizeHostname(hostname).also { sanitized ->
                if (sanitized == null) {
                    throw IllegalArgumentException(
                        "Invalid DNS hostname: $hostname. Must be a valid hostname format"
                    )
                }
            }
        } else null
        
        val userId = UserHandleCompat.getUserId(Process.myUid())
        try {
            val provider = ActivityManagerApis.getContentProviderExternal("settings", userId, null, "com.android.shell")
            if (provider != null) {
                if (mode != null) {
                    val extras = Bundle().apply { putString("value", mode) }
                    IContentProviderUtils.callCompat(provider, null, "settings", "PUT_global", "private_dns_mode", extras)
                }
                if (sanitizedHostname != null) {
                    val extras = Bundle().apply { putString("value", sanitizedHostname) }
                    IContentProviderUtils.callCompat(provider, null, "settings", "PUT_global", "private_dns_specifier", extras)
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "setPrivateDns failed", e)
        }
        return false
    }

    override fun restrictAppNetwork(packageName: String?, restricted: Boolean): Boolean {
        if (packageName == null) return false
        val binder = ServiceManager.getService("netpolicy") ?: return false
        return try {
            val stub = Class.forName("android.net.INetworkPolicyManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder) ?: return false

            val userId = UserHandleCompat.getUserId(Process.myUid())
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, userId)
                ?: return false
            val uid = ai.uid

            // Policy 1: REJECT_METERED_BACKGROUND
            // Policy 4: REJECT_ALL (Android 10+)
            val policy = if (restricted) {
                if (android.os.Build.VERSION.SDK_INT >= 29) 4 else 1
            } else 0
            
            val method = service.javaClass.getMethod("setUidPolicy", Int::class.java, Int::class.java)
            method.invoke(service, uid, policy)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun isAppNetworkRestricted(packageName: String?): Boolean {
        if (packageName == null) return false
        val binder = ServiceManager.getService("netpolicy") ?: return false
        return try {
            val stub = Class.forName("android.net.INetworkPolicyManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder) ?: return false

            val userId = UserHandleCompat.getUserId(Process.myUid())
            val ai = Android17Compat.getApplicationInfo(packageName, 0L, userId)
                ?: return false
            val uid = ai.uid

            val method = service.javaClass.getMethod("getUidPolicy", Int::class.java)
            val policy = method.invoke(service, uid) as Int
            policy != 0
        } catch (e: Exception) {
            false
        }
    }
}
