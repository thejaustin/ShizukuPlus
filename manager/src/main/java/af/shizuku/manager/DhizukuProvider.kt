package af.shizuku.manager

import af.shizuku.manager.utils.ShizukuStateMachine
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
                if (!af.shizuku.manager.authorization.AuthorizationManager
                        .granted(pkgName, callingUid)
                ) {
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
            return af.shizuku.manager.authorization.AuthorizationManager
                .granted(pkgName, callingUid)
        }

        override fun transact(
            code: Int,
            data: Bundle?,
        ): Bundle {
            val reply = Bundle()
            val adminComponent =
                android.content.ComponentName(
                    context?.packageName ?: "af.shizuku.plus",
                    af.shizuku.manager.admin.DhizukuAdminReceiver::class.java.name,
                )
            reply.putParcelable("component", adminComponent)
            reply.putParcelable("admin", adminComponent)
            reply.putParcelable("owner", adminComponent)
            return reply
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
            return af.shizuku.manager.authorization.AuthorizationManager
                .granted(pkgName, callingUid)
        }

        private fun enforceAnyInterface(
            data: android.os.Parcel,
            vararg descriptors: String,
        ): Boolean {
            for (descriptor in descriptors) {
                data.setDataPosition(0)
                try {
                    data.enforceInterface(descriptor)
                    return true
                } catch (_: SecurityException) {
                }
            }
            return false
        }

        override fun onTransact(
            code: Int,
            data: android.os.Parcel,
            reply: android.os.Parcel?,
            flags: Int,
        ): Boolean {
            if (!ShizukuSettings.isDhizukuModeEnabled()) return false

            if (code == FIRST_CALL_TRANSACTION + 10) { // TRANSACT_CODE_REMOTE_BINDER
                // Security gate first — only authorized callers may relay transactions.
                if (!isCallerAuthorized()) return false

                // Dhizuku.remoteTransact() writes the token with writeInterfaceToken(), which puts
                // header ints before the "com.rosan.dhizuku.server" string, so it has to be read
                // back with enforceInterface(). A plain readString() skip (84edaf5f) landed on the
                // header instead, so readStrongBinder() got nothing and every relayed call failed
                // (MDPC/OwnDroid route all their DevicePolicyManager calls through here, #670).
                if (!enforceAnyInterface(data, "com.rosan.dhizuku.server", "com.rosan.dhizuku.aidl.IDhizuku")) {
                    return false
                }
                val targetBinder = data.readStrongBinder() ?: return false
                val targetCode = data.readInt()
                val targetFlags = data.readInt()
                val targetData = android.os.Parcel.obtain()
                return try {
                    targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
                    val identity = Binder.clearCallingIdentity()
                    try {
                        targetBinder.transact(targetCode, targetData, reply, targetFlags)
                    } finally {
                        Binder.restoreCallingIdentity(identity)
                    }
                } finally {
                    targetData.recycle()
                }
            }

            if (code >= FIRST_CALL_TRANSACTION + 0 && code <= FIRST_CALL_TRANSACTION + 4) {
                var isV2 = true
                try {
                    data.enforceInterface("com.rosan.dhizuku.aidl.IDhizuku")
                } catch (_: SecurityException) {
                    data.setDataPosition(0)
                    try {
                        data.enforceInterface("com.rosan.dhizuku.IDhizuku")
                        isV2 = false
                    } catch (_: SecurityException) {
                    }
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
                            val binder =
                                if (isCallerAuthorized() && ShizukuStateMachine.isRunning()) {
                                    try {
                                        ServiceManager.getService(Context.DEVICE_POLICY_SERVICE)
                                    } catch (_: Exception) {
                                        null
                                    }
                                } else {
                                    null
                                }
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
                        val adminComponent =
                            android.content.ComponentName(
                                context?.packageName ?: "af.shizuku.plus",
                                af.shizuku.manager.admin.DhizukuAdminReceiver::class.java.name,
                            )
                        val bundle = Bundle()
                        bundle.putParcelable("component", adminComponent)
                        bundle.putParcelable("admin", adminComponent)
                        bundle.putParcelable("owner", adminComponent)
                        reply?.writeBundle(bundle)
                        return true
                    }
                    FIRST_CALL_TRANSACTION + 4 -> { // getOwnerComponent
                        reply?.writeNoException()
                        val adminComponent =
                            android.content.ComponentName(
                                context?.packageName ?: "af.shizuku.plus",
                                af.shizuku.manager.admin.DhizukuAdminReceiver::class.java.name,
                            )
                        if (reply != null) {
                            reply.writeInt(1) // non-null Parcelable
                            adminComponent.writeToParcel(reply, 0)
                        }
                        return true
                    }
                }
            }

            // getDelegatedScopes (15) / setDelegatedScopes (16) from the v2 IDhizuku AIDL. Amarok
            // calls both right after it gets permission (#670). Like Dhizuku's own server, the
            // calls run as us (the device owner); unlike it, a client may only touch its own
            // package's scopes.
            if (code == FIRST_CALL_TRANSACTION + 15 || code == FIRST_CALL_TRANSACTION + 16) {
                try {
                    data.enforceInterface("com.rosan.dhizuku.aidl.IDhizuku")
                } catch (_: SecurityException) {
                    return false
                }
                val packageName = data.readString()
                val callingUid = Binder.getCallingUid()
                val callerPackages = context?.packageManager?.getPackagesForUid(callingUid).orEmpty()
                if (!isCallerAuthorized() ||
                    packageName == null ||
                    (callingUid != android.os.Process.myUid() && packageName !in callerPackages)
                ) {
                    reply?.writeException(SecurityException("Not authorized for Dhizuku scopes of $packageName"))
                    return true
                }
                val dpm = context?.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                val admin =
                    android.content.ComponentName(
                        context?.packageName ?: "af.shizuku.plus",
                        af.shizuku.manager.admin.DhizukuAdminReceiver::class.java.name,
                    )
                try {
                    if (dpm == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                        throw UnsupportedOperationException("Delegated scopes need Android 8.0+")
                    }
                    if (code == FIRST_CALL_TRANSACTION + 15) {
                        val scopes = dpm.getDelegatedScopes(admin, packageName)
                        reply?.writeNoException()
                        reply?.writeStringArray(scopes.toTypedArray())
                    } else {
                        val scopes = data.createStringArray().orEmpty().toList()
                        dpm.setDelegatedScopes(admin, packageName, scopes)
                        reply?.writeNoException()
                    }
                } catch (e: Exception) {
                    reply?.writeException(e as? SecurityException ?: IllegalStateException(e.message))
                }
                return true
            }

            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? = call(method, arg, extras)

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val bundle = Bundle()
        val adminComponent =
            android.content.ComponentName(
                context?.packageName ?: "af.shizuku.plus",
                af.shizuku.manager.admin.DhizukuAdminReceiver::class.java.name,
            )
        bundle.putBinder("binder", DhizukuV1Binder())
        bundle.putBinder("dhizuku_binder", DhizukuV2Binder())
        bundle.putParcelable("component", adminComponent)
        bundle.putParcelable("admin", adminComponent)
        bundle.putParcelable("owner", adminComponent)
        bundle.putParcelable("dhizuku_component", adminComponent)
        return bundle
    }
}
