package moe.shizuku.manager.management

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.databinding.AppListItemBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.AppIconCache
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class AppViewHolder(private val binding: AppListItemBinding) :
    BaseViewHolder<PackageInfo>(binding.root), View.OnClickListener, View.OnLongClickListener {

    interface Callbacks {
        fun onHideApp(packageName: String)
    }

    companion object {
        @JvmField
        val CREATOR = Creator<PackageInfo> { inflater: LayoutInflater, parent: ViewGroup? ->
            AppViewHolder(AppListItemBinding.inflate(inflater, parent, false))
        }
    }

    private val icon get() = binding.icon
    private val name get() = binding.title
    private val pkg get() = binding.summary
    private val switchWidget get() = binding.switchWidget
    private val root get() = binding.requiresRoot

    init {
        itemView.filterTouchesWhenObscured = true
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)
        // Tap the package name to copy it
        pkg.setOnClickListener { v ->
            val clipboard = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("package_name", packageName))
            Toast.makeText(v.context, R.string.app_management_package_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private inline val packageName get() = data.packageName
    private inline val ai get() = data.applicationInfo!!
    private inline val uid get() = ai.uid

    private var loadIconJob: Job? = null

    // ----- Long-press: reads Settings to decide menu vs. direct action -----

    private data class LpAction(val label: String, val run: () -> Unit)

    override fun onLongClick(v: View): Boolean {
        val context = v.context
        val pm = context.packageManager
        val appLabel = ai.loadLabel(pm)
        val isGranted = runCatching { AuthorizationManager.granted(packageName, uid) }.getOrDefault(false)

        val enabled = buildEnabledActions(context, isGranted)

        when {
            enabled.isEmpty() -> { /* all actions disabled — consume silently */ }
            enabled.size == 1 -> enabled[0].run()
            else -> MaterialAlertDialogBuilder(context)
                .setTitle(appLabel)
                .setItems(enabled.map { it.label }.toTypedArray()) { _, i -> enabled[i].run() }
                .show()
        }
        return true
    }

    private fun buildEnabledActions(context: Context, isGranted: Boolean): List<LpAction> {
        val pm = context.packageManager
        return buildList {
            if (ShizukuSettings.getLongPressOpenApp()) {
                add(LpAction(context.getString(R.string.app_management_context_open_app)) {
                    val intent = pm.getLaunchIntentForPackage(packageName)
                    if (intent != null) launchActivity(context, intent)
                    else Toast.makeText(context, R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
                })
            }
            if (ShizukuSettings.getLongPressAppInfo()) {
                add(LpAction(context.getString(R.string.app_management_context_app_info)) {
                    launchActivity(context, Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    ))
                })
            }
            if (ShizukuSettings.getLongPressTogglePermission()) {
                val label = if (isGranted)
                    context.getString(R.string.app_management_context_revoke)
                else
                    context.getString(R.string.app_management_context_grant)
                add(LpAction(label) {
                    try {
                        if (isGranted) AuthorizationManager.revoke(packageName, uid)
                        else AuthorizationManager.grant(packageName, uid)
                        adapter.notifyItemChanged(adapterPosition, Any())
                        adapter.notifyItemChanged(0)
                    } catch (e: SecurityException) {
                        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 0) {
                            showAdbLimitedDialog(context)
                        }
                    }
                })
            }
            if (ShizukuSettings.getLongPressHideFromList()) {
                add(LpAction(context.getString(R.string.app_management_context_hide)) {
                    (context as? Callbacks)?.onHideApp(packageName)
                })
            }
        }
    }

    // ----- Regular tap: toggle permission -----

    override fun onClick(v: View) {
        val context = v.context
        try {
            if (AuthorizationManager.granted(packageName, uid)) {
                AuthorizationManager.revoke(packageName, uid)
            } else {
                AuthorizationManager.grant(packageName, uid)
            }
        } catch (e: SecurityException) {
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            if (uid != 0) showAdbLimitedDialog(context)
            return
        }
        adapter.notifyItemChanged(adapterPosition, Any())
        adapter.notifyItemChanged(0)
    }

    // ----- Helpers -----

    private fun launchActivity(context: Context, intent: Intent) {
        val activity = context as? Activity
        if (activity != null) {
            val opts = ActivityOptions.makeCustomAnimation(
                activity, android.R.anim.fade_in, android.R.anim.fade_out
            )
            activity.startActivity(intent, opts.toBundle())
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun showAdbLimitedDialog(context: Context) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.app_management_dialog_adb_is_limited_title)
            .setMessage(
                context.getString(
                    R.string.app_management_dialog_adb_is_limited_message,
                    Helps.ADB.get()
                ).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            )
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkMovementMethod.getInstance()
        }
        runCatching { dialog.show() }
    }

    override fun onBind() {
        val pm = itemView.context.packageManager
        val userId = UserHandleCompat.getUserId(uid)
        icon.setImageDrawable(ai.loadIcon(pm))
        name.text = if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "${ai.loadLabel(pm)} - ${userInfo.name} ($userId)"
        } else {
            ai.loadLabel(pm)
        }
        pkg.text = ai.packageName
        switchWidget.isChecked = AuthorizationManager.granted(packageName, uid)
        root.visibility = if (ai.metaData != null &&
            ai.metaData.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT"))
            View.VISIBLE else View.GONE

        loadIconJob = AppIconCache.loadIconBitmapAsync(context, ai, ai.uid / 100000, icon)
    }

    override fun onBind(payloads: List<Any>) {
        switchWidget.isChecked = AuthorizationManager.granted(packageName, uid)
    }

    override fun onRecycle() {
        loadIconJob?.cancel()
    }
}
