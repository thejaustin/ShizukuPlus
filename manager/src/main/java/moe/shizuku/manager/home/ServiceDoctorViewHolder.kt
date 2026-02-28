package moe.shizuku.manager.home

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeServiceDoctorBinding
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class ServiceDoctorViewHolder(private val binding: HomeServiceDoctorBinding, root: View) :
    BaseViewHolder<Unit>(root) {

    companion object {
        val CREATOR = Creator<Unit> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeServiceDoctorBinding.inflate(inflater, outer.root, true)
            ServiceDoctorViewHolder(inner, outer.root)
        }
    }

    init {
        itemView.setOnClickListener { showDiagnosticDialog() }
    }

    private fun showDiagnosticDialog() {
        val context = itemView.context
        val results = mutableListOf<String>()

        // 1. Battery Optimization
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            false
        }
        val batteryStatus = if (isOptimized) context.getString(R.string.doctor_status_optimized) else context.getString(R.string.doctor_status_ok)
        results.add(context.getString(R.string.doctor_check_battery, batteryStatus))

        // 2. Wireless ADB
        val adbEnabled = EnvironmentUtils.getAdbTcpPort() > 0
        val adbStatus = if (adbEnabled) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_not_enabled)
        results.add(context.getString(R.string.doctor_check_adb, adbStatus))

        // 3. Root
        val isRooted = EnvironmentUtils.isRooted()
        val rootStatus = if (isRooted) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_not_enabled)
        results.add(context.getString(R.string.doctor_check_root, rootStatus))

        // 4. ADB Permission Limitation (Simplified)
        // Some manufacturers limit adb permissions, we can guess this based on system properties if needed
        // For now, let's keep it simple or based on known manufacturer issues
        val manufacturer = Build.MANUFACTURER.lowercase()
        val permissionStatus = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("oppo") || manufacturer.contains("vivo") -> 
                context.getString(R.string.doctor_status_limited)
            else -> context.getString(R.string.doctor_status_ok)
        }
        results.add(context.getString(R.string.doctor_check_permission, permissionStatus))

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.doctor_dialog_title)
            .setItems(results.toTypedArray(), null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
