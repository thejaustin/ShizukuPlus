package rikka.shizuku.server

import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import moe.shizuku.server.IOverlayManagerPlus
import rikka.shizuku.server.util.UserHandleCompat

class OverlayManagerPlusImpl : IOverlayManagerPlus.Stub() {

    private fun getService(): IBinder? = ServiceManager.getService("overlay")

    override fun setOverlayEnabled(packageName: String?, enabled: Boolean): Boolean {
        if (packageName == null) return false
        val binder = getService() ?: return false
        return try {
            val stub = Class.forName("android.content.om.IOverlayManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder)

            val method = service!!.javaClass.getMethod("setEnabled", String::class.java, Boolean::class.java, Int::class.java)
            method.invoke(service, packageName, enabled, UserHandleCompat.getUserId(Process.myUid()))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setHighestPriority(packageName: String?): Boolean {
        if (packageName == null) return false
        val binder = getService() ?: return false
        return try {
            val stub = Class.forName("android.content.om.IOverlayManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder)

            val method = service!!.javaClass.getMethod("setHighestPriority", String::class.java, Int::class.java)
            method.invoke(service, packageName, UserHandleCompat.getUserId(Process.myUid()))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun getAllOverlays(): List<String> {
        val binder = getService() ?: return emptyList()
        return try {
            val stub = Class.forName("android.content.om.IOverlayManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, binder)

            val method = service!!.javaClass.getMethod("getAllOverlays", Int::class.java)
            val result = method.invoke(service, UserHandleCompat.getUserId(Process.myUid())) as Map<*, *>

            val list = mutableListOf<String>()
            result.values.forEach { overlayList ->
                (overlayList as List<*>).forEach { info ->
                    val pkgName = info?.javaClass?.getMethod("getPackageName")?.invoke(info) as String
                    val isEnabled = info.javaClass.getMethod("isEnabled").invoke(info) as Boolean
                    list.add("$pkgName:$isEnabled")
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
