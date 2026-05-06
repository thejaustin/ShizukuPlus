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
            if (validTcpPort > 0 && ShizukuSettings.getTcpMode()) {
                val intent = android.content.Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_PORT, validTcpPort)
                }
                context.startActivity(intent)
            } else {
                (context as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.let { fm ->
                    AdbDialogFragment().show(fm)
                }
            }
        }
    }

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
            val tcpPort = if (sysPropPort in 1..65535) sysPropPort else discoveredPort
            val tcpMode = ShizukuSettings.getTcpMode()
            val lastPort = ShizukuSettings.getLastPort()
            val validTcpPort = when {
                tcpPort in 1..65535 -> tcpPort
                lastPort in 1..65535 -> lastPort
                else -> -1
            }

            if (validTcpPort <= 0 && !EnvironmentUtils.isTlsSupported()) {
                WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else if (validTcpPort <= 0) {
                AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else if (!tcpMode) {
                scope.launch {
                    AdbStarter.stopTcp(context, validTcpPort)
                }
                AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else {
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_PORT, validTcpPort)
                }
                val activity = context.asActivity<android.app.Activity>()
                if (activity != null) {
                    activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
                } else {
                    context.startActivity(intent)
                }
            }
        }

        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartWirelessAdbViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
        containerBinding.removeBtn.setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(HomeAdapter.ID_START_WADB)
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
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
        } else if ((context.display?.displayId ?: -1) > 0 || ShizukuSettings.getLegacyPairing()) {
            AdbPairDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        } else {
            val activity = context.asActivity<android.app.Activity>() ?: return
            activity.startWithSceneTransition(
                Intent(activity, AdbPairingTutorialActivity::class.java),
                binding.icon, "icon_wireless_adb"
            )
        }
    }
}
