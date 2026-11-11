package af.shizuku.manager.authorization

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuStateMachine
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.html.text.HtmlCompat

/**
 * Answers Dhizuku.requestPermission() from Dhizuku-API 2.5+ clients (MDPC, OwnDroid forks…),
 * which start `<owner>.action.REQUEST_DHIZUKU_PERMISSION` when we're the device owner. Without
 * this activity the library reports "denied" straight away (#670).
 *
 * Users who never turned on Dhizuku Mode never see anything: the request is denied silently. So
 * is a request for an app that never connected to DhizukuProvider, because the uid in the
 * request can't be checked here.
 * Allowing stores a normal Shizuku+ grant, so the app shows up (and can be revoked) in the usual
 * app list.
 */
class DhizukuRequestPermissionActivity : AppActivity() {
    private var listener: IBinder? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The library puts the values both in the extras and in a nested "bundle" (older form).
        val extras = intent.getBundleExtra("bundle") ?: intent.extras
        val uid = extras?.getInt(PARAM_CLIENT_UID, -1) ?: -1
        listener = extras?.getBinder(PARAM_CLIENT_REQUEST_PERMISSION_BINDER)
        val packageName = if (uid >= 0) packageManager.getPackagesForUid(uid)?.firstOrNull() else null

        // The uid is only what the request claims; see DhizukuProvider.rememberClient.
        if (!ShizukuSettings.isDhizukuModeEnabled() ||
            packageName == null ||
            listener == null ||
            !af.shizuku.manager.DhizukuProvider
                .hasConnected(uid)
        ) {
            reply(false)
            finish()
            return
        }
        if (!ShizukuStateMachine.isRunning()) {
            Toast.makeText(this, R.string.dhizuku_request_not_running, Toast.LENGTH_LONG).show()
            reply(false)
            finish()
            return
        }

        lifecycleScope.launch {
            val alreadyGranted = withContext(Dispatchers.IO) { AuthorizationManager.granted(packageName, uid) }
            if (alreadyGranted) {
                reply(true)
                finish()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            showDialog(packageName, uid)
        }
    }

    private fun showDialog(
        packageName: String,
        uid: Int,
    ) {
        val label =
            try {
                packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(HtmlCompat.fromHtml(getString(R.string.dhizuku_request_title, label)))
                .setMessage(R.string.dhizuku_request_message)
                .setPositiveButton(R.string.dhizuku_request_allow) { _, _ ->
                    lifecycleScope.launch {
                        val ok =
                            withContext(Dispatchers.IO) {
                                try {
                                    AuthorizationManager.grant(packageName, uid)
                                    true
                                } catch (_: SecurityException) {
                                    false
                                }
                            }
                        af.shizuku.manager.database.ActivityLogManager
                            .log(label.toString(), packageName, if (ok) "Dhizuku: Granted by user" else "Dhizuku: Grant failed")
                        reply(ok)
                        finish()
                    }
                }.setNegativeButton(R.string.dhizuku_request_deny) { _, _ ->
                    af.shizuku.manager.database.ActivityLogManager
                        .log(label.toString(), packageName, "Dhizuku: Denied by user")
                    reply(false)
                }.setOnDismissListener { if (answered) finish() }
                .setCancelable(false)
                .create()
        dialog.setCanceledOnTouchOutside(false)
        try {
            dialog.show()
        } catch (_: WindowManager.BadTokenException) {
            reply(false)
            finish()
        }
    }

    override fun onDestroy() {
        // Leaving without an answer must still unblock the caller (but not on rotation; the
        // recreated activity asks again).
        if (!isChangingConfigurations) reply(false)
        super.onDestroy()
    }

    // IDhizukuRequestPermissionListener.onRequestPermission(int) — oneway, so safe on main.
    private fun reply(granted: Boolean) {
        if (answered) return
        answered = true
        val binder = listener ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener")
            data.writeInt(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    private companion object {
        const val PARAM_CLIENT_UID = "uid"
        const val PARAM_CLIENT_REQUEST_PERMISSION_BINDER = "request_permission_binder"
    }
}
