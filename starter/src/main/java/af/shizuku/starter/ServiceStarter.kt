package af.shizuku.starter

import android.content.IContentProvider
import android.os.*
import android.util.Log
import af.shizuku.api.BinderContainer
import af.shizuku.starter.util.IContentProviderCompat
import kotlinx.coroutines.*
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.UserService
import java.util.*

object ServiceStarter {

    private const val TAG = "ShizukuServiceStarter"
    private const val EXTRA_BINDER = "af.shizuku.plus.api.intent.extra.BINDER"

    // Fallback only: used if a caller built the command line without --manager= (e.g. an
    // out-of-date server binary). The real value is always passed explicitly below because this
    // process is spawned fresh via app_process and can't see the server's runtime-resolved
    // ServerConstants.MANAGER_APPLICATION_ID (which flips to the Drop-In id when that's the
    // flavor actually installed) - hardcoding the Plus id here made every UserService start fail
    // with "provider is null" on Drop-In-only installs (#371).
    private const val DEFAULT_MANAGER_PACKAGE_NAME = "af.shizuku.plus.api"

    val DEBUG_ARGS: String by lazy {
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= 30 -> "-Xcompiler-option --debuggable -XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y"
            sdk >= 28 -> "-Xcompiler-option --debuggable -XjdwpProvider:internal -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"
            else -> "-Xcompiler-option --debuggable -agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"
        }
    }

    private const val USER_SERVICE_CMD_FORMAT = "(CLASSPATH='%s' %s%s /system/bin " +
            "--nice-name='%s' af.shizuku.starter.ServiceStarter " +
            "--token='%s' --package='%s' --class='%s' --uid=%d --manager='%s'%s </dev/null >/dev/null 2>&1)&"

    @JvmStatic
    @Volatile
    private var shizukuBinder: IBinder? = null

    @JvmStatic
    fun commandForUserService(
        appProcess: String,
        managerApkPath: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        debug: Boolean,
        managerPackageName: String
    ): String {
        // Sanitize single quotes to prevent shell injection escapes
        val safeManagerApkPath = managerApkPath.replace("'", "'\\''")
        val safeToken = token.replace("'", "'\\''")
        val safePackageName = packageName.replace("'", "'\\''")
        val safeClassname = classname.replace("'", "'\\''")
        val safeProcessNameSuffix = processNameSuffix.replace("'", "'\\''")
        val safeManagerPackageName = managerPackageName.replace("'", "'\\''")

        val processName = "$safePackageName:$safeProcessNameSuffix"
        return String.format(
            Locale.ENGLISH, USER_SERVICE_CMD_FORMAT,
            safeManagerApkPath, appProcess, if (debug) " $DEBUG_ARGS" else "",
            processName,
            safeToken, safePackageName, safeClassname, callingUid, safeManagerPackageName,
            if (debug) " --debug-name=$processName" else ""
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        UserService.setTag(TAG)
        val result = UserService.create(args)

        if (result == null) {
            System.exit(1)
            return
        }

        val service = result.first
        val token = result.second
        val managerPackageName = args.firstOrNull { it.startsWith("--manager=") }
            ?.substring("--manager=".length)
            ?: DEFAULT_MANAGER_PACKAGE_NAME

        runBlocking {
            if (!sendBinder(service, token, managerPackageName)) {
                System.exit(1)
            }
        }

        Looper.loop()
        System.exit(0)

        Log.i(TAG, "service exited")
    }

    private suspend fun sendBinder(
        binder: IBinder,
        token: String,
        packageName: String,
        retry: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val name = "$packageName.shizuku"
        val userId = 0
        var provider: IContentProvider? = null

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name)
            if (provider == null) {
                Log.e(TAG, "provider is null $name $userId")

                // The manager's process may have just been killed (OEM background-kill) and be
                // mid-restart, in which case AMS can transiently return null instead of waiting
                // and starting it. Give it one retry after a short delay before giving up, same
                // as the dead-binder path below already does.
                if (retry) {
                    delay(1000)
                    return@withContext sendBinder(binder, token, packageName, false)
                }
                return@withContext false
            }
            if (!provider.asBinder().pingBinder()) {
                Log.e(TAG, "provider is dead $name $userId")

                if (retry) {
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId)
                    Log.e(TAG, "kill $packageName in user $userId and try again")
                    delay(1000)
                    return@withContext sendBinder(binder, token, packageName, false)
                }
                return@withContext false
            }

            if (!retry) {
                Log.e(TAG, "retry works")
            }

            val extra = Bundle().apply {
                putParcelable(EXTRA_BINDER, BinderContainer(binder))
                putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)
            }

            val reply = IContentProviderCompat.call(provider, null, null, name, "sendUserService", null, extra)

            if (reply != null) {
                reply.classLoader = BinderContainer::class.java.classLoader

                Log.i(TAG, "send binder to $packageName in user $userId")
                val container = reply.getParcelable<BinderContainer>(EXTRA_BINDER)

                if (container?.binder != null && container.binder.pingBinder()) {
                    shizukuBinder = container.binder
                    try {
                        shizukuBinder?.linkToDeath({
                            Log.i(TAG, "exiting...")
                            System.exit(0)
                        }, 0)
                    } catch (t: Throwable) {
                        Log.w(TAG, "linkToDeath failed", t)
                    }
                    return@withContext true
                } else {
                    Log.w(TAG, "server binder not received")
                }
            }

            return@withContext false
        } catch (tr: Throwable) {
            Log.e(TAG, "failed send binder to $packageName in user $userId", tr)
            return@withContext false
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null)
                } catch (tr: Throwable) {
                    Log.w(TAG, "removeContentProviderExternal", tr)
                }
            }
        }
    }
}
