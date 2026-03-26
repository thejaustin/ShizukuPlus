package moe.shizuku.manager.settings

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityServiceDoctorBinding
import moe.shizuku.manager.databinding.ItemDoctorCheckBinding
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
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
            getString(R.string.doctor_check_secure_settings),
            if (hasSecureSettings) getString(R.string.doctor_status_ok) else getString(R.string.doctor_status_not_enabled),
            hasSecureSettings,
            onFix = if (!hasSecureSettings) { { SettingsHelper.promptWriteSecureSettings(this) } } else null
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
                "Samsung Auto Blocker",
                if (oneUi >= 6) "Needs Manual Check" else getString(R.string.doctor_status_ok),
                oneUi < 6,
                onFix = if (oneUi >= 6) { { SettingsPage.Samsung.AutoBlocker.launch(this) } } else null
            ))

            // Samsung Device Care / Always sleeping apps
            checks.add(DoctorCheck(
                "Samsung Battery Protection",
                "Review",
                true,
                onFix = { SettingsPage.Samsung.DeviceCareBattery.launch(this) }
            ))

            if (oneUi >= 6) {
                tips.add("• **Samsung Auto Blocker**: Ensure it is turned **OFF** or that 'Maximum Restrictions' is disabled. It silently blocks ADB commands on One UI 7/8+.")
                tips.add("• **One UI 7/8 Connectivity**: If Wireless Pairing fails, try using **Split Screen mode** with Shizuku and Developer Options open simultaneously.")
                tips.add("• **S22 Ultra Tip**: For consistent ADB, dial `*#0808#` and select `MTP + ADB` if you encounter connection drops.")
            }
        }

        // 8. Secure Folder / Secondary User detection
        if (EnvironmentUtils.isSecondaryUser()) {
            checks.add(DoctorCheck(
                "Secondary User / Secure Folder",
                "Detected",
                false
            ))
            tips.add("• **Secure Folder Detected**: Shizuku works best in the Main User (Owner). Running inside Secure Folder or a Work Profile may require starting the server from the Main User first.")
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
                "Phantom Process Killer",
                "Needs Manual Check",
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
            tips.add("• **Phantom Process Killer**: Android 12-15+ may kill Shizuku if too many commands are run. The 'Fix' button attempts to disable this limit.")
        }

        // 11. Samsung Auto Restart & Sleeping Apps
        if (EnvironmentUtils.isSamsung()) {
            tips.add("• **Samsung Auto-Optimization**: Disable 'Auto Restart' in Device Care to prevent Shizuku from being killed at night.")
            tips.add("• **Samsung Sleeping Apps**: Ensure Shizuku+ is added to the **'Never sleeping apps'** list in *Device Care > Battery > Background usage limits* to prevent background service termination.")
            
            val board = Build.HARDWARE.lowercase()
            if (board.contains("exynos")) {
                tips.add("• **S22 Ultra (Exynos)**: If service response is sluggish, try disabling **'RAM Plus'** in Device Care > Memory.")
            } else if (board.contains("qcom") || board.contains("snapdragon")) {
                tips.add("• **S22 Ultra (Snapdragon)**: Ensure 'Enhanced Processing' is enabled in Quick Settings for maximum command throughput.")
            }
        }

        checkListAdapter.submitList(checks)
        tipsTextView.text = if (tips.isEmpty()) {
            "Your system seems well-configured for Shizuku+."
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
            holder.binding.title.text = check.title
            holder.binding.status.text = check.status
            holder.binding.icon.setImageResource(if (check.ok) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp)
            holder.binding.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (check.ok) getColor(R.color.status_ok) else getColor(R.color.status_error)
            )
            
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
