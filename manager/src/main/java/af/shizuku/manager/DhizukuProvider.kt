package af.shizuku.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import com.rosan.dhizuku.IDhizuku
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

class DhizukuProvider : ContentProvider() {

    private inner class DhizukuV1Binder : IDhizuku.Stub() {
        override fun getVersion(): Int = 1

        override fun getBinder(): IBinder? {
            if (!ShizukuSettings.isDhizukuModeEnabled()) return null
            if (!ShizukuStateMachine.isRunning()) return null

            val callingUid = Binder.getCallingUid()
            val myUid = android.os.Process.myUid()
            if (callingUid != myUid) {
                val pkgName = context?.packageManager?.getPackagesForUid(callingUid)?.firstOrNull() ?: ""
                if (!af.shizuku.manager.authorization.AuthorizationManager.granted(pkgName, callingUid)) {
                    return null
                }
            }

            return try {
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE)
            } catch (e: Exception) {
                null
            }
        }

        override fun isPermissionGranted(): Boolean {
            if (!ShizukuSettings.isDhizukuModeEnabled()) return false
            val callingUid = Binder.getCallingUid()
            if (callingUid == android.os.Process.myUid()) return true
            val pkgName = context?.packageManager?.getPackagesForUid(callingUid)?.firstOrNull() ?: ""
            return af.shizuku.manager.authorization.AuthorizationManager.granted(pkgName, callingUid)
        }

        override fun transact(code: Int, data: Bundle?): Bundle {
            return Bundle()
        }
    }

    private inner class DhizukuV2Binder : Binder() {
        override fun getInterfaceDescriptor(): String = "com.rosan.dhizuku.aidl.IDhizuku"

        // Mirrors DhizukuV1Binder's authorization gate above - the caller must either be
        // this app itself or a package the user has explicitly granted via AuthorizationManager.
        private fun isCallerAuthorized(): Boolean {
            val callingUid = Binder.getCallingUid()
            if (callingUid == android.os.Process.myUid()) return true
            val pkgName = context?.packageManager?.getPackagesForUid(callingUid)?.firstOrNull() ?: ""
            return af.shizuku.manager.authorization.AuthorizationManager.granted(pkgName, callingUid)
        }

        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (!ShizukuSettings.isDhizukuModeEnabled()) return false

            if (code == FIRST_CALL_TRANSACTION + 10) { // TRANSACT_CODE_REMOTE_BINDER
                data.enforceInterface("com.rosan.dhizuku.server")
                val targetBinder = data.readStrongBinder()
                val targetCode = data.readInt()
                val targetFlags = data.readInt()
                return targetBinder.transact(targetCode, data, reply, targetFlags)
            }

            if (code >= FIRST_CALL_TRANSACTION + 0 && code <= FIRST_CALL_TRANSACTION + 3) {
                data.enforceInterface("com.rosan.dhizuku.aidl.IDhizuku")
                when (code) {
                    FIRST_CALL_TRANSACTION + 0 -> { // getVersion
                        reply?.writeNoException()
                        reply?.writeInt(5) // V5
                        return true
                    }
                    FIRST_CALL_TRANSACTION + 1 -> { // getBinder
                        reply?.writeNoException()
                        val binder = if (isCallerAuthorized() && ShizukuStateMachine.isRunning()) {
                            try {
                                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE)
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                        reply?.writeStrongBinder(binder)
                        return true
                    }
                    FIRST_CALL_TRANSACTION + 2 -> { // isPermissionGranted
                        reply?.writeNoException()
                        reply?.writeInt(if (isCallerAuthorized()) 1 else 0)
                        return true
                    }
                    FIRST_CALL_TRANSACTION + 3 -> { // transact
                        reply?.writeNoException()
                        reply?.writeBundle(Bundle())
                        return true
                    }
                }
            }

            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if ("getBinder" == method) {
            val bundle = Bundle()
            bundle.putBinder("binder", DhizukuV1Binder())
            return bundle
        }
        if ("client" == method) {
            val bundle = Bundle()
            bundle.putBinder("dhizuku_binder", DhizukuV2Binder())
            return bundle
        }
        return null
    }
}
