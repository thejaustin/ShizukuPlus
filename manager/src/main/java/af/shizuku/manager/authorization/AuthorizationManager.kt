package af.shizuku.manager.authorization

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.Manifest
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuSystemApis
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Parcel
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants
import java.util.*

object AuthorizationManager {
    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            val binder =
                Shizuku.getBinder()
                    ?: throw IllegalStateException("Shizuku binder not available")
            try {
                binder.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list
                ?: emptyList()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun isShizukuClientPackage(pi: PackageInfo): Boolean {
        if (BuildConfig.APPLICATION_ID == pi.packageName) return false
        val perms = pi.requestedPermissions
        if (perms?.contains(Manifest.permission.API_V23) == true ||
            perms?.contains(ServerConstants.PERMISSION_LEGACY) == true ||
            perms?.contains(ServerConstants.PERMISSION_ORIGINAL) == true
        ) {
            return true
        }
        val meta = pi.applicationInfo?.metaData
        return meta?.getBoolean("af.shizuku.client.V3_SUPPORT", false) == true ||
            meta?.getBoolean("moe.shizuku.client.V3_SUPPORT", false) == true
    }

    private fun getPackagesLegacy(): List<PackageInfo> {
        val result = mutableListOf<PackageInfo>()
        for (user in ShizukuSystemApis.getUsers(useCache = false)) {
            try {
                val all = ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id)
                for (pi in all) {
                    if (isShizukuClientPackage(pi)) {
                        result.add(pi)
                    }
                }
            } catch (e: Throwable) {
                LOGGER.w(e, "getInstalledPackages")
            }
        }
        return result
    }

    fun getPackages(): List<PackageInfo> {
        val packages: MutableList<PackageInfo> = ArrayList()
        try {
            if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
                packages.addAll(getPackagesLegacy())
            } else {
                packages.addAll(getApplications(-1))
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "getApplications failed, falling back to installed packages scan")
            try {
                packages.addAll(getPackagesLegacy())
            } catch (fallbackError: Throwable) {
                LOGGER.w(fallbackError, "Fallback installed packages scan also failed")
            }
        }
        return packages
    }

    fun isPlusApiSupported(pi: PackageInfo): Boolean = pi.applicationInfo?.metaData?.getBoolean("af.shizuku.plus.API") == true

    fun granted(
        packageName: String,
        uid: Int,
    ): Boolean {
        return try {
            // Retry pingBinder up to 3x with 200 ms back-off: a momentary binder drop
            // (common right after a consent-action broadcast wakes the process) used to
            // cause granted() to return false and re-prompt the user on every new rish
            // invocation even though AuthorizationManager.grant() had already stored the
            // grant for that UID (#398).
            var binderAlive = Shizuku.pingBinder()
            if (!binderAlive) {
                repeat(3) {
                    if (!binderAlive) {
                        Thread.sleep(200)
                        binderAlive = Shizuku.pingBinder()
                    }
                }
            }
            if (!binderAlive) return false
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
            } else {
                (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** @throws SecurityException when the service isn't allowed to change it (limited ADB). */
    fun grant(
        packageName: String,
        uid: Int,
    ) {
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            }
        } catch (e: SecurityException) {
            // The caller tells the user (e.g. the "ADB is limited" dialog); swallowing it here
            // made a failed change look like it worked.
            throw e
        } catch (t: Throwable) {
            timber.log.Timber.w(t, "%s failed for %s (incompatible server?)", "grant", packageName)
        }
    }

    /** @throws SecurityException when the service isn't allowed to change it (limited ADB). */
    fun revoke(
        packageName: String,
        uid: Int,
    ) {
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            }
        } catch (e: SecurityException) {
            // The caller tells the user (e.g. the "ADB is limited" dialog); swallowing it here
            // made a failed change look like it worked.
            throw e
        } catch (t: Throwable) {
            timber.log.Timber.w(t, "%s failed for %s (incompatible server?)", "revoke", packageName)
        }
        clearDhizukuScopes(packageName)
    }

    // Scopes a Dhizuku client set through us outlive the Shizuku grant unless we drop them here,
    // which is what Dhizuku itself does when an app loses access. No-op unless we're device owner.
    private fun clearDhizukuScopes(packageName: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        try {
            val context = af.shizuku.manager.ShizukuApplication.appContext
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName) && !dpm.isProfileOwnerApp(context.packageName)) return
            val admin = android.content.ComponentName(context, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
            if (dpm.getDelegatedScopes(admin, packageName).isNotEmpty()) {
                dpm.setDelegatedScopes(admin, packageName, emptyList())
            }
        } catch (t: Throwable) {
            timber.log.Timber.w(t, "clearing Dhizuku scopes failed for %s", packageName)
        }
    }
}
