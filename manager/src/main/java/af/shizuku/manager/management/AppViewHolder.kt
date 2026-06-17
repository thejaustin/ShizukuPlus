package af.shizuku.manager.management

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.TypedValue
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.databinding.AppListItemBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.ShizukuSystemApis
import af.shizuku.common.util.UserHandleCompat
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
    private var grantedLoadJob: Job? = null

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
        val appInfo = ai ?: return true
        // Capture values before entering coroutine — the ViewHolder may be rebound
        val capturedPackage = packageName
        val capturedUid = uid
        val appLabel = AppIconCache.getLabel(context, appInfo)

        CoroutineScope(Dispatchers.IO).launch {
            val isGranted = runCatching {
                AuthorizationManager.granted(capturedPackage, appInfo.uid)
            }.getOrDefault(false)
            val enabled = buildEnabledActions(context, capturedPackage, capturedUid, appLabel, appInfo, isGranted)
            withContext(Dispatchers.Main) {
                when {
                    enabled.isEmpty() -> { /* consume silently */ }
                    enabled.size == 1 -> enabled[0].run()
                    else -> MaterialAlertDialogBuilder(context)
                        .setTitle(appLabel)
                        .setItems(enabled.map { it.label }.toTypedArray()) { _, i -> enabled[i].run() }
                        .show()
                }
            }
        }
        return true
    }

    // Called from IO thread (inside onLongClick coroutine), so binder calls here are safe.
    private fun buildEnabledActions(
        context: Context,
        capturedPackage: String,
        capturedUid: Int,
        appLabel: String,
        appInfo: android.content.pm.ApplicationInfo,
        isGranted: Boolean
    ): List<LpAction> {
        val pm = context.packageManager
        return buildList {
            if (ShizukuSettings.getLongPressOpenApp()) {
                add(LpAction(context.getString(R.string.app_management_context_open_app)) {
                    ActivityLogManager.log(appLabel, capturedPackage, "Long-press: open_app")
                    val intent = pm.getLaunchIntentForPackage(capturedPackage)
                    if (intent != null) launchActivity(context, intent)
                    else Toast.makeText(context, R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
                })
            }
            if (ShizukuSettings.getLongPressAppInfo()) {
                add(LpAction(context.getString(R.string.app_management_context_app_info)) {
                    ActivityLogManager.log(appLabel, capturedPackage, "Long-press: app_info")
                    launchActivity(context, Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", capturedPackage, null)
                    ))
                })
            }
            if (ShizukuSettings.getLongPressTogglePermission()) {
                val label = if (isGranted)
                    context.getString(R.string.app_management_context_revoke)
                else
                    context.getString(R.string.app_management_context_grant)
                add(LpAction(label) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (isGranted) {
                                AuthorizationManager.revoke(capturedPackage, capturedUid)
                                ActivityLogManager.log(appLabel, capturedPackage, "Long-press: revoke_permission")
                            } else {
                                AuthorizationManager.grant(capturedPackage, capturedUid)
                                ActivityLogManager.log(appLabel, capturedPackage, "Long-press: grant_permission")
                            }
                            withContext(Dispatchers.Main) {
                                val pos = adapterPosition
                                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                                    adapter.notifyItemChanged(pos, Any())
                                    adapter.notifyItemChanged(0)
                                }
                            }
                        } catch (e: SecurityException) {
                            val uidCheck = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                            withContext(Dispatchers.Main) {
                                if (uidCheck != 0) showAdbLimitedDialog(context)
                            }
                        }
                    }
                })
            }
            if (ShizukuSettings.getLongPressHideFromList()) {
                add(LpAction(context.getString(R.string.app_management_context_hide)) {
                    ActivityLogManager.log(appLabel, capturedPackage, "Long-press: hide_app")
                    (context as? Callbacks)?.onHideApp(capturedPackage)
                })
            }

            // Freeze/Unfreeze — binder calls are safe here since buildEnabledActions runs on IO
            if (ShizukuSettings.isCustomApiEnabled()) {
                val shizukuService = try { Shizuku.getBinder() } catch (e: Exception) { null }
                if (shizukuService != null) {
                    val amPlus = try {
                        moe.shizuku.server.IShizukuService.Stub.asInterface(shizukuService).activityManagerPlus
                    } catch (e: Exception) { null }
                    if (amPlus != null) {
                        val isFrozen = try { amPlus.isAppFrozen(capturedPackage) } catch (e: Exception) { false }
                        val freezeLabel = if (isFrozen) "Unfreeze App (Enable)" else "Freeze App (Disable)"
                        add(LpAction(freezeLabel) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val success = if (isFrozen) amPlus.unfreezeApp(capturedPackage) else amPlus.freezeApp(capturedPackage)
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            Toast.makeText(context, if (isFrozen) "App unfrozen" else "App frozen", Toast.LENGTH_SHORT).show()
                                            ActivityLogManager.log(appLabel, capturedPackage, "Long-press: ${if (isFrozen) "unfreeze" else "freeze"}")
                                            val pos = adapterPosition
                                            if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
                                        } else {
                                            Toast.makeText(context, "Operation failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        })
                    }
                }
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
        val capturedPackage = packageName
        val appLabel = AppIconCache.getLabel(context, appInfo)
        val revokeLabel = context.getString(R.string.app_management_log_permission_toggle, context.getString(R.string.app_management_context_revoke))
        val grantLabel = context.getString(R.string.app_management_log_permission_toggle, context.getString(R.string.app_management_context_grant))
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (AuthorizationManager.granted(capturedPackage, appInfo.uid)) {
                    AuthorizationManager.revoke(capturedPackage, appInfo.uid)
                    ActivityLogManager.log(appLabel, capturedPackage, revokeLabel)
                } else {
                    AuthorizationManager.grant(capturedPackage, appInfo.uid)
                    ActivityLogManager.log(appLabel, capturedPackage, grantLabel)
                }
                withContext(Dispatchers.Main) {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        adapter.notifyItemChanged(pos, Any())
                        adapter.notifyItemChanged(0)
                    }
                }
            } catch (e: SecurityException) {
                val uidCheck = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                withContext(Dispatchers.Main) {
                    if (uidCheck != 0) showAdbLimitedDialog(context)
                }
            }
        }
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

    private fun showEnhancementSettings(context: Context, metadata: AppContextManager.AppMetadata) {
        val enhancements = metadata.potentialEnhancements
        val checkedItems = BooleanArray(enhancements.size) { i ->
            ShizukuSettings.isAppEnhancementEnabled(packageName, enhancements[i].key)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.app_management_enhancements)
            .setMessage(R.string.app_management_enhancements_desc)
            .setMultiChoiceItems(enhancements.map { "${it.title}: ${it.description}" }.toTypedArray(), checkedItems) { _, which, isChecked ->
                ShizukuSettings.setAppEnhancementEnabled(packageName, enhancements[which].key, isChecked)
                val appLabel = ai?.let { AppIconCache.getLabel(context, it) } ?: packageName
                ActivityLogManager.log(appLabel, packageName, "Toggle Enhancement: ${enhancements[which].key} -> $isChecked")
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pos = adapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    adapter.notifyItemChanged(pos)
                }
            }
            .show()
    }

    override fun onBind() {
        val appInfo = ai ?: return
        val context = itemView.context
        val capturedPackage = packageName
        val capturedData = data

        val userId = UserHandleCompat.getUserId(appInfo.uid)
        val appLabel = AppIconCache.getLabel(context, appInfo)

        name.text = if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "$appLabel - ${userInfo.name} ($userId)"
        } else {
            appLabel
        }

        val appsAdapter = adapter as AppsAdapter
        if (appsAdapter.isSelectionMode()) {
            checkbox.visibility = View.VISIBLE
            checkbox.isChecked = appsAdapter.selectedPackages.contains(capturedPackage)
            switchWidget.visibility = View.GONE
        } else {
            checkbox.visibility = View.GONE
            switchWidget.visibility = View.VISIBLE
            // Load granted state off the main thread to avoid blocking during scrolling
            switchWidget.isEnabled = false
            grantedLoadJob?.cancel()
            grantedLoadJob = CoroutineScope(Dispatchers.IO).launch {
                val granted = AuthorizationManager.granted(capturedPackage, appInfo.uid)
                val isPlusMissing = AuthorizationManager.isPlusApiSupported(capturedData) &&
                        !ShizukuSettings.isCustomApiEnabled()
                withContext(Dispatchers.Main) {
                    if (adapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        switchWidget.isChecked = granted
                        if (!isPlusMissing) switchWidget.isEnabled = true
                    }
                }
            }
        }

        pkg.text = appInfo.packageName

        val metadata = AppContextManager.getMetadata(capturedPackage)
        if (metadata != null) {
            appContextView.visibility = View.VISIBLE
            val enabledAny = metadata.potentialEnhancements.any { ShizukuSettings.isAppEnhancementEnabled(capturedPackage, it.key) }
            val badge = if (enabledAny) context.getString(R.string.app_management_badge_enhanced) else context.getString(R.string.app_management_badge_upgrade)
            val colorAttr = if (enabledAny) com.google.android.material.R.attr.colorTertiary else com.google.android.material.R.attr.colorSecondary
            val tv = TypedValue()
            context.theme.resolveAttribute(colorAttr, tv, true)
            val color = String.format("#%06X", tv.data and 0xFFFFFF)

            appContextView.text = context.getString(R.string.app_management_badge_format, color, badge, metadata.description).toHtml()
            appContextView.setOnClickListener { showEnhancementSettings(context, metadata) }
        } else {
            appContextView.visibility = View.GONE
            appContextView.setOnClickListener(null)
        }

        root.visibility = if (appInfo.metaData != null && appInfo.metaData.getBoolean("af.shizuku.client.V3_REQUIRES_ROOT"))
            View.VISIBLE else View.GONE

        val isPlusRequired = AuthorizationManager.isPlusApiSupported(capturedData)
        val isPlusEnabled = ShizukuSettings.isCustomApiEnabled()
        val isPlusMissing = isPlusRequired && !isPlusEnabled

        plus.visibility = if (isPlusMissing) View.VISIBLE else View.GONE

        itemView.isEnabled = !isPlusMissing
        itemView.alpha = if (isPlusMissing) 0.5f else 1.0f

        loadIconJob = AppIconCache.loadIconBitmapAsync(context, appInfo, appInfo.uid / 100000, icon)
    }

    override fun onBind(payloads: List<Any>) {
        val appInfo = ai ?: return
        val capturedPackage = packageName
        grantedLoadJob?.cancel()
        grantedLoadJob = CoroutineScope(Dispatchers.IO).launch {
            val granted = AuthorizationManager.granted(capturedPackage, appInfo.uid)
            withContext(Dispatchers.Main) {
                if (adapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    switchWidget.isChecked = granted
                }
            }
        }
    }

    override fun onRecycle() {
        loadIconJob?.cancel()
        grantedLoadJob?.cancel()
    }
}
