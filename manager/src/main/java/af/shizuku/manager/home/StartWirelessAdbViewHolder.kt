package af.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity

import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import af.shizuku.manager.Helps
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbPairingTutorialActivity
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartWirelessAdbBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.receiver.NotifCancelReceiver
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import com.airbnb.mvrx.withState
import af.shizuku.manager.utils.MotionUtils.applySpringTouch

class StartWirelessAdbViewHolder(
    private val binding: HomeStartWirelessAdbBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
    private val homeModel: HomeViewModel
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        fun creator(scope: CoroutineScope, homeModel: HomeViewModel): Creator<Any> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartWirelessAdbBinding.inflate(inflater, outer.cardContent, true)
                StartWirelessAdbViewHolder(inner, outer, scope, homeModel)
            }
        }

        fun start(context: android.content.Context, scope: CoroutineScope, discoveredPort: Int = -1) {
            val sysPropPort = EnvironmentUtils.getAdbTcpPort()
            val tcpPort = if (sysPropPort in 1..65535) sysPropPort else discoveredPort
            val lastPort = ShizukuSettings.getLastPort()
            val validTcpPort = when {
                tcpPort in 1..65535 -> tcpPort
                lastPort in 1..65535 -> lastPort
                else -> -1
            }
            // If the port is already known (TLS-discovered or TCP mode), start immediately.
            // This path is taken from the mDNS notification, where the port was already resolved.
            if (validTcpPort > 0) {
                val intent = android.content.Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_PORT, validTcpPort)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                (context as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.let { fm ->
                    AdbDialogFragment().show(fm)
                }
            }
        }
    }

    private val originalIcon = binding.icon.drawable

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        binding.button1.setOnClickListener { v: View ->
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
                Toast.makeText(context, context.getString(R.string.toast_shizuku_already_starting), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))

            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }

            val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
            if (adbEnabled == 0) {
                WadbEnableUsbDebuggingDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                return@setOnClickListener
            }

            val sysPropPort = EnvironmentUtils.getAdbTcpPort()
            val discoveredPort = withState(homeModel) { it.discoveredAdbPort }
            val tcpMode = ShizukuSettings.getTcpMode()
            val lastPort = ShizukuSettings.getLastPort()

            // livePort: a port we know is currently active — either the TCP sysprop (set while
            // TCP mode is on) or the TLS port just resolved by mDNS discovery in HomeViewModel.
            // lastPort is a *cached* port from the previous session and may be stale.
            val livePort = when {
                sysPropPort in 1..65535 -> sysPropPort
                discoveredPort in 1..65535 -> discoveredPort
                else -> -1
            }
            val validPort = if (livePort > 0) livePort else if (lastPort in 1..65535) lastPort else -1

            if (validPort <= 0 && !EnvironmentUtils.isTlsSupported()) {
                WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else if (validPort <= 0) {
                AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else if (sysPropPort > 0 && !tcpMode) {
                // A TCP-mode connection is active but the user wants TLS. Stop TCP first, then
                // open the dialog so mDNS can rediscover the (different) TLS port.
                scope.launch {
                    AdbStarter.stopTcp(context, sysPropPort)
                }
                AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else if (livePort > 0) {
                // Live port confirmed: either TCP active or TLS already resolved by mDNS.
                // Skip the intermediate dialog — go straight to the connect flow.
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_PORT, livePort)
                }
                val activity = context.asActivity<android.app.Activity>()
                if (activity != null) {
                    activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
                } else {
                    context.startActivity(intent)
                }
            } else if (tcpMode) {
                // Only a stale cached port in TCP mode — try it directly (TCP port is stable).
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_PORT, lastPort)
                }
                context.startActivity(intent)
            } else {
                // Only a stale cached TLS port — open dialog so mDNS can rediscover the current port.
                AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            }
        }

        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartWirelessAdbViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }


        if (EnvironmentUtils.isTlsSupported()) {
            binding.button3.setOnClickListener { v: View ->
                CustomTabsHelper.launchUrlOrCopy(v.context, Helps.ADB_ANDROID11.get())
            }
            binding.button2.setOnClickListener { v: View ->
                onPairClicked(v.context)
            }
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text = context.getString(R.string.home_wireless_adb_description)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        } else {
            binding.text1.text = context.getString(R.string.home_wireless_adb_description_pre_11)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            binding.button2.isVisible = false
            binding.button3.isVisible = false
        }
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_start_wireless_adb")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
            return
        }
        // AdbPairingTutorialActivity provides a dedicated pairing flow: it starts the pairing
        // service, shows step-by-step instructions, handles notification permission, and
        // auto-dismisses once Shizuku is running.
        val activity = context.asActivity<FragmentActivity>() ?: return
        val intent = Intent(context, AdbPairingTutorialActivity::class.java)
        activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
    }
}
