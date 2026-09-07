package af.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.databinding.AdbDialogBinding
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.NetworkStateHelper
import af.shizuku.manager.utils.SettingsPage

@RequiresApi(Build.VERSION_CODES.R)
class AdbDialogFragment : DialogFragment() {

    private lateinit var binding: AdbDialogBinding
    private lateinit var adbMdns: AdbMdns
    private val port = MutableLiveData<Int>()
    private var probeJob: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbDialogBinding.inflate(layoutInflater)
        adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
            port.postValue(it)
        }

        val builder = MaterialAlertDialogBuilder(context).apply {
            setTitle(R.string.dialog_adb_discovery)
            setView(binding.root)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.development_settings, null)

            // Samsung Specific: Launch in Pop-up mode
            if (EnvironmentUtils.isSamsung()) {
                setNeutralButton(R.string.adb_dialog_popup_settings) { _, _ ->
                    val intent = SettingsPage.Developer.WirelessDebugging.buildIntent(context).apply {
                        // Samsung specific flags for Pop-up window
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        putExtra("android.intent.extra.WINDOW_MODE", 5) // WINDOW_MODE_FREEFORM
                        putExtra("com.samsung.android.intent.extra.LAUNCH_MODE", 4)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        SettingsPage.Developer.WirelessDebugging.launch(context)
                    }
                }
            }
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        adbMdns.stop()
        probeJob?.cancel()
    }

    private fun onDialogShow(dialog: AlertDialog) {
        adbMdns.start()
        val context = dialog.context
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)

        val isSamsung = EnvironmentUtils.isSamsung()
        val isWifi = NetworkStateHelper.isWifiConnected(context)
        val isHotspot = !isSamsung && NetworkStateHelper.isHotspotEnabled(context)
        if (!isWifi && !isHotspot) {
            binding.wifiWarningLayout.isVisible = true
            if (isSamsung) {
                binding.hotspotTipText.setText(R.string.dialog_adb_samsung_hotspot_warning)
                binding.btnOpenHotspot.isVisible = false
            } else {
                binding.hotspotTipText.setText(R.string.dialog_adb_hotspot_tip)
                binding.btnOpenHotspot.isVisible = true
                binding.btnOpenHotspot.setOnClickListener {
                    val intent = Intent().apply {
                        action = Settings.ACTION_WIRELESS_SETTINGS
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent("android.settings.TETHER_SETTINGS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {
                            SettingsPage.Developer.WirelessDebugging.launch(context)
                        }
                    }
                }
            }
        } else {
            binding.wifiWarningLayout.isVisible = false
        }

        val isAutoPairEnabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("AdbPairingAccessibilityService") == true

        if (isAutoPairEnabled) {
            binding.autoPairStatusText.isVisible = true
            binding.btnPairDevice.isVisible = false
        } else {
            binding.btnPairDevice.isVisible = true
            binding.autoPairStatusText.isVisible = false
        }

        binding.btnPairDevice.setOnClickListener {
            dismiss()
            AdbPairDialogFragment().show(parentFragmentManager, null)
        }

        // Active loopback probe: checks local TCP port (5555, lastPort, etc.)
        // without waiting for mDNS, allowing instantaneous connection on 5G/cellular.
        binding.probeStatusText.isVisible = true
        binding.probeStatusText.setText(R.string.dialog_adb_probing_loopback)
        probeJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val activePort = AdbPortProber.findActiveLoopbackPort(context)
                if (activePort in 1..65535) {
                    withContext(Dispatchers.Main) {
                        binding.probeStatusText.text = getString(R.string.dialog_adb_loopback_found, activePort)
                        startAndDismiss(activePort)
                    }
                    break
                }
                delay(1000)
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
        }

        port.observe(this) {
            if (it > 65535 || it < 1) return@observe
            port.removeObservers(this)
            probeJob?.cancel()
            startAndDismiss(it)
        }
    }

    private fun startAndDismiss(port: Int) {
        val intent = Intent(context, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_PORT, port)
        }
        requireContext().startActivity(intent)

        dismissAllowingStateLoss()
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }
}
