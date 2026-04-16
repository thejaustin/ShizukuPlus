package af.shizuku.manager.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.app.AppBarActivity
import af.shizuku.manager.databinding.ActivityServiceDoctorBinding
import af.shizuku.manager.databinding.ItemDoctorCheckBinding
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

class ServiceDoctorActivity : AppBarActivity() {

    private lateinit var checkListAdapter: CheckListAdapter
    private lateinit var tipsTextView: TextView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val batteryOptimizationListener = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        runDiagnostics()
    }

    override fun getLayoutId() = R.layout.activity_service_doctor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityServiceDoctorBinding.bind(rootView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tipsTextView = binding.tipsText

        val scrollView = binding.checkList.parent?.parent as? View ?: rootView
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        binding.checkList.layoutManager = LinearLayoutManager(this)
        checkListAdapter = CheckListAdapter()
        binding.checkList.adapter = checkListAdapter

        runDiagnostics()
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

    private fun runDiagnostics() {
        val checks = mutableListOf<DoctorCheck>()
        val tips = mutableListOf<String>()

        // 1. Battery Optimization
        val isIgnoring = SettingsHelper.isIgnoringBatteryOptimizations(this)
        checks.add(DoctorCheck(
            getString(R.string.doctor_check_battery, ""),
            if (isIgnoring) getString(R.string.doctor_status_ok) else getString(R.string.doctor_status_optimized),
            isIgnoring,
            onFix = if (!isIgnoring) { { SettingsHelper.requestIgnoreBatteryOptimizations(this, batteryOptimizationListener) } } else null
        ))
        if (!isIgnoring) tips.add("• " + getString(R.string.doctor_tip_battery))

        // 2. Wireless ADB
        val adbPort = EnvironmentUtils.getAdbTcpPort()
        val adbOk = adbPort > 0
        checks.add(DoctorCheck(
            getString(R.string.doctor_check_adb, ""),
            if (adbOk) "${getString(R.string.doctor_status_ok)} ($adbPort)" else getString(R.string.doctor_status_not_enabled),
            adbOk
        ))
        if (!adbOk && !EnvironmentUtils.isRooted()) tips.add("• " + getString(R.string.doctor_tip_adb))

        // 3. Root
        val isRooted = EnvironmentUtils.isRooted()
        checks.add(DoctorCheck(
            getString(R.string.doctor_check_root, ""),
            if (isRooted) getString(R.string.doctor_status_ok) else getString(R.string.doctor_status_not_enabled),
            isRooted
        ))

        // 4. Shizuku Server
        val isRunning = ShizukuStateMachine.isRunning()
        checks.add(DoctorCheck(
            getString(R.string.doctor_check_server, ""),
            if (isRunning) getString(R.string.doctor_status_running) else getString(R.string.doctor_status_stopped),
            isRunning
        ))

        // 5. Secure Settings (WRITE_SECURE_SETTINGS)
        val hasSecureSettings = SettingsHelper.hasWriteSecureSettings(this)
        checks.add(DoctorCheck(
            getString(R.string.doctor_check_secure_settings, ""),
            if (hasSecureSettings) getString(R.string.doctor_status_ok) else getString(R.string.doctor_status_not_enabled),
            hasSecureSettings,
            onFix = if (!hasSecureSettings) { { SettingsHelper.promptWriteSecureSettings(this) } } else null
        ))

        // 5b. Accessibility (for AI automation features)
        val isAccessibilityEnabled = SettingsHelper.isAccessibilityServiceEnabled(this, AdbPairingAccessibilityService::class.java)
        checks.add(DoctorCheck(
            "Accessibility Service",
            if (isAccessibilityEnabled) getString(R.string.doctor_status_ok) else getString(R.string.doctor_status_not_enabled),
            isAccessibilityEnabled,
            onFix = if (!isAccessibilityEnabled) { { SettingsPage.Accessibility.launch(this) } } else null
        ))

        // 6. Xiaomi Restricted ADB
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi")) {
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_permission, ""),
                getString(R.string.doctor_status_limited),
                false
            ))
            tips.add("• " + getString(R.string.doctor_tip_xiaomi))
        }

        // 7. Samsung Auto Blocker (One UI 6.1+)
        if (EnvironmentUtils.isSamsung()) {
            val oneUi = EnvironmentUtils.getOneUiVersion()
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_samsung_autoblocker),
                if (oneUi >= 6) getString(R.string.doctor_status_manual_check) else getString(R.string.doctor_status_ok),
                oneUi < 6,
                onFix = if (oneUi >= 6) { { SettingsPage.Samsung.AutoBlocker.launch(this) } } else null
            ))

            // Samsung Device Care / Always sleeping apps
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_samsung_battery_protection),
                getString(R.string.doctor_status_review) + " (Check Sleeping Apps)",
                true,
                onFix = { SettingsPage.Samsung.BackgroundUsageLimits.launch(this) }
            ))

            if (oneUi >= 6) {
                tips.add("• " + getString(R.string.doctor_tip_samsung_autoblocker))
                tips.add("• " + getString(R.string.doctor_tip_oneui_connectivity))
                tips.add("• " + getString(R.string.doctor_tip_s22_ultra))
            }
        }

        // 8. Secure Folder / Secondary User detection
        if (EnvironmentUtils.isSecondaryUser()) {
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_secondary_user),
                getString(R.string.doctor_status_detected),
                false
            ))
            tips.add("• " + getString(R.string.doctor_tip_secure_folder))
        }

        // 9. Background Limits (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_background, ""),
                getString(R.string.doctor_status_ok),
                true
            ))
        }

        // 10. Phantom Process Killer (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_phantom_process),
                getString(R.string.doctor_status_manual_check),
                true,
                onFix = {
                    serviceScope.launch {
                        try {
                            // Try to disable it via Shizuku if running
                            if (ShizukuStateMachine.isRunning()) {
                                Shizuku.newProcess(arrayOf("device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"), null, null).waitFor()
                                withContext(Dispatchers.Main) { Toast.makeText(this@ServiceDoctorActivity, "Attempted to disable Phantom Killer", Toast.LENGTH_SHORT).show() }
                            } else {
                                withContext(Dispatchers.Main) { Toast.makeText(this@ServiceDoctorActivity, "Service must be running to auto-fix", Toast.LENGTH_SHORT).show() }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { Toast.makeText(this@ServiceDoctorActivity, "Fix failed: ${e.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            ))
            tips.add("• " + getString(R.string.doctor_tip_phantom_process))
        }

        // 11. Samsung Auto Restart & Sleeping Apps
        if (EnvironmentUtils.isSamsung()) {
            tips.add("• " + getString(R.string.doctor_tip_samsung_optimization))
            tips.add("• " + getString(R.string.doctor_tip_samsung_sleeping))
            
            val board = Build.HARDWARE.lowercase()
            if (board.contains("exynos")) {
                tips.add("• " + getString(R.string.doctor_tip_s22_ultra_exynos))
            } else if (board.contains("qcom") || board.contains("snapdragon")) {
                tips.add("• " + getString(R.string.doctor_tip_s22_ultra_snapdragon))
            }
        }

        checkListAdapter.submitList(checks)
        tipsTextView.text = if (tips.isEmpty()) {
            getString(R.string.doctor_system_well_configured)
        } else {
            tips.joinToString("\n\n")
        }
    }

    private data class DoctorCheck(
        val title: String,
        val status: String,
        val ok: Boolean,
        val onFix: (() -> Unit)? = null
    )

    private inner class CheckListAdapter : RecyclerView.Adapter<CheckViewHolder>() {
        private var items = emptyList<DoctorCheck>()

        fun submitList(newItems: List<DoctorCheck>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckViewHolder {
            return CheckViewHolder(ItemDoctorCheckBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: CheckViewHolder, position: Int) {
            val check = items[position]
            val context = holder.itemView.context
            holder.binding.title.text = check.title
            holder.binding.status.text = check.status
            holder.binding.icon.setImageResource(if (check.ok) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp)
            val tv = android.util.TypedValue()
            val colorAttr = if (check.ok) com.google.android.material.R.attr.colorTertiary else android.R.attr.colorError
            context.theme.resolveAttribute(colorAttr, tv, true)
            holder.binding.icon.imageTintList = android.content.res.ColorStateList.valueOf(tv.data)
            
            if (check.onFix != null) {
                holder.binding.btnFix.visibility = View.VISIBLE
                holder.binding.btnFix.setOnClickListener { check.onFix.invoke() }
            } else {
                holder.binding.btnFix.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
    }

    private class CheckViewHolder(val binding: ItemDoctorCheckBinding) : RecyclerView.ViewHolder(binding.root)
}
