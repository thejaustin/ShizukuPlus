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
import moe.shizuku.manager.utils.ActivityLogManager
import moe.shizuku.manager.utils.AppContextManager
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
    private val appContextView get() = binding.appContext
    private val checkbox get() = binding.checkbox
    private val switchWidget get() = binding.switchWidget
    private val root get() = binding.requiresRoot
    private val plus get() = binding.requiresPlus

    init {
        itemView.filterTouchesWhenObscured = true
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)
        // Tap the package name to copy it
        pkg.setOnClickListener { v ->
            if ((adapter as AppsAdapter).isSelectionMode()) {
                onClick(itemView)
                return@setOnClickListener
            }
            val clipboard = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("package_name", packageName))
            Toast.makeText(v.context, R.string.app_management_package_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private inline val packageName get() = data.packageName
    private inline val ai get() = data.applicationInfo
    private inline val uid get() = ai?.uid ?: 0

    private var loadIconJob: Job? = null

    // ----- Long-press: reads Settings to decide menu vs. direct action -----

    private data class LpAction(val label: String, val run: () -> Unit)

    override fun onLongClick(v: View): Boolean {
        val appsAdapter = adapter as AppsAdapter
        if (!appsAdapter.isSelectionMode()) {
            appsAdapter.isSelectionMode = true
            appsAdapter.toggleSelection(packageName)
            return true
        }
        val context = v.context
        val pm = context.packageManager
        val appInfo = ai ?: return true
        val appLabel = appInfo.loadLabel(pm)
        val isGranted = runCatching { AuthorizationManager.granted(packageName, appInfo.uid) }.getOrDefault(false)

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
        val appLabel = ai?.loadLabel(pm)?.toString() ?: packageName
        return buildList {
            if (ShizukuSettings.getLongPressOpenApp()) {
                add(LpAction(context.getString(R.string.app_management_context_open_app)) {
                    ActivityLogManager.log(appLabel, packageName, "Long-press: open_app")
                    val intent = pm.getLaunchIntentForPackage(packageName)
                    if (intent != null) launchActivity(context, intent)
                    else Toast.makeText(context, R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
                })
            }
            if (ShizukuSettings.getLongPressAppInfo()) {
                add(LpAction(context.getString(R.string.app_management_context_app_info)) {
                    ActivityLogManager.log(appLabel, packageName, "Long-press: app_info")
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
                        if (isGranted) {
                            AuthorizationManager.revoke(packageName, uid)
                            ActivityLogManager.log(appLabel, packageName, "Long-press: revoke_permission")
                        } else {
                            AuthorizationManager.grant(packageName, uid)
                            ActivityLogManager.log(appLabel, packageName, "Long-press: grant_permission")
                        }
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
                    ActivityLogManager.log(appLabel, packageName, "Long-press: hide_app")
                    (context as? Callbacks)?.onHideApp(packageName)
                })
            }
        }
    }

    // ----- Regular tap: toggle permission -----

    override fun onClick(v: View) {
        val appsAdapter = adapter as AppsAdapter
        if (appsAdapter.isSelectionMode()) {
            appsAdapter.toggleSelection(packageName)
            return
        }
        val context = v.context
        val appInfo = ai ?: return
        val appLabel = appInfo.loadLabel(context.packageManager).toString()
        try {
            if (AuthorizationManager.granted(packageName, appInfo.uid)) {
                AuthorizationManager.revoke(packageName, appInfo.uid)
                ActivityLogManager.log(appLabel, packageName, context.getString(R.string.app_management_log_permission_toggle, context.getString(R.string.app_management_context_revoke)))
            } else {
                AuthorizationManager.grant(packageName, appInfo.uid)
                ActivityLogManager.log(appLabel, packageName, context.getString(R.string.app_management_log_permission_toggle, context.getString(R.string.app_management_context_grant)))
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
        val appInfo = ai ?: return
        val pm = itemView.context.packageManager
        val userId = UserHandleCompat.getUserId(appInfo.uid)
        icon.setImageDrawable(appInfo.loadIcon(pm))
        name.text = if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "${appInfo.loadLabel(pm)} - ${userInfo.name} ($userId)"
        } else {
            appInfo.loadLabel(pm)
        }
        
        val appsAdapter = adapter as AppsAdapter
        if (appsAdapter.isSelectionMode()) {
            checkbox.visibility = View.VISIBLE
            checkbox.isChecked = appsAdapter.selectedPackages.contains(packageName)
            switchWidget.visibility = View.GONE
        } else {
            checkbox.visibility = View.GONE
            switchWidget.visibility = View.VISIBLE
            switchWidget.isChecked = AuthorizationManager.granted(packageName, appInfo.uid)
        }

                pkg.text = appInfo.packageName
                
                val description = AppContextManager.getDescription(packageName)
                if (description != null) {
                    appContextView.visibility = View.VISIBLE
                    appContextView.text = context.getString(R.string.app_management_item_context, description)
                } else {
                    appContextView.visibility = View.GONE
                }
                
                root.visibility = if (appInfo.metaData != null &&
            appInfo.metaData.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT"))
            View.VISIBLE else View.GONE

        val isPlusRequired = AuthorizationManager.isPlusApiSupported(data)
        val isPlusEnabled = ShizukuSettings.isCustomApiEnabled()
        val isPlusMissing = isPlusRequired && !isPlusEnabled

        plus.visibility = if (isPlusMissing) View.VISIBLE else View.GONE

        itemView.isEnabled = !isPlusMissing
        itemView.alpha = if (isPlusMissing) 0.5f else 1.0f
        switchWidget.isEnabled = !isPlusMissing

        loadIconJob = AppIconCache.loadIconBitmapAsync(context, appInfo, appInfo.uid / 100000, icon)
    }

    override fun onBind(payloads: List<Any>) {
        val appInfo = ai ?: return
        switchWidget.isChecked = AuthorizationManager.granted(packageName, appInfo.uid)
    }

    override fun onRecycle() {
        loadIconJob?.cancel()
    }
}
