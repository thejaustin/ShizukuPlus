package moe.shizuku.manager.settings

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.app.SnackbarHelper
import moe.shizuku.manager.receiver.NotifCancelReceiver
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.html.text.HtmlCompat
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect

abstract class BaseSettingsFragment : PreferenceFragmentCompat() {

    protected lateinit var batteryOptimizationListener: ActivityResultLauncher<Intent>
    protected var batteryOptimizationContinuation: CancellableContinuation<Boolean>? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE

        batteryOptimizationListener = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val accepted = SettingsHelper.isIgnoringBatteryOptimizations(requireContext())
            batteryOptimizationContinuation?.resume(accepted)
        }

        onCreateSettingsPreferences(savedInstanceState, rootKey)
    }

    abstract fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?)

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        val context = recyclerView.context
        val cardMarginPx = (16 * context.resources.displayMetrics.density).toInt()

        recyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        recyclerView.setPadding(cardMarginPx, 0, cardMarginPx, 0)
        recyclerView.clipToPadding = false
        recyclerView.addItemDecoration(SettingsItemDecoration(context))

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { _, insets ->
            val systemBarsInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())
            recyclerView.addItemSpacing(
                left = systemBarsInsets.left.toFloat(),
                right = systemBarsInsets.right.toFloat()
            )
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                systemBarsInsets.bottom
            )
            insets
        }

        recyclerView.fixEdgeEffect()
        return recyclerView
    }

    protected fun needsRestart(setting: String, newValue: Any? = null): Boolean {
        val currentPort = EnvironmentUtils.getAdbTcpPort()
        return when (setting) {
            KEY_TCP_MODE -> {
                val newMode = newValue as? Boolean ?: ShizukuSettings.getTcpMode()
                (currentPort > 0) != newMode
            }
            KEY_TCP_PORT -> {
                val newPort = newValue as? Int ?: ShizukuSettings.getTcpPort()
                (currentPort > 0) && (currentPort != newPort)
            }
            KEY_DHIZUKU_MODE, KEY_CUSTOM_API_ENABLED -> true
            else -> false
        }
    }

    protected fun maybeGetRestartIcon(setting: String): Drawable? {
        if (!needsRestart(setting)) return null
        return tint(requireContext().getDrawable(R.drawable.ic_server_restart))
    }

    protected fun tint(icon: Drawable?): Drawable? {
        val tintColor = TypedValue()
        requireContext().theme.resolveAttribute(R.attr.colorOnSurfaceVariant, tintColor, true)
        icon?.mutate()?.setTint(tintColor.data)
        return icon
    }

    protected fun promptStopTcp(tcpModePref: androidx.preference.TwoStatePreference, applyChange: () -> Unit) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(android.R.string.dialog_alert_title)
            .setMessage(context.getString(R.string.settings_tcp_mode_dialog_close_port))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    tcpModePref.apply {
                        isEnabled = false
                        summary = context.getString(R.string.settings_tcp_mode_closing_port)
                    }
                    AdbStarter.stopTcp(context, EnvironmentUtils.getAdbTcpPort())
                    if (EnvironmentUtils.getAdbTcpPort() <= 0) applyChange()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    protected fun maybePromptRestart(setting: String, newValue: Any? = null, applyChange: () -> Unit) {
        val context = requireContext()
        if (!ShizukuStateMachine.isRunning() || !needsRestart(setting, newValue)) {
            applyChange()
            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))
        } else {
            val message = buildString {
                append(context.getString(R.string.settings_restart_dialog_message))
                if (setting == KEY_TCP_MODE)
                    append(context.getString(R.string.settings_restart_dialog_message_wifi_required))
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_restart_dialog_title)
                .setMessage(HtmlCompat.fromHtml(message))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    applyChange()
                    ShizukuReceiverStarter.start(context, true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    protected fun maybeToggleBatterySensitiveSetting(newValue: Boolean, onResult: (Boolean) -> Unit) {
        val context = requireContext()
        if (!newValue || SettingsHelper.isIgnoringBatteryOptimizations(context) || EnvironmentUtils.isTelevision()) {
            onResult(true)
            return
        }
        lifecycleScope.launch {
            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                batteryOptimizationContinuation = continuation
                SnackbarHelper.show(
                    context,
                    requireView(),
                    msg = context.getString(R.string.snackbar_battery_optimization_settings),
                    duration = 6000,
                    actionText = context.getString(R.string.snackbar_action_fix),
                    action = { SettingsHelper.requestIgnoreBatteryOptimizations(context, batteryOptimizationListener) },
                    onDismiss = { event ->
                        if (event != Snackbar.Callback.DISMISS_EVENT_ACTION && continuation.isActive)
                            continuation.resume(false)
                    }
                )
            }
            onResult(result)
        }
    }

    protected class SettingsItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val cardPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val dividerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val cornerRadius = context.resources.getDimension(R.dimen.card_corner_radius)
        private val cardMargin = 16f * context.resources.displayMetrics.density

        init {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(R.attr.colorSurfaceContainerLow, typedValue, true)
            cardPaint.color = typedValue.data
            context.theme.resolveAttribute(R.attr.colorOutlineVariant, typedValue, true)
            dividerPaint.color = typedValue.data
            dividerPaint.strokeWidth = 1f * context.resources.displayMetrics.density
        }

        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val count = parent.childCount
            if (count == 0) return
            var cardTop = Float.MIN_VALUE
            var cardBottom = Float.MIN_VALUE
            for (i in 0 until count) {
                val child = parent.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                if (cardTop == Float.MIN_VALUE) cardTop = child.top.toFloat()
                cardBottom = child.bottom.toFloat()
                if (i < count - 1) {
                    val next = parent.getChildAt(i + 1)
                    if (next.visibility == View.VISIBLE) {
                        c.drawLine(
                            child.left.toFloat(),
                            child.bottom.toFloat(),
                            child.right.toFloat(),
                            child.bottom.toFloat(),
                            dividerPaint
                        )
                    }
                }
            }
            if (cardTop != Float.MIN_VALUE) {
                c.drawRoundRect(cardMargin, cardTop, parent.width - cardMargin, cardBottom, cornerRadius, cornerRadius, cardPaint)
            }
        }
    }
}
