package moe.shizuku.manager.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine

class ServiceDoctorActivity : AppBarActivity() {

    private lateinit var checkListAdapter: CheckListAdapter
    private lateinit var tipsTextView: TextView

    private val batteryOptimizationListener = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        runDiagnostics()
    }

    override fun getLayoutId() = R.layout.activity_service_doctor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView = findViewById<RecyclerView>(R.id.check_list)
        tipsTextView = findViewById(R.id.tips_text)

        val scrollView = recyclerView.parent?.parent as? View ?: rootView
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        checkListAdapter = CheckListAdapter()
        recyclerView.adapter = checkListAdapter

        runDiagnostics()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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

        // 5. Xiaomi Restricted ADB
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi")) {
            // We can't easily check the security setting programmatically, but we can warn
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_permission, ""),
                getString(R.string.doctor_status_limited),
                false
            ))
            tips.add("• " + getString(R.string.doctor_tip_xiaomi))
        }

        // 6. Background Limits (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            checks.add(DoctorCheck(
                getString(R.string.doctor_check_background, ""),
                getString(R.string.doctor_status_ok),
                true
            ))
        }

        checkListAdapter.submitList(checks)
        tipsTextView.text = if (tips.isEmpty()) "Your system seems well-configured for Shizuku+." else tips.joinToString("

")
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
            return CheckViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_doctor_check, parent, false))
        }

        override fun onBindViewHolder(holder: CheckViewHolder, position: Int) {
            val check = items[position]
            holder.title.text = check.title
            holder.status.text = check.status
            holder.icon.setImageResource(if (check.ok) R.drawable.ic_server_ok_24dp else R.drawable.ic_server_error_24dp)
            holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (check.ok) getColor(R.color.status_ok) else getColor(R.color.status_error)
            )
            
            if (check.onFix != null) {
                holder.btnFix.visibility = View.VISIBLE
                holder.btnFix.setOnClickListener { check.onFix.invoke() }
            } else {
                holder.btnFix.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
    }

    private class CheckViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
        val status: TextView = view.findViewById(R.id.status)
        val btnFix: Button = view.findViewById(R.id.btn_fix)
    }
}
