package rikka.shizuku.server

import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import moe.shizuku.server.INetworkGovernorPlus
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.api.IContentProviderUtils
import rikka.shizuku.server.util.UserHandleCompat

class NetworkGovernorPlusImpl : INetworkGovernorPlus.Stub() {
    
    override fun setPrivateDns(mode: String?, hostname: String?): Boolean {
        // mode: "off", "opportunistic", "hostname"
        val userId = UserHandleCompat.getUserId(Process.myUid())
        try {
            val provider = ActivityManagerApis.getContentProviderExternal("settings", userId, null, "settings")
            if (provider != null) {
                if (mode != null) {
                    val extras = Bundle().apply { putString("value", mode) }
                    IContentProviderUtils.callCompat(provider, null, "settings", "PUT_global", "private_dns_mode", extras)
                }
                if (hostname != null) {
                    val extras = Bundle().apply { putString("value", hostname) }
                    IContentProviderUtils.callCompat(provider, null, "settings", "PUT_global", "private_dns_specifier", extras)
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    override fun restrictAppNetwork(packageName: String?, restricted: Boolean): Boolean {
        if (packageName == null) return false
        val binder = ServiceManager.getService("netpolicy") ?: return false
        return try {
            val stub = Class.forName("android.net.INetworkPolicyManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder)

            val userId = UserHandleCompat.getUserId(Process.myUid())
            val ai = PackageManagerApis.getApplicationInfoNoThrow(packageName, 0, userId)
                ?: return false
            val uid = ai.uid

            // Policy: 1 = REJECT_METERED_BACKGROUND, 0 = NONE
            val policy = if (restricted) 1 else 0
            val method = service!!.javaClass.getMethod("setUidPolicy", Int::class.java, Int::class.java)
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
            val service = asInterface.invoke(null, binder)

            val userId = UserHandleCompat.getUserId(Process.myUid())
            val ai = PackageManagerApis.getApplicationInfoNoThrow(packageName, 0, userId)
                ?: return false
            val uid = ai.uid

            val method = service!!.javaClass.getMethod("getUidPolicy", Int::class.java)
            val policy = method.invoke(service, uid) as Int
            policy != 0
        } catch (e: Exception) {
            false
        }
    }
}
