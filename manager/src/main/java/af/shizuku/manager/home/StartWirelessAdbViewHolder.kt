package af.shizuku.manager.home

import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbPairingTutorialActivity
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartWirelessAdbBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.receiver.NotifCancelReceiver
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.airbnb.mvrx.withState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartWirelessAdbViewHolder(
    private val binding: HomeStartWirelessAdbBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
    private val homeModel: HomeViewModel,
) : BaseViewHolder<ServiceStatus?>(containerBinding.root) {
    companion object {
        fun creator(
            scope: CoroutineScope,
            homeModel: HomeViewModel,
        ): Creator<ServiceStatus?> =
            Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartWirelessAdbBinding.inflate(inflater, outer.cardContent, true)
                StartWirelessAdbViewHolder(inner, outer, scope, homeModel)
            }

        fun start(
            context: android.content.Context,
            scope: CoroutineScope,
            discoveredPort: Int = -1,
        ) {
            scope.launch {
                val sysPropPort = EnvironmentUtils.getAdbTcpPort()
                val activeLoopbackPort =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        af.shizuku.manager.adb.AdbPortProber
                            .findActiveLoopbackPort(context)
                    }
                val validTcpPort =
                    when {
                        discoveredPort in 1..65535 -> discoveredPort
                        activeLoopbackPort in 1..65535 -> activeLoopbackPort
                        sysPropPort in 1..65535 -> sysPropPort
                        else -> -1
                    }
                // If the port is confirmed active or TLS-discovered, start immediately; otherwise
                // open the discovery dialog so mDNS can resolve the current port instead of
                // connecting to a stale cached port.
                if (validTcpPort > 0) {
                    val intent =
                        android.content.Intent(context, StarterActivity::class.java).apply {
                            putExtra(StarterActivity.EXTRA_PORT, validTcpPort)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(intent)
                } else {
                    val fm = (context as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
                    if (fm != null) {
                        // The port probe above suspends; the activity may have been stopped or
                        // destroyed meanwhile, and show() would throw IllegalStateException.
                        if (!fm.isStateSaved && !fm.isDestroyed) AdbDialogFragment().show(fm)
                    } else {
                        val lastPort = ShizukuSettings.getLastPort()
                        if (lastPort in 1..65535) {
                            val intent =
                                android.content.Intent(context, StarterActivity::class.java).apply {
                                    putExtra(StarterActivity.EXTRA_PORT, lastPort)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            context.startActivity(intent)
                        }
                    }
                }
            }
        }
    }

    private val originalIcon = binding.icon.drawable

    init {
        HomeEditMode.bindCardGestures(containerBinding, this)
        binding.button1.setOnClickListener { _: View ->
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                Toast.makeText(context, context.getString(R.string.toast_shizuku_already_starting), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))

            scope.launch {
                // Off the main thread so the shell fallback (Android 17) lands before the check below.
                if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        af.shizuku.manager.utils.SettingsHelper
                            .putGlobalSafely(context, Settings.Global.ADB_ENABLED, 1)
                        af.shizuku.manager.utils.SettingsHelper
                            .putGlobalSafely(context, "adb_allowed_connection_time", 0L)
                    }
                }
                if (!EnvironmentUtils.isAdbEnabled()) {
                    WadbEnableUsbDebuggingDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                    return@launch
                }
                val sysPropPort = EnvironmentUtils.getAdbTcpPort()
                val discoveredPort = withState(homeModel) { it.discoveredAdbPort }
                val tcpMode = ShizukuSettings.getTcpMode()
                val lastPort = ShizukuSettings.getLastPort()

                // Fast active probe on loopback (127.0.0.1:5555, lastPort, etc.)
                // This enables 1-tap start on 5G/cellular without Wi-Fi when TCP mode is active.
                val activeLoopbackPort =
                    af.shizuku.manager.adb.AdbPortProber
                        .findActiveLoopbackPort(context)

                val livePort =
                    when {
                        activeLoopbackPort in 1..65535 -> activeLoopbackPort
                        sysPropPort in 1..65535 -> sysPropPort
                        discoveredPort in 1..65535 -> discoveredPort
                        else -> -1
                    }
                val validPort =
                    if (livePort > 0) {
                        livePort
                    } else if (lastPort in 1..65535) {
                        lastPort
                    } else {
                        -1
                    }

                if (validPort <= 0 && !EnvironmentUtils.isTlsSupported()) {
                    WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                } else if (validPort <= 0) {
                    AdbDialogFragment.forUserGesture().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                } else if (sysPropPort > 0 && !tcpMode && activeLoopbackPort <= 0) {
                    // A TCP-mode connection is active but the user wants TLS. Stop TCP first, then
                    // open the dialog so mDNS can rediscover the (different) TLS port.
                    AdbStarter.stopTcp(context, sysPropPort)
                    AdbDialogFragment.forUserGesture().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                } else if (livePort > 0) {
                    // Live port confirmed: loopback responsive, TCP active, or TLS already resolved by mDNS.
                    // Skip the intermediate dialog — go straight to the connect flow.
                    val intent =
                        Intent(context, StarterActivity::class.java).apply {
                            putExtra(StarterActivity.EXTRA_PORT, livePort)
                            putExtra(StarterActivity.EXTRA_USER_GESTURE, true)
                        }
                    val activity = context.asActivity<android.app.Activity>()
                    if (activity != null) {
                        activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
                    } else {
                        context.startActivity(intent)
                    }
                } else if (tcpMode) {
                    // Only a stale cached port in TCP mode — try it directly (TCP port is stable).
                    val intent =
                        Intent(context, StarterActivity::class.java).apply {
                            putExtra(StarterActivity.EXTRA_PORT, lastPort)
                            putExtra(StarterActivity.EXTRA_USER_GESTURE, true)
                        }
                    context.startActivity(intent)
                } else {
                    // Only a stale cached TLS port — open dialog so mDNS can rediscover the current port.
                    AdbDialogFragment.forUserGesture().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                }
            }
        }

        if (EnvironmentUtils.isTlsSupported()) {
            binding.button3.setOnClickListener { v: View ->
                CustomTabsHelper.launchUrlOrCopy(v.context, Helps.ADB_ANDROID11.get())
            }
            binding.button2.setOnClickListener { v: View ->
                onPairClicked(v.context)
            }
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text =
                context
                    .getString(R.string.home_wireless_adb_description)
                    .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        } else {
            binding.text1.text =
                context
                    .getString(R.string.home_wireless_adb_description_pre_11)
                    .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            binding.button2.isVisible = false
            binding.button3.isVisible = false
        }
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_start_wireless_adb")

        val runningViaAdb = data?.isRunning == true && data?.uid != 0
        // "Start via Wireless debugging" reads wrong while it's already running that way.
        binding.title.setText(if (runningViaAdb) R.string.home_wireless_adb_title_active else R.string.home_wireless_adb_title)
        if (EnvironmentUtils.isTlsSupported()) {
            if (runningViaAdb) {
                binding.button1.setText(R.string.home_wireless_adb_button_reconnect)
                binding.text1.text =
                    context
                        .getString(R.string.home_wireless_adb_description_active)
                        .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            } else {
                binding.button1.setText(R.string.home_root_button_start)
                binding.text1.text =
                    context
                        .getString(R.string.home_wireless_adb_description)
                        .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
            return
        }
        val activity = context.asActivity<FragmentActivity>() ?: return
        if (!af.shizuku.manager.adb.AdbPairingClient
                .isNativeAvailable()
        ) {
            val detail =
                af.shizuku.manager.adb.AdbPairingClient.nativeLoadError
                    ?.let { "\n\n($it)" }
                    .orEmpty()
            com.google.android.material.dialog
                .MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.adb_pairing_fallback_title)
                .setMessage(activity.getString(R.string.adb_pairing_fallback_message) + detail)
                .setPositiveButton(R.string.adb_pairing_fallback_enable_tcp) { _, _ ->
                    ShizukuSettings.setTcpMode(true)
                    ShizukuSettings.setTcpPort(5555)
                    Toast
                        .makeText(
                            activity,
                            activity.getString(R.string.recommendation_tcp_mode_enabled_toast),
                            Toast.LENGTH_SHORT,
                        ).show()
                    AdbDialogFragment.forUserGesture().show(activity.supportFragmentManager)
                }.setNeutralButton(R.string.home_adb_button_view_command) { _, _ ->
                    StartAdbViewHolder.showViewCommandDialog(activity)
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        // AdbPairingTutorialActivity provides a dedicated pairing flow: it starts the pairing
        // service, shows step-by-step instructions, handles notification permission, and
        // auto-dismisses once Shizuku is running.
        val intent = Intent(context, AdbPairingTutorialActivity::class.java)
        activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
    }
}
