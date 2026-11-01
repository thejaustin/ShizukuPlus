package af.shizuku.manager.management

import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.databinding.AppListItemBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.DpmAppHider
import af.shizuku.manager.utils.ShizukuSystemApis
import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class AppViewHolder(
    private val binding: AppListItemBinding,
) : BaseViewHolder<PackageInfo>(binding.root),
    View.OnClickListener,
    View.OnLongClickListener {
    interface Callbacks {
        fun onHideApp(packageName: String)
    }

    companion object {
        @JvmField
        val CREATOR =
            Creator<PackageInfo> { inflater: LayoutInflater, parent: ViewGroup? ->
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
        // TalkBack-accessible equivalent of the row's swipe-to-act gestures (#41) - a swipe
        // gesture has no screen-reader affordance on its own. Reads the swipe-action settings and
        // `data` live at query/perform time (not captured at bind time) so it can't go stale
        // across a settings change or ViewHolder rebind without needing separate invalidation.
        ViewCompat.setAccessibilityDelegate(
            itemView,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    swipeActionLabel(host.context, ShizukuSettings.getSwipeRightAction())?.let { label ->
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.accessibility_action_swipe_right, label))
                    }
                    swipeActionLabel(host.context, ShizukuSettings.getSwipeLeftAction())?.let { label ->
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.accessibility_action_swipe_left, label))
                    }
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    val activity = host.context as? ApplicationManagementActivity
                    when (action) {
                        R.id.accessibility_action_swipe_right -> {
                            activity?.handleSwipeAction(data, ShizukuSettings.getSwipeRightAction(), host)
                            return true
                        }
                        R.id.accessibility_action_swipe_left -> {
                            activity?.handleSwipeAction(data, ShizukuSettings.getSwipeLeftAction(), host)
                            return true
                        }
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            },
        )
        pkg.setOnClickListener { v ->
            if ((adapter as AppsAdapter).isSelectionMode()) {
                onClick(itemView)
                return@setOnClickListener
            }
            val clipboard = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("package_name", packageName))
            notifyUser(v.context.getString(R.string.app_management_package_copied))
        }
    }

    private inline val packageName get() = data.packageName
    private inline val ai get() = data.applicationInfo
    private inline val uid get() = ai?.uid ?: 0

    private var loadIconJob: Job? = null
    private var grantedLoadJob: Job? = null
    private var nameLoadJob: Job? = null

    // Incremented on every bind; coroutines capture the value at launch and check it on Main
    // to detect whether the ViewHolder was rebound before their withContext(Main) block runs.
    private var bindGeneration: Int = 0

    // ----- Long-press: reads Settings to decide menu vs. direct action -----

    private data class LpAction(
        val label: String,
        val run: () -> Unit,
    )

    override fun onLongClick(v: View): Boolean {
        val appsAdapter = adapter as AppsAdapter
        if (appsAdapter.isSelectionMode()) {
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
            val isGranted =
                runCatching {
                    AuthorizationManager.granted(capturedPackage, appInfo.uid)
                }.getOrDefault(false)
            val enabled = buildEnabledActions(context, capturedPackage, capturedUid, appLabel, appInfo, isGranted)
            withContext(Dispatchers.Main) {
                when {
                    enabled.isEmpty() -> {
                        appsAdapter.isSelectionMode = true
                        appsAdapter.toggleSelection(capturedPackage)
                    }
                    else ->
                        MaterialAlertDialogBuilder(context)
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
        isGranted: Boolean,
    ): List<LpAction> {
        val pm = context.packageManager
        return buildList {
            if (ShizukuSettings.getLongPressOpenApp()) {
                add(
                    LpAction(context.getString(R.string.app_management_context_open_app)) {
                        ActivityLogManager.log(appLabel, capturedPackage, "Long-press: open_app")
                        val intent = pm.getLaunchIntentForPackage(capturedPackage)
                        if (intent != null) {
                            launchActivity(context, intent)
                        } else {
                            notifyUser(context.getString(R.string.app_management_no_launcher))
                        }
                    },
                )
            }
            if (ShizukuSettings.getLongPressAppInfo()) {
                add(
                    LpAction(context.getString(R.string.app_management_context_app_info)) {
                        ActivityLogManager.log(appLabel, capturedPackage, "Long-press: app_info")
                        launchActivity(
                            context,
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", capturedPackage, null),
                            ),
                        )
                    },
                )
            }
            if (ShizukuSettings.getLongPressTogglePermission()) {
                val label =
                    if (isGranted) {
                        context.getString(R.string.app_management_context_revoke)
                    } else {
                        context.getString(R.string.app_management_context_grant)
                    }
                add(
                    LpAction(label) {
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
                            } catch (_: SecurityException) {
                                val uidCheck = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                                withContext(Dispatchers.Main) {
                                    if (uidCheck != 0) showAdbLimitedDialog(context)
                                }
                            }
                        }
                    },
                )
            }
            if (ShizukuSettings.getLongPressHideFromList()) {
                add(
                    LpAction(context.getString(R.string.app_management_context_hide)) {
                        ActivityLogManager.log(appLabel, capturedPackage, "Long-press: hide_app")
                        (context as? Callbacks)?.onHideApp(capturedPackage)
                    },
                )
            }

            // Granular per-app API access scopes (#645)
            if (ShizukuSettings.getLongPressApiScopes()) {
                add(
                    LpAction(context.getString(R.string.app_management_context_api_scopes)) {
                        showApiScopesDialog(context, capturedPackage, appLabel)
                    },
                )
            }

            // Read-only global Plus features hint & link (#631)
            val metadata = AppContextManager.getMetadata(capturedPackage)
            if (ShizukuSettings.isShowPlusFeatureHintsEnabled() &&
                !ShizukuSettings.isHideDisabledPlusFeaturesEnabled() &&
                metadata != null &&
                metadata.recommendedPlusFeatures.isNotEmpty()
            ) {
                add(
                    LpAction(context.getString(R.string.app_management_context_plus_benefits)) {
                        showPlusBenefitsDialog(context, appLabel, metadata.recommendedPlusFeatures)
                    },
                )
            }

            // Bulk selection mode (#1)
            if (ShizukuSettings.getLongPressSelectMultiple()) {
                add(
                    LpAction(context.getString(R.string.app_management_context_select_multiple)) {
                        val appsAdapter = adapter as AppsAdapter
                        appsAdapter.isSelectionMode = true
                        appsAdapter.toggleSelection(capturedPackage)
                    },
                )
            }

            // Freeze/Unfreeze & Per-App Free-Form Window Resizing (#635) — binder calls are safe here on IO
            var freezeService: af.shizuku.server.IActivityManagerPlus? = null
            if (ShizukuSettings.isCustomApiEnabled()) {
                val shizukuService =
                    try {
                        Shizuku.getBinder()
                    } catch (_: Exception) {
                        null
                    }
                if (shizukuService != null) {
                    val serviceStub =
                        try {
                            moe.shizuku.server.IShizukuService.Stub
                                .asInterface(shizukuService)
                        } catch (_: Exception) {
                            null
                        }
                    if (ShizukuSettings.getLongPressFreeformResize() && ShizukuSettings.isWindowManagerPlusEnabled()) {
                        val wmPlus =
                            try {
                                serviceStub?.windowManagerPlus
                            } catch (_: Exception) {
                                null
                            }
                        if (wmPlus != null) {
                            val isResizable = ShizukuSettings.isAppForceResizable(capturedPackage)
                            val freeformLabel =
                                context.getString(
                                    if (isResizable) R.string.lp_action_disable_freeform else R.string.lp_action_enable_freeform,
                                )
                            add(
                                LpAction(freeformLabel) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val targetState = !isResizable
                                            wmPlus.forceResizable(capturedPackage, targetState)
                                            ShizukuSettings.setAppForceResizable(capturedPackage, targetState)
                                            ActivityLogManager.log(
                                                appLabel,
                                                capturedPackage,
                                                "Long-press: ${if (targetState) "enable_freeform" else "disable_freeform"}",
                                            )
                                            withContext(Dispatchers.Main) {
                                                notifyUser(
                                                    context.getString(
                                                        if (targetState) R.string.toast_freeform_enabled else R.string.toast_freeform_disabled,
                                                        appLabel,
                                                    ),
                                                )
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                notifyUser(context.getString(R.string.toast_error_with_message, e.message))
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    freezeService =
                        try {
                            serviceStub?.activityManagerPlus
                        } catch (_: Exception) {
                            null
                        }
                }
            }

            // Freeze/Unfreeze (#632): DevicePolicyManager when Shizuku+ is device/profile owner
            // (only the admin's owner may hide apps), else the server's disable/suspend chain.
            if (ShizukuSettings.getLongPressFreezeApp()) {
                val amPlus = freezeService
                val useDpm = ShizukuSettings.isDpmFreezeEnabled() && DpmAppHider.isAvailable(context)
                if (useDpm || amPlus != null) {
                    val dpmFrozen = useDpm && DpmAppHider.isFrozen(context, capturedPackage)
                    val isFrozen =
                        dpmFrozen ||
                            try {
                                amPlus?.isAppFrozen(capturedPackage) == true
                            } catch (_: Exception) {
                                false
                            }
                    val freezeLabel = context.getString(if (isFrozen) R.string.lp_action_unfreeze_app else R.string.lp_action_freeze_app)
                    add(
                        LpAction(freezeLabel) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val success =
                                        if (isFrozen) {
                                            var restored = dpmFrozen && DpmAppHider.setFrozen(context, capturedPackage, false)
                                            if (amPlus != null && amPlus.isAppFrozen(capturedPackage)) {
                                                restored = amPlus.unfreezeApp(capturedPackage) || restored
                                            }
                                            restored
                                        } else {
                                            (useDpm && DpmAppHider.setFrozen(context, capturedPackage, true)) ||
                                                amPlus?.freezeApp(capturedPackage) == true
                                        }
                                    withContext(Dispatchers.Main) {
                                        if (success) {
                                            notifyUser(context.getString(if (isFrozen) R.string.toast_app_unfrozen else R.string.toast_app_frozen))
                                            ActivityLogManager.log(appLabel, capturedPackage, "Long-press: ${if (isFrozen) "unfreeze" else "freeze"}")
                                            val pos = adapterPosition
                                            if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos)
                                        } else {
                                            notifyUser(context.getString(R.string.toast_operation_failed))
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        notifyUser(context.getString(R.string.toast_error_with_message, e.message))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // ----- Regular tap: toggle permission -----

    override fun onClick(v: View) {
        val appsAdapter = adapter as AppsAdapter
        if (appsAdapter.isSelectionMode()) {
            appsAdapter.toggleSelection(packageName)
            af.shizuku.manager.utils.HapticUtils
                .segmentTick(v)
            return
        }
        val context = v.context
        val appInfo = ai ?: return
        val capturedPackage = packageName
        val appLabel = AppIconCache.getLabel(context, appInfo)
        val revokeLabel = af.shizuku.manager.activitylog.ActivityLogText.TOGGLE_REVOKE
        val grantLabel = af.shizuku.manager.activitylog.ActivityLogText.TOGGLE_GRANT
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val wasGranted = AuthorizationManager.granted(capturedPackage, appInfo.uid)
                if (wasGranted) {
                    AuthorizationManager.revoke(capturedPackage, appInfo.uid)
                    ActivityLogManager.log(appLabel, capturedPackage, revokeLabel)
                } else {
                    AuthorizationManager.grant(capturedPackage, appInfo.uid)
                    ActivityLogManager.log(appLabel, capturedPackage, grantLabel)
                }
                withContext(Dispatchers.Main) {
                    if (wasGranted) {
                        af.shizuku.manager.utils.HapticUtils
                            .toggleOff(v)
                    } else {
                        af.shizuku.manager.utils.HapticUtils
                            .toggleOn(v)
                    }
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        adapter.notifyItemChanged(pos, Any())
                        adapter.notifyItemChanged(0)
                    }
                }
            } catch (_: SecurityException) {
                withContext(Dispatchers.Main) {
                    af.shizuku.manager.utils.HapticUtils
                        .error(v)
                }
                val uidCheck = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                withContext(Dispatchers.Main) {
                    if (uidCheck != 0) showAdbLimitedDialog(context)
                }
            }
        }
    }

    // ----- Helpers -----

    /** Maps a swipe-action key (settings_app_management.xml's swipe_actions_values) to the same
     *  human-readable label shown in the settings picker, or null for "none" (nothing to expose
     *  as an accessibility action). */
    private fun swipeActionLabel(
        context: Context,
        action: String,
    ): String? {
        if (action == "none") return null
        val values = context.resources.getStringArray(R.array.swipe_actions_values)
        val labels = context.resources.getStringArray(R.array.swipe_actions)
        val idx = values.indexOf(action)
        return if (idx in labels.indices) labels[idx] else null
    }

    private fun launchActivity(
        context: Context,
        intent: Intent,
    ) {
        val activity = context as? Activity
        if (activity != null) {
            val opts =
                ActivityOptions.makeCustomAnimation(
                    activity,
                    android.R.anim.fade_in,
                    android.R.anim.fade_out,
                )
            activity.startActivity(intent, opts.toBundle())
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun showAdbLimitedDialog(context: Context) {
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_management_dialog_adb_is_limited_title)
                .setMessage(
                    context
                        .getString(
                            R.string.app_management_dialog_adb_is_limited_message,
                            Helps.ADB.get(),
                        ).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE),
                ).setPositiveButton(android.R.string.ok, null)
                .create()
        dialog.setOnShowListener {
            (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkMovementMethod.getInstance()
        }
        runCatching { dialog.show() }
    }

    private fun showApiScopesDialog(
        context: Context,
        pkgName: String,
        appLabel: String,
    ) {
        val scopeKeys =
            arrayOf(
                "shell",
                "package_manager",
                "activity_manager",
                "appops",
                "system_settings",
            )
        val scopeLabels =
            arrayOf(
                context.getString(R.string.api_scope_shell),
                context.getString(R.string.api_scope_package_manager),
                context.getString(R.string.api_scope_activity_manager),
                context.getString(R.string.api_scope_appops),
                context.getString(R.string.api_scope_system_settings),
            )
        val disabled = ShizukuSettings.getAppDisabledScopes(pkgName)
        val checked = BooleanArray(scopeKeys.size) { i -> scopeKeys[i] !in disabled }

        // Shown under the list: commands typed into an already open interactive shell can't be
        // inspected, so only turning off Shell blocks those.
        val density = context.resources.displayMetrics.density
        val note =
            TextView(context).apply {
                setText(R.string.api_scopes_dialog_note)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                val padH = (24 * density).toInt()
                setPadding(padH, (8 * density).toInt(), padH, (4 * density).toInt())
            }
        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_shield_24)
            .setTitle(context.getString(R.string.api_scopes_dialog_title, appLabel))
            .setMultiChoiceItems(scopeLabels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setView(note)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newDisabled = mutableSetOf<String>()
                for (i in scopeKeys.indices) {
                    if (!checked[i]) {
                        newDisabled.add(scopeKeys[i])
                    }
                }
                ShizukuSettings.setAppDisabledScopes(pkgName, newDisabled)
                ActivityLogManager.log(
                    appLabel,
                    pkgName,
                    if (newDisabled.isEmpty()) {
                        "API Scopes: all allowed"
                    } else {
                        "API Scopes: restricted (${newDisabled.joinToString(", ")})"
                    },
                )
                val pos = adapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    adapter.notifyItemChanged(pos)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class GlobalPlusFeatureInfo(
        val key: String,
        val title: String,
        val isEnabled: Boolean,
        val highlightKey: String,
    )

    private fun resolveGlobalPlusFeature(
        context: Context,
        featureKey: String,
    ): GlobalPlusFeatureInfo? =
        when (featureKey) {
            "shell_interceptor" ->
                GlobalPlusFeatureInfo(
                    key = featureKey,
                    title = context.getString(R.string.settings_shell_interceptor),
                    isEnabled = ShizukuSettings.isShellInterceptorEnabled(),
                    highlightKey = "plus_governors_module",
                )
            "storage_proxy" ->
                GlobalPlusFeatureInfo(
                    key = featureKey,
                    title = context.getString(R.string.settings_storage_proxy),
                    isEnabled = ShizukuSettings.isStorageProxyEnabled(),
                    highlightKey = "plus_governors_module",
                )
            "overlay_manager_plus" ->
                GlobalPlusFeatureInfo(
                    key = featureKey,
                    title = context.getString(R.string.settings_overlay_manager_plus),
                    isEnabled = ShizukuSettings.isOverlayManagerPlusEnabled(),
                    highlightKey = "overlay_manager_plus_enabled",
                )
            "network_governor_plus" ->
                GlobalPlusFeatureInfo(
                    key = featureKey,
                    title = context.getString(R.string.settings_network_governor_plus_title),
                    isEnabled = ShizukuSettings.isNetworkGovernorPlusEnabled(),
                    highlightKey = "network_governor_plus_enabled",
                )
            else -> null
        }

    /**
     * Read-only dialog (#631 Option 3) listing the global Plus features that [appLabel] benefits
     * from, showing their live global On/Off state and navigating directly to the setting on tap.
     */
    private fun showPlusBenefitsDialog(
        context: Context,
        appLabel: String,
        featureKeys: List<String>,
    ) {
        val resolved = featureKeys.mapNotNull { resolveGlobalPlusFeature(context, it) }
        if (resolved.isEmpty()) return
        val items =
            resolved
                .map { info ->
                    context.getString(
                        if (info.isEnabled) R.string.plus_benefits_item_status_on else R.string.plus_benefits_item_status_off,
                        info.title,
                    )
                }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_bolt_24)
            .setTitle(context.getString(R.string.plus_benefits_dialog_title, appLabel))
            .setItems(items) { _, which ->
                val target = resolved[which]
                val intent =
                    Intent(context, af.shizuku.manager.settings.SettingsActivity::class.java).apply {
                        putExtra("highlight_key", target.highlightKey)
                    }
                launchActivity(context, intent)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Snackbar on the Apps screen; toast if this row was recycled before an async result came back. */
    private fun notifyUser(message: String) {
        if (itemView.isAttachedToWindow) {
            af.shizuku.manager.app.SnackbarHelper
                .show(itemView.context, itemView, message)
        } else {
            Toast.makeText(itemView.context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind() {
        val appInfo = ai ?: return
        val context = itemView.context
        val capturedPackage = packageName
        val capturedData = data

        // Bump once per bind so both async lookups below (name/user-info and granted-state)
        // share one generation and are invalidated together on rebind/recycle.
        val gen = ++bindGeneration

        val userId = UserHandleCompat.getUserId(appInfo.uid)
        val appLabel = AppIconCache.getLabel(context, appInfo)

        nameLoadJob?.cancel()
        if (userId != UserHandleCompat.myUserId()) {
            // Fallback shown immediately; getUserInfo() can trigger a blocking
            // UserManagerApis.getUsers() binder call on a cache miss, so resolve it off-thread.
            name.text = appLabel
            nameLoadJob =
                CoroutineScope(Dispatchers.IO).launch {
                    val userInfo = ShizukuSystemApis.getUserInfo(userId)
                    withContext(Dispatchers.Main) {
                        if (gen == bindGeneration && adapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            name.text = "$appLabel - ${userInfo.name} ($userId)"
                        }
                    }
                }
        } else {
            name.text = appLabel
        }

        af.shizuku.manager.utils.SwitchDesign
            .applyThumb(switchWidget)
        val appsAdapter = adapter as AppsAdapter
        if (appsAdapter.isSelectionMode()) {
            setGrantEmphasis(true, animate = false)
            checkbox.visibility = View.VISIBLE
            checkbox.isChecked = appsAdapter.selectedPackages.contains(capturedPackage)
            switchWidget.visibility = View.GONE
        } else {
            checkbox.visibility = View.GONE
            switchWidget.visibility = View.VISIBLE
            // Load granted state off the main thread to avoid blocking during scrolling
            switchWidget.isEnabled = false
            grantedLoadJob?.cancel()
            grantedLoadJob =
                CoroutineScope(Dispatchers.IO).launch {
                    val granted = AuthorizationManager.granted(capturedPackage, appInfo.uid)
                    val isPlusMissing =
                        AuthorizationManager.isPlusApiSupported(capturedData) &&
                            !ShizukuSettings.isCustomApiEnabled()
                    withContext(Dispatchers.Main) {
                        // Guard against a rebound ViewHolder: adapterPosition alone doesn't catch
                        // the case where the holder was recycled and rebound to a new item at the
                        // same position before this continuation ran.
                        if (gen == bindGeneration && adapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            switchWidget.isChecked = granted
                            setGrantEmphasis(granted, animate = false)
                            if (!isPlusMissing) switchWidget.isEnabled = true
                        }
                    }
                }
        }

        pkg.text = appInfo.packageName

        val showDescriptions = ShizukuSettings.isShowAppDescriptionsEnabled()
        val showHints =
            ShizukuSettings.isShowPlusFeatureHintsEnabled() &&
                !ShizukuSettings.isHideDisabledPlusFeaturesEnabled()
        val metadata = if (showDescriptions) AppContextManager.getMetadata(capturedPackage) else null
        val disabledScopes = ShizukuSettings.getAppDisabledScopes(capturedPackage)
        val firstDisabledPlusBenefit =
            if (showHints) {
                metadata
                    ?.recommendedPlusFeatures
                    ?.mapNotNull { resolveGlobalPlusFeature(context, it) }
                    ?.firstOrNull { !it.isEnabled }
            } else {
                null
            }
        if (disabledScopes.isNotEmpty()) {
            appContextView.visibility = View.VISIBLE
            val badge = context.getString(R.string.api_scopes_restricted_badge, 5 - disabledScopes.size, 5)
            appContextView.text =
                if (metadata != null) {
                    "$badge · ${metadata.description}"
                } else {
                    badge
                }
            appContextView.setOnClickListener {
                showApiScopesDialog(context, capturedPackage, appLabel)
            }
        } else if (metadata != null) {
            appContextView.visibility = View.VISIBLE
            if (firstDisabledPlusBenefit != null) {
                val hint = context.getString(R.string.plus_benefits_badge_hint, firstDisabledPlusBenefit.title)
                appContextView.text = "$hint · ${metadata.description}"
                appContextView.setOnClickListener {
                    showPlusBenefitsDialog(context, appLabel, metadata.recommendedPlusFeatures)
                }
            } else {
                appContextView.text = metadata.description
                if (showHints && metadata.recommendedPlusFeatures.isNotEmpty()) {
                    appContextView.setOnClickListener {
                        showPlusBenefitsDialog(context, appLabel, metadata.recommendedPlusFeatures)
                    }
                } else {
                    appContextView.setOnClickListener(null)
                    appContextView.isClickable = false
                }
            }
        } else {
            appContextView.visibility = View.GONE
            appContextView.setOnClickListener(null)
            appContextView.isClickable = false
        }

        root.visibility =
            if (appInfo.metaData != null && appInfo.metaData.getBoolean("af.shizuku.client.V3_REQUIRES_ROOT")) {
                View.VISIBLE
            } else {
                View.GONE
            }

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
        val gen = ++bindGeneration
        grantedLoadJob =
            CoroutineScope(Dispatchers.IO).launch {
                val granted = AuthorizationManager.granted(capturedPackage, appInfo.uid)
                withContext(Dispatchers.Main) {
                    if (gen == bindGeneration && adapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        switchWidget.isChecked = granted
                        setGrantEmphasis(granted, animate = true)
                    }
                }
            }
    }

    /**
     * Apps without access get the same softened icon as an off setting, so granted apps stand out
     * when scanning the list; it springs back on grant.
     */
    private fun setGrantEmphasis(
        granted: Boolean,
        animate: Boolean,
    ) {
        val scale = if (granted) 1f else 0.9f
        val alpha = if (granted) 1f else 0.6f
        if (!animate) {
            icon.scaleX = scale
            icon.scaleY = scale
            icon.alpha = alpha
            return
        }
        with(af.shizuku.manager.utils.MotionUtils) {
            icon.springTo(androidx.dynamicanimation.animation.SpringAnimation.SCALE_X, scale, com.google.android.material.R.attr.motionSpringFastSpatial)
            icon.springTo(androidx.dynamicanimation.animation.SpringAnimation.SCALE_Y, scale, com.google.android.material.R.attr.motionSpringFastSpatial)
            icon.springTo(androidx.dynamicanimation.animation.SpringAnimation.ALPHA, alpha, com.google.android.material.R.attr.motionSpringFastEffects)
        }
    }

    override fun onRecycle() {
        loadIconJob?.cancel()
        grantedLoadJob?.cancel()
        nameLoadJob?.cancel()
    }
}
