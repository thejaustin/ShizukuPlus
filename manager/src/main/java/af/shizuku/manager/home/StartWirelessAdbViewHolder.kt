package af.shizuku.manager.home
import timber.log.Timber

import android.Manifest
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
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import af.shizuku.manager.Helps
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.R
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
        private const val NOTIF_PERMISSION_REQUEST_CODE = 1001

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
            return
        }

        // Check if notifications are enabled — the pairing service posts the pairing code
        // as a notification. If notifications are blocked, the Pair button appears to do
        // nothing because the notification never shows (issue #204).
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val notificationsEnabled = nm.areNotificationsEnabled()
        val channel = nm.getNotificationChannel(af.shizuku.manager.adb.AdbPairingService.NOTIFICATION_CHANNEL)
        val channelEnabled = channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE

        if (!notificationsEnabled || !channelEnabled) {
            val activity = context.asActivity<FragmentActivity>() ?: return
            // On API 33+ the notification permission may simply not have been requested yet —
            // show a dialog so the user can grant it in-place rather than dig into Settings.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_notif_permission_title)
                    .setMessage(R.string.dialog_notif_permission_message)
                    .setPositiveButton(R.string.dialog_notif_permission_allow) { _, _ ->
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            NOTIF_PERMISSION_REQUEST_CODE
                        )
                    }
                    .setNegativeButton(R.string.dialog_notif_permission_manual) { _, _ ->
                        AdbPairDialogFragment().show(activity.supportFragmentManager)
                    }
                    .show()
            } else {
                // Notifications disabled at system/channel level — open settings or manual pair
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_notif_permission_title)
                    .setMessage(R.string.dialog_notif_permission_message)
                    .setPositiveButton(R.string.dialog_notif_permission_open_settings) { _, _ ->
                        af.shizuku.manager.utils.SettingsPage.Notifications.NotificationSettings.launch(context)
                    }
                    .setNegativeButton(R.string.dialog_notif_permission_manual) { _, _ ->
                        AdbPairDialogFragment().show(activity.supportFragmentManager)
                    }
                    .show()
            }
            return
        }
        val serviceIntent = af.shizuku.manager.adb.AdbPairingService.startIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            af.shizuku.manager.utils.SettingsHelper.launchOrHighlightWirelessDebugging(context)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start AdbPairingService")
            val activity = context.asActivity<FragmentActivity>()
            if (activity != null) {
                AdbPairDialogFragment().show(activity.supportFragmentManager)
            }
        }
    }
}
