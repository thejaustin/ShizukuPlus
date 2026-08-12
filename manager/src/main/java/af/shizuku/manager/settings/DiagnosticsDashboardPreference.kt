package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class DiagnosticsDashboardPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_diagnostics_dashboard
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val container = holder.itemView as? LinearLayout ?: return
        val listContainer = holder.findViewById(R.id.diagnostics_list) as? LinearLayout ?: return
        val btnDisable = holder.findViewById(R.id.btn_disable_diagnostics)

        val sp = preferenceManager.sharedPreferences
        val isDisabledGlobal = sp?.getBoolean("diagnostics_enabled", true) == false

        if (isDisabledGlobal) {
            container.visibility = View.GONE
            return
        }

        val dismissed = sp?.getStringSet("diagnostics_dismissed", emptySet()) ?: emptySet()
        listContainer.removeAllViews()

        val activeWarnings = mutableListOf<WarningItem>()

        // Diagnostic 1: Dhizuku Mode enabled but Device Owner not active
        if (ShizukuSettings.isDhizukuModeEnabled() && !isDeviceOwnerActive(context)) {
            activeWarnings.add(
                WarningItem(
                    id = "dhizuku_not_owner",
                    messageRes = R.string.diagnostics_warning_dhizuku_owner
                )
            )
        }

        // Diagnostic 2: Shadow Binder enabled but no hidden packages
        if (ShizukuSettings.isShadowBinderEnabled() && ShizukuSettings.getShadowBinderHiddenPackages().isNullOrBlank()) {
            activeWarnings.add(
                WarningItem(
                    id = "shadow_binder_no_apps",
                    messageRes = R.string.diagnostics_warning_shadow_binder
                )
            )
        }

        // Diagnostic 3: Battery Optimization scan
        if (!isIgnoringBatteryOptimizations(context)) {
            activeWarnings.add(
                WarningItem(
                    id = "battery_optimization",
                    messageRes = R.string.diagnostics_warning_battery_optimization
                )
            )
        }

        val visibleWarnings = activeWarnings.filter { it.id !in dismissed }

        if (visibleWarnings.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(context)
        for (warning in visibleWarnings) {
            val itemView = inflater.inflate(R.layout.layout_diagnostic_item, listContainer, false)
            itemView.findViewById<TextView>(R.id.diagnostic_text).text = context.getString(warning.messageRes)

            // Set action handling on tapping the warning card itself
            itemView.setOnClickListener {
                when (warning.id) {
                    "battery_optimization" -> {
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_SETTINGS)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            } catch (anfe: Exception) {
                                Toast.makeText(context, R.string.diagnostics_open_battery_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "shadow_binder_no_apps" -> {
                        val activity = context as? androidx.fragment.app.FragmentActivity
                        val frag = activity?.supportFragmentManager
                            ?.findFragmentById(R.id.fragment_container)
                        val opened = if (frag is ShizukuPlusSettingsFragment) {
                            frag.findPreference<Preference>("shadow_binder_hidden_packages")?.let {
                                frag.onPreferenceTreeClick(it)
                            } != null
                        } else false
                        if (!opened) {
                            Toast.makeText(
                                context,
                                R.string.diagnostics_shadow_binder_select_apps_hint,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    "dhizuku_not_owner" -> {
                        val cmd = "adb shell dpm set-device-owner " +
                            "${context.packageName}/.admin.DhizukuAdminReceiver"
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.diagnostics_setup_device_owner_title)
                            .setMessage(context.getString(R.string.diagnostics_device_owner_message, cmd))
                            .setPositiveButton(R.string.diagnostics_copy_command) { _, _ ->
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText(context.getString(R.string.diagnostics_clipboard_label), cmd)
                                )
                                Toast.makeText(context, R.string.diagnostics_command_copied, Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(R.string.action_dismiss, null)
                            .show()
                    }
                }
            }

            itemView.findViewById<Button>(R.id.btn_dismiss_warning).setOnClickListener {
                val newDismissed = dismissed.toMutableSet().apply { add(warning.id) }
                sp?.edit()?.putStringSet("diagnostics_dismissed", newDismissed)?.apply()
                notifyChanged()
            }
            listContainer.addView(itemView)
        }

        btnDisable?.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.diagnostics_disable_title)
                .setMessage(R.string.diagnostics_disable_message)
                .setPositiveButton(R.string.diagnostics_disable_button) { _, _ ->
                    sp?.edit()?.putBoolean("diagnostics_enabled", false)?.apply()
                    notifyChanged()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun isDeviceOwnerActive(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            true // default to true to not raise warnings if system query fails
        }
    }

    private data class WarningItem(val id: String, val messageRes: Int)
}
