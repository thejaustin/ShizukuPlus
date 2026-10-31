package af.shizuku.manager.settings

import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.LocalNetworkPermission
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.databinding.ActivityServiceDoctorBinding
import af.shizuku.manager.databinding.ItemDoctorCheckBinding
import af.shizuku.manager.doctor.ServiceDoctor
import af.shizuku.manager.home.tryEnableAccessibilityAutomatically
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

class ServiceDoctorActivity : AppBarActivity() {
    private lateinit var binding: ActivityServiceDoctorBinding
    private lateinit var checkListAdapter: CheckListAdapter
    private lateinit var tipsTextView: TextView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val batteryOptimizationListener =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            runDiagnostics()
        }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            runDiagnostics()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityServiceDoctorBinding.inflate(layoutInflater, rootView, true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tipsTextView = binding.tipsText

        binding.btnRerun.applySpringTouch()
        binding.btnRerun.setOnClickListener {
            HapticUtils.segmentTick(it)
            runDiagnostics()
        }

        binding.btnAutoHeal.applySpringTouch()
        binding.btnAutoHeal.setOnClickListener {
            HapticUtils.tap(it)
            performAutoHeal()
        }

        binding.switchAdaptiveAutoHeal.isChecked = ShizukuSettings.isAdaptiveDoctorAutoHealEnabled()
        binding.switchAdaptiveAutoHeal.setOnCheckedChangeListener { view, isChecked ->
            if (isChecked) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
            ShizukuSettings.setAdaptiveDoctorAutoHealEnabled(isChecked)
        }

        binding.rowHelperNotifications.setOnClickListener {
            HapticUtils.tap(it)
            pickHelperNotifications()
        }

        binding.checkList.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        binding.checkList.layoutManager = LinearLayoutManager(this)
        checkListAdapter = CheckListAdapter()
        binding.checkList.adapter = checkListAdapter

        runDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        // Also settable from Behavior › Notifications.
        showHelperNotificationsLevel()
    }

    private fun showHelperNotificationsLevel() {
        val values = resources.getStringArray(R.array.helper_notifications_values)
        val index = values.indexOf(ShizukuSettings.getHelperNotificationLevel()).coerceAtLeast(0)
        binding.textHelperNotifications.text = resources.getStringArray(R.array.helper_notifications)[index]
    }

    private fun pickHelperNotifications() {
        val values = resources.getStringArray(R.array.helper_notifications_values)
        val checked = values.indexOf(ShizukuSettings.getHelperNotificationLevel()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_helper_notifications)
            .setSingleChoiceItems(R.array.helper_notifications, checked) { dialog, which ->
                ShizukuSettings.setHelperNotificationLevel(values[which])
                af.shizuku.manager.utils.ShizukuIssues
                    .render(applicationContext)
                showHelperNotificationsLevel()
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /** Sets a secure setting to 0 (needs WRITE_SECURE_SETTINGS) and confirms it stuck. */
    private fun trySecureSettingOff(key: String): Boolean {
        if (!SettingsHelper.hasWriteSecureSettings(this)) return false
        return runCatching {
            android.provider.Settings.Secure
                .putInt(contentResolver, key, 0)
            android.provider.Settings.Secure
                .getInt(contentResolver, key, 1) == 0
        }.getOrDefault(false)
    }

    private fun createFixAction(item: ServiceDoctor.CheckItem): (() -> Unit)? {
        if (item.ok) return null
        return when (item.id) {
            "battery_optimization" -> {
                {
                    serviceScope.launch {
                        if (DeviceOptimizer.protectSelf(this@ServiceDoctorActivity)) {
                            Toast.makeText(this@ServiceDoctorActivity, R.string.doctor_fix_applied, Toast.LENGTH_SHORT).show()
                            runDiagnostics()
                        } else if (EnvironmentUtils.isSamsung()) {
                            DeviceOptimizer.snoozeNag(7)
                            SettingsPage.Samsung.BackgroundUsageLimits.launch(this@ServiceDoctorActivity)
                        } else {
                            SettingsHelper.requestIgnoreBatteryOptimizations(this@ServiceDoctorActivity, batteryOptimizationListener)
                        }
                    }
                }
            }
            "wireless_adb" -> {
                {
                    if (SettingsHelper.hasWriteSecureSettings(this)) {
                        runCatching {
                            android.provider.Settings.Global
                                .putInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 1)
                            android.provider.Settings.Global
                                .putInt(contentResolver, "adb_wifi_enabled", 1)
                        }
                        Toast.makeText(this, R.string.doctor_fix_applied, Toast.LENGTH_SHORT).show()
                        runDiagnostics()
                    } else {
                        SettingsPage.Developer.WirelessDebugging.launch(this)
                    }
                }
            }
            "local_network" -> {
                {
                    val perm = LocalNetworkPermission.required()
                    if (perm != null) {
                        serviceScope.launch {
                            if (DeviceOptimizer.grantSelf(this@ServiceDoctorActivity, perm)) {
                                Toast.makeText(this@ServiceDoctorActivity, R.string.doctor_fix_applied, Toast.LENGTH_SHORT).show()
                                runDiagnostics()
                            } else {
                                localNetworkPermissionLauncher.launch(perm)
                            }
                        }
                    }
                }
            }
            "shizuku_server" -> {
                {
                    Toast.makeText(this, R.string.doctor_fix_starting, Toast.LENGTH_SHORT).show()
                    af.shizuku.manager.receiver.ShizukuReceiverStarter
                        .start(this, forceStart = true)
                    serviceScope.launch {
                        delay(4000)
                        runDiagnostics()
                    }
                }
            }
            "secure_settings" -> {
                {
                    SettingsHelper.promptWriteSecureSettings(this)
                    runDiagnostics()
                }
            }
            "accessibility_service" -> {
                {
                    serviceScope.launch {
                        val ok = withContext(Dispatchers.IO) { tryEnableAccessibilityAutomatically() }
                        if (ok) {
                            Toast.makeText(this@ServiceDoctorActivity, R.string.doctor_fix_applied, Toast.LENGTH_SHORT).show()
                            runDiagnostics()
                        } else {
                            SettingsPage.Accessibility.launch(this@ServiceDoctorActivity)
                        }
                    }
                }
            }
            "device_hardening" -> {
                {
                    serviceScope.launch {
                        DeviceOptimizer.applyFixes(this@ServiceDoctorActivity)
                        runDiagnostics()
                    }
                }
            }
            "wallet_integrity" -> {
                { showWalletFixDialog() }
            }
            "xiaomi_adb" -> {
                { SettingsPage.Developer.Options.launch(this) }
            }
            "xiaomi_autostart" -> {
                { SettingsPage.Xiaomi.BatterySettings.launch(this) }
            }
            "oppo_permission" -> {
                { SettingsPage.Developer.Options.launch(this) }
            }
            "oppo_battery" -> {
                { SettingsPage.Oppo.BatterySettings.launch(this) }
            }
            "tcl_autostart" -> {
                { SettingsPage.TCL.AutoStart.launch(this) }
            }
            "samsung_autoblocker" -> {
                {
                    if (trySecureSettingOff("rampart_enabled")) {
                        Toast.makeText(this, R.string.doctor_fix_applied, Toast.LENGTH_SHORT).show()
                        runDiagnostics()
                    } else {
                        SettingsPage.Samsung.AutoBlocker.launch(this)
                    }
                }
            }
            "samsung_max_restrictions" -> {
                {
                    if (trySecureSettingOff("rampart_max_restrictions_enabled")) {
                        Toast.makeText(this, R.string.doctor_fix_applied, Toast.LENGTH_SHORT).show()
                        runDiagnostics()
                    } else {
                        SettingsPage.Samsung.AutoBlocker.launch(this)
                    }
                }
            }
            "su_bridge_a16" -> {
                {
                    startActivity(Intent(this, RootCompatibilityActivity::class.java))
                }
            }
            "background_limits" -> {
                {
                    SettingsHelper.requestIgnoreBatteryOptimizations(this, batteryOptimizationListener)
                }
            }
            "phantom_process" -> {
                {
                    serviceScope.launch {
                        try {
                            if (Shizuku.pingBinder()) {
                                val p =
                                    Shizuku.newProcess(
                                        arrayOf("device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"),
                                        null,
                                        null,
                                    )
                                val exitCode = withContext(Dispatchers.IO) { p?.waitFor() }
                                p?.destroy()
                                if (exitCode == 0) {
                                    Timber.i("Phantom process limit increased")
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@ServiceDoctorActivity, R.string.service_doctor_fix_phantom_attempted, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@ServiceDoctorActivity, R.string.service_doctor_fix_requires_service, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            val isNullProcess = e is IllegalStateException && e.message?.contains("null remote process") == true
                            Timber.w(e, "Phantom process fix failed")
                            withContext(Dispatchers.Main) {
                                val msg =
                                    if (isNullProcess && EnvironmentUtils.isSamsung()) {
                                        getString(R.string.service_doctor_fix_blocked_samsung)
                                    } else if (isNullProcess) {
                                        getString(R.string.service_doctor_fix_blocked_generic)
                                    } else {
                                        getString(R.string.service_doctor_fix_failed, e.message)
                                    }
                                Toast.makeText(this@ServiceDoctorActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            else -> null
        }
    }

    private fun showWalletFixDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.doctor_check_wallet_integrity)
            .setMessage(R.string.doctor_tip_wallet_integrity)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                serviceScope.launch {
                    val isMagiskMocking = ShizukuSettings.isRootMagiskMockingEnabled()
                    val isBusyboxMocking = ShizukuSettings.isRootBusyboxMockingEnabled()
                    val isSpoofing = ShizukuSettings.isSpoofDeviceEnabled()

                    val cleaned = RootCompatHelper.cleanupBridgeFromTmp(this@ServiceDoctorActivity)

                    if (isMagiskMocking) ShizukuSettings.setRootMagiskMockingEnabled(false)
                    if (isBusyboxMocking) ShizukuSettings.setRootBusyboxMockingEnabled(false)
                    if (ShizukuSettings.isSuBridgeEnabled()) ShizukuSettings.setSuBridgeEnabled(false)
                    if (isSpoofing) ShizukuSettings.setSpoofDeviceEnabled(false)
                    ShizukuSettings.syncAllPlusFeaturesToServer()

                    val refreshResult = RootCompatHelper.refreshGoogleWalletAttestation(this@ServiceDoctorActivity)

                    if (cleaned) {
                        val successMsg =
                            when (refreshResult) {
                                RootCompatHelper.WalletRefreshResult.CACHE_CLEARED_ROOT ->
                                    getString(R.string.doctor_fix_wallet_success)
                                RootCompatHelper.WalletRefreshResult.WALLET_CLEARED_PROCESSES_KILLED ->
                                    getString(R.string.doctor_fix_wallet_success_adb_mode)
                                RootCompatHelper.WalletRefreshResult.FORCE_STOPPED_ONLY ->
                                    getString(R.string.doctor_fix_wallet_success_no_shizuku)
                            }
                        MaterialAlertDialogBuilder(this@ServiceDoctorActivity)
                            .setTitle(R.string.doctor_check_wallet_integrity)
                            .setMessage(successMsg)
                            .setPositiveButton(R.string.doctor_action_open_wallet) { _, _ ->
                                try {
                                    val pm = packageManager
                                    val intent = pm.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel")
                                    if (intent != null) startActivity(intent)
                                } catch (_: Exception) {
                                }
                                runDiagnostics()
                            }.apply {
                                if (refreshResult != RootCompatHelper.WalletRefreshResult.CACHE_CLEARED_ROOT) {
                                    setNeutralButton(R.string.doctor_action_clear_gms_cache) { _, _ ->
                                        try {
                                            val intent =
                                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = android.net.Uri.parse("package:com.google.android.gms")
                                                }
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            Timber.w(e, "Could not open GMS settings")
                                        }
                                        runDiagnostics()
                                    }
                                }
                            }.show()
                    } else {
                        MaterialAlertDialogBuilder(this@ServiceDoctorActivity)
                            .setTitle(R.string.doctor_check_wallet_integrity)
                            .setMessage(getString(R.string.doctor_fix_wallet_failed) + "\n\n" + RootCompatHelper.ADB_CLEANUP_COMMAND)
                            .setPositiveButton(R.string.doctor_action_copy_adb_cmd) { _, _ ->
                                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("ADB Cleanup", RootCompatHelper.ADB_CLEANUP_COMMAND))
                                Toast.makeText(this@ServiceDoctorActivity, R.string.doctor_adb_cmd_copied, Toast.LENGTH_SHORT).show()
                                runDiagnostics()
                            }.setNegativeButton(android.R.string.cancel) { _, _ -> runDiagnostics() }
                            .show()
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runDiagnostics() {
        serviceScope.launch {
            val diag = withContext(Dispatchers.IO) { ServiceDoctor.runFullDiagnostics(this@ServiceDoctorActivity) }
            val checks =
                diag.checks.map { item ->
                    DoctorCheck(
                        title = item.title,
                        status = item.status,
                        ok = item.ok,
                        onFix = createFixAction(item),
                        topicId = item.id,
                    )
                }

            checkListAdapter.submitList(checks)

            val issueCount = diag.issuesCount
            val fixableCount = checks.count { !it.ok && it.onFix != null }

            if (fixableCount > 0) {
                binding.autoFixContainer.visibility = View.VISIBLE
                binding.btnAutoHeal.text = getString(R.string.doctor_action_auto_fix_all)
                binding.btnAutoHeal.isEnabled = true
            } else {
                binding.autoFixContainer.visibility = View.GONE
            }

            if (issueCount == 0) {
                binding.summaryTitle.text = getString(R.string.doctor_summary_healthy_title)
                binding.summarySubtitle.text = getString(R.string.doctor_summary_healthy_subtitle)
                binding.summaryIcon.setImageResource(R.drawable.ic_server_ok_24)
                binding.summaryIcon.imageTintList =
                    android.content.res.ColorStateList
                        .valueOf(themeColor(com.google.android.material.R.attr.colorTertiary))
            } else {
                binding.summaryTitle.text = getString(R.string.doctor_summary_issues_title, issueCount)
                binding.summarySubtitle.text = getString(R.string.doctor_summary_issues_subtitle)
                binding.summaryIcon.setImageResource(R.drawable.ic_server_error_24)
                binding.summaryIcon.imageTintList =
                    android.content.res.ColorStateList
                        .valueOf(themeColor(android.R.attr.colorError))
            }

            if (diag.tips.isEmpty()) {
                tipsTextView.text = getString(R.string.doctor_system_well_configured)
            } else {
                tipsTextView.text = diag.tips.joinToString("\n\n")
            }
        }
    }

    private fun performAutoHeal() {
        binding.btnAutoHeal.isEnabled = false
        binding.btnAutoHeal.text = getString(R.string.doctor_action_auto_fix_all_running)
        binding.autoFixProgress.visibility = View.VISIBLE

        serviceScope.launch {
            val report = ServiceDoctor.autoHeal(this@ServiceDoctorActivity)
            withContext(Dispatchers.Main) {
                binding.autoFixProgress.visibility = View.GONE
                HapticUtils.success(binding.btnAutoHeal)
                val msg =
                    if (report.resolvedCount > 0) {
                        getString(R.string.doctor_auto_heal_complete, report.resolvedCount)
                    } else {
                        getString(R.string.doctor_auto_heal_no_issues)
                    }
                Toast.makeText(this@ServiceDoctorActivity, msg, Toast.LENGTH_SHORT).show()
                runDiagnostics()
            }
        }
    }

    private data class DoctorCheck(
        val title: String,
        val status: String,
        val ok: Boolean,
        val onFix: (() -> Unit)? = null,
        val topicId: String? = null,
    )

    private inner class CheckListAdapter : RecyclerView.Adapter<CheckViewHolder>() {
        private var items = emptyList<DoctorCheck>()

        fun submitList(newItems: List<DoctorCheck>) {
            val diff =
                DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize() = items.size

                        override fun getNewListSize() = newItems.size

                        override fun areItemsTheSame(
                            o: Int,
                            n: Int,
                        ) = items[o].title == newItems[n].title

                        override fun areContentsTheSame(
                            o: Int,
                            n: Int,
                        ) = items[o] == newItems[n]
                    },
                )
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): CheckViewHolder = CheckViewHolder(ItemDoctorCheckBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(
            holder: CheckViewHolder,
            position: Int,
        ) {
            val check = items[position]
            val context = holder.itemView.context
            holder.binding.title.text = check.title
            holder.binding.status.text = check.status
            holder.binding.icon.setImageResource(if (check.ok) R.drawable.ic_server_ok_24 else R.drawable.ic_server_error_24)
            val colorAttr = if (check.ok) com.google.android.material.R.attr.colorTertiary else android.R.attr.colorError
            holder.binding.icon.imageTintList =
                android.content.res.ColorStateList
                    .valueOf(context.themeColor(colorAttr))

            val topic =
                check.topicId ?: when {
                    check.title.contains("battery", ignoreCase = true) || check.title.contains("sleeping", ignoreCase = true) || check.title.contains("autostart", ignoreCase = true) || check.title.contains("blocker", ignoreCase = true) -> af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_BATTERY_OEM
                    check.title.contains("adb", ignoreCase = true) || check.title.contains("network", ignoreCase = true) -> af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_WIRELESS_ADB
                    check.title.contains("su", ignoreCase = true) || check.title.contains("root", ignoreCase = true) || check.title.contains("wallet", ignoreCase = true) -> af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_ROOT_COMPAT
                    else -> af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_BATTERY_OEM
                }
            holder.itemView.setOnClickListener {
                af.shizuku.manager.utils.HapticUtils
                    .tap(it)
                af.shizuku.manager.knowledgebase.KnowledgebaseHelper
                    .show(context, topic)
            }

            if (check.onFix != null) {
                holder.binding.btnFix.visibility = View.VISIBLE
                holder.binding.btnFix.setOnClickListener { check.onFix.invoke() }
            } else {
                holder.binding.btnFix.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
    }

    private class CheckViewHolder(
        val binding: ItemDoctorCheckBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
