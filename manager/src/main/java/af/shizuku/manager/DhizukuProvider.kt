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
            } catch (_: Exception) {
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
                // Security gate first — only authorized callers may relay transactions.
                if (!isCallerAuthorized()) return false

                // Dhizuku clients may write any of several interface descriptor variants
                // ("com.rosan.dhizuku.server", "com.rosan.dhizuku.aidl.IDhizuku", etc.).
                // Samsung Knox on Android 16 intercepts enforceInterface() and may throw
                // *before* consuming the descriptor string, leaving the parcel position at 0
                // instead of advancing past the descriptor. If we then called readStrongBinder()
                // we'd be reading the descriptor bytes as a binder — corrupt relay.
                // Skipping with readString() is safe here: the authorization check above already
                // guards the relay; the descriptor is Dhizuku-internal bookkeeping, not a
                // security boundary.
                data.setDataPosition(0)
                data.readString() // consume/skip the interface descriptor token
                val targetBinder = data.readStrongBinder() ?: return false
                val targetCode = data.readInt()
                val targetFlags = data.readInt()
                return targetBinder.transact(targetCode, data, reply, targetFlags)
            }

            if (code >= FIRST_CALL_TRANSACTION + 0 && code <= FIRST_CALL_TRANSACTION + 3) {
                var isV2 = true
                try {
                    data.enforceInterface("com.rosan.dhizuku.aidl.IDhizuku")
                } catch (_: SecurityException) {
                    data.setDataPosition(0)
                    try {
                        data.enforceInterface("com.rosan.dhizuku.IDhizuku")
                        isV2 = false
                    } catch (_: SecurityException) {}
                }
                when (code) {
                    FIRST_CALL_TRANSACTION + 0 -> { // getVersionCode (v2) / getVersion (v1)
                        reply?.writeNoException()
                        reply?.writeInt(if (isV2) 5 else 1)
                        return true
                    }
                    FIRST_CALL_TRANSACTION + 1 -> { // getVersionName (v2) / getBinder (v1)
                        reply?.writeNoException()
                        if (isV2) {
                            reply?.writeString("5.0")
                        } else {
                            val binder = if (isCallerAuthorized() && ShizukuStateMachine.isRunning()) {
                                try {
                                    ServiceManager.getService(Context.DEVICE_POLICY_SERVICE)
                                } catch (_: Exception) {
                                    null
                                }
                            } else null
                            reply?.writeStrongBinder(binder)
                        }
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
