package moe.shizuku.manager.settings

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import moe.shizuku.manager.app.SnackbarHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine

class BehaviorSettingsFragment : BaseSettingsFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var watchdogPreference: TwoStatePreference
    private lateinit var tcpModePreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpModePreference.icon = maybeGetRestartIcon(KEY_TCP_MODE)
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT)
        }
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
        val context = requireContext()

        startOnBootPreference = findPreference(KEY_START_ON_BOOT)!!
        watchdogPreference = findPreference(KEY_WATCHDOG)!!
        tcpModePreference = findPreference(KEY_TCP_MODE)!!
        tcpPortPreference = findPreference(KEY_TCP_PORT)!!

        startOnBootPreference.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() ||
                EnvironmentUtils.isRooted()
            ) {
                isChecked = ShizukuSettings.getStartOnBoot(context)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val doToggle = {
                            maybeToggleBatterySensitiveSetting(newValue) { result ->
                                if (result) {
                                    ShizukuSettings.setStartOnBoot(context, newValue)
                                    isChecked = ShizukuSettings.getStartOnBoot(context)
                                }
                            }
                        }
                        // https://r.android.com/2128832
                        if (newValue &&
                            !EnvironmentUtils.isTelevision() &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ) {
                            MaterialAlertDialogBuilder(context)
                                .setTitle(android.R.string.dialog_alert_title)
                                .setMessage(R.string.settings_start_on_boot_bug)
                                .setPositiveButton(android.R.string.ok) { _, _ -> doToggle() }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> isChecked = !newValue }
                                .show()
                        } else {
                            doToggle()
                        }
                    }
                    false
                }
            } else {
                isEnabled = false
                isChecked = false
                summary = context.getString(R.string.settings_start_on_boot_summary)
            }
        }

        watchdogPreference.apply {
            isChecked = ShizukuSettings.isWatchdogRunning()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        tcpModePreference.apply {
            if (EnvironmentUtils.isTlsSupported()) {
                summary = context.getString(R.string.settings_tcp_mode_summary)
                icon = maybeGetRestartIcon(KEY_TCP_MODE)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val applyChange: () -> Unit = {
                            ShizukuSettings.setTcpMode(newValue)
                            isChecked = newValue
                            isEnabled = true
                            summary = context.getString(R.string.settings_tcp_mode_summary)
                            icon = maybeGetRestartIcon(KEY_TCP_MODE)
                            syncTcpPortVisibility()
                        }
                        if (!newValue && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, newValue)) {
                            promptStopTcp(tcpModePreference) { applyChange() }
                        } else {
                            maybePromptRestart(KEY_TCP_MODE, newValue) { applyChange() }
                        }
                    }
                    false
                }
            } else if (EnvironmentUtils.isTelevision()) {
                isEnabled = false
                isChecked = true
            } else {
                isVisible = false
            }
        }

        tcpPortPreference.apply {
            syncTcpPortVisibility()
            icon = maybeGetRestartIcon(KEY_TCP_PORT)
            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
            }
            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT)
                    }
                    maybePromptRestart(KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }
    }

    private fun syncTcpPortVisibility() {
        tcpPortPreference.isVisible = tcpModePreference.isVisible && tcpModePreference.isChecked
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onPause() {
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == KEY_WATCHDOG) watchdogPreference.isChecked = ShizukuSettings.isWatchdogRunning()
    }
}
