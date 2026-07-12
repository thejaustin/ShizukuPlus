package af.shizuku.manager.settings

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
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.receiver.NotifCancelReceiver
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.html.text.HtmlCompat
import rikka.recyclerview.fixEdgeEffect

abstract class BaseSettingsFragment : PreferenceFragmentCompat() {

    protected lateinit var batteryOptimizationListener: ActivityResultLauncher<Intent>
    protected var batteryOptimizationContinuation: CancellableContinuation<Boolean>? = null
    private val activeDialogs = mutableListOf<android.app.Dialog>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE

        batteryOptimizationListener = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val accepted = SettingsHelper.isIgnoringBatteryOptimizations(requireContext())
            batteryOptimizationContinuation?.resume(accepted)
        }

        onCreateSettingsPreferences(savedInstanceState, rootKey)

        preferenceScreen?.let { IconStyleHelper.applyToTree(requireContext(), it) }
    }

    abstract fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?)

    override fun onPreferenceTreeClick(preference: androidx.preference.Preference): Boolean {
        HapticUtils.tap(requireView())
        return super.onPreferenceTreeClick(preference)
    }

    open fun getTitle(): CharSequence? = null

    override fun onResume() {
        super.onResume()

        val title = getTitle()
        if (title != null) {
            (activity as? SettingsActivity)?.updateTitle(title.toString())
        }

        val highlightKey = arguments?.getString("highlight_key")
        if (!highlightKey.isNullOrEmpty()) {
            listView?.post {
                val adapter = listView?.adapter
                if (adapter != null) {
                    var position = -1
                    for (i in 0 until adapter.itemCount) {
                        val pref = (adapter as? androidx.preference.PreferenceGroupAdapter)?.getItem(i)
                        if (pref?.key == highlightKey) {
                            position = i
                            break
                        }
                    }

                    if (position >= 0) {
                        listView?.smoothScrollToPosition(position)
                        listView?.postDelayed({
                            val holder = listView?.findViewHolderForAdapterPosition(position)
                            holder?.itemView?.let { itemView ->
                                val defaultBg = itemView.background
                                val tintColor = TypedValue()
                                requireContext().theme.resolveAttribute(R.attr.colorPrimaryContainer, tintColor, true)
                                itemView.setBackgroundColor(tintColor.data)
                                itemView.animate()
                                    .setDuration(ShizukuSettings.scaledAnimationDuration(1200))
                                    .alpha(1.0f)
                                    .withEndAction {
                                        itemView.background = defaultBg
                                    }
                                    .start()
                            }
                        }, 400)
                    }
                }
            }
            arguments?.remove("highlight_key")
        }
    }

    override fun onDestroyView() {
        activeDialogs.forEach { if (it.isShowing) it.dismiss() }
        activeDialogs.clear()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
    }

    /**
     * Helper to show a dialog and track it for proper dismissal.
     */
    protected fun showDialog(builder: MaterialAlertDialogBuilder) {
        val dialog = builder.show()
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        val context = recyclerView.context
        val cardMarginPx = (16 * context.resources.displayMetrics.density).toInt()
        val contentPaddingPx = (8 * context.resources.displayMetrics.density).toInt()

        // Fix: Disable LayoutTransition to prevent IllegalArgumentException
        // "Providing a LayoutTransition into RecyclerView is not supported"
        recyclerView.layoutTransition = null
        recyclerView.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            supportsChangeAnimations = false
        }

        recyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        recyclerView.setPadding(cardMarginPx + contentPaddingPx, 0, cardMarginPx + contentPaddingPx, 0)
        recyclerView.clipToPadding = false
        recyclerView.addItemDecoration(SettingsItemDecoration(context))

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { _, insets ->
            val systemBarsInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())
            recyclerView.setPadding(
                cardMarginPx + contentPaddingPx + systemBarsInsets.left,
                recyclerView.paddingTop,
                cardMarginPx + contentPaddingPx + systemBarsInsets.right,
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
        showDialog(
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
        )
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
            showDialog(
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.settings_restart_dialog_title)
                    .setMessage(HtmlCompat.fromHtml(message))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        applyChange()
                        ShizukuReceiverStarter.start(context, true)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            )
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

    protected fun maybeToggleSecureSetting(newValue: Boolean, onResult: (Boolean) -> Unit) {
        val context = requireContext()
        if (!newValue || SettingsHelper.hasWriteSecureSettings(context) || EnvironmentUtils.isRooted()) {
            onResult(true)
            return
        }
        SettingsHelper.promptWriteSecureSettings(context)
        onResult(false)
    }

    protected class SettingsItemDecoration(context: Context) : af.shizuku.manager.widget.M3ECardItemDecoration(context) {
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return

            // Add space above category headers for M3E spacing
            if (view.tag == "category_header") {
                outRect.top = (12 * density).toInt()
            }
        }

        override fun isHeader(view: View): Boolean = view.tag == "category_header"
    }
}
