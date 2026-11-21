package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.*
import af.shizuku.manager.databinding.AdbPairDialogBinding
import af.shizuku.manager.utils.SettingsHelper
import android.annotation.SuppressLint
import android.app.Application
import android.app.Dialog
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.LayoutInflater
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.ConnectException

@RequiresApi(VERSION_CODES.R)
class AdbPairDialogFragment : DialogFragment() {
    private lateinit var binding: AdbPairDialogBinding
    private lateinit var statePanel: PairingStatePanel

    private val viewModel: ViewModel by activityViewModels()

    // A port was found and then lost: Settings closed its dialog, so its code is gone.
    private var portLost = false
    private var finishing = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbPairDialogBinding.inflate(LayoutInflater.from(context))
        statePanel = PairingStatePanel.inflate(binding.stateContainer, inline = true)

        val builder =
            MaterialAlertDialogBuilder(context).apply {
                setTitle(R.string.dialog_adb_pairing_title)
                setView(binding.root)
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton(android.R.string.ok, null)
                setNeutralButton(R.string.development_settings, null)
            }
        // Fresh dialog session (not a config-change recreation): clear any terminal result left in
        // the activity-scoped ViewModel from a previous open, so the observer below doesn't get the
        // stale value redelivered and instantly dismiss (past success) or flash a stale failure.
        if (savedInstanceState == null) viewModel.reset()

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    private fun onDialogShow(dialog: AlertDialog) {
        val codeEditText = binding.pairingCode.editText
        val isNumerical = ShizukuSettings.isNumericalPairingKeyboardEnabled()
        if (isNumerical) {
            codeEditText?.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            codeEditText?.keyListener =
                android.text.method.DigitsKeyListener
                    .getInstance("0123456789")
        } else {
            codeEditText?.inputType = android.text.InputType.TYPE_CLASS_TEXT
            codeEditText?.keyListener =
                android.text.method.TextKeyListener
                    .getInstance()
        }
        codeEditText?.doAfterTextChanged {
            binding.pairingCode.error = null
        }
        binding.port.editText?.doAfterTextChanged {
            binding.port.error = null
        }

        binding.pairingCode.error = null
        binding.pairingCode.isVisible = true
        binding.port.isVisible = true

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = true

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            SettingsHelper.launchOrHighlightWirelessDebugging(it.context)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val context = it.context
            val portEditText = binding.port.editText
            val port =
                try {
                    portEditText?.text.toString().toInt()
                } catch (_: Exception) {
                    -1
                }
            if (port > 65535 || port < 1) {
                binding.port.isVisible = true
                showFieldError(binding.port, context.getString(R.string.dialog_adb_invalid_port))
                return@setOnClickListener
            }

            val password = codeEditText?.text?.toString()?.trim() ?: ""
            if (password.length != 6 || !password.all { it.isDigit() }) {
                showFieldError(binding.pairingCode, context.getString(R.string.dialog_adb_invalid_code))
                return@setOnClickListener
            }

            viewModel.run(port, password)
        }

        viewModel.port.observe(this) { portValue ->
            val portEditText = binding.port.editText
            if (portValue <= 0) {
                if (viewModel.lastPort > 0) portLost = true
                if (portEditText?.text?.toString() == "-1" || portEditText?.text?.isEmpty() == true) {
                    portEditText?.setText("")
                }
            } else {
                portLost = false
                if (portEditText?.text?.toString() != portValue.toString()) portEditText?.setText(portValue.toString())
            }
            viewModel.lastPort = portValue
            render()
        }
    }

    override fun onStart() {
        super.onStart()

        viewModel.isPairing.observe(this) { isPairing ->
            binding.pairingCode.isEnabled = !isPairing
            binding.port.isEnabled = !isPairing
            getDialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !isPairing
            render()
        }

        viewModel.result.observe(this) { state ->
            when (state) {
                is PairingState.Idle -> Unit
                is PairingState.Success -> onPaired()
                is PairingState.Failure -> onFailed(state.error)
            }
            render()
        }
    }

    // Wi-Fi, Wireless debugging or split screen may have changed while the user was in Settings.
    override fun onResume() {
        super.onResume()
        if (::statePanel.isInitialized) render()
    }

    private fun inMultiScreen(): Boolean {
        val activity = activity ?: return false
        return activity.isInMultiWindowMode || (
            activity.window
                ?.decorView
                ?.display
                ?.displayId ?: -1
        ) > 0
    }

    private fun render() {
        if (!::statePanel.isInitialized || !isAdded) return
        val context = requireContext()
        val result = viewModel.result.value
        val port = viewModel.port.value ?: -1
        val spec =
            when {
                result is PairingState.Success ->
                    spec("done", R.drawable.ic_check_24, PairingTone.DONE, R.string.pairing_v2_paired_title, getText(R.string.pairing_v2_success_starting))
                viewModel.isPairing.value == true ->
                    spec("pairing", R.drawable.ic_wadb_24, PairingTone.WORKING, R.string.pairing_v2_pairing_title, getText(R.string.pairing_v2_pairing_text))
                result is PairingState.Failure -> failureSpec(result.error)
                else -> waitingSpec(context, port)
            }
        statePanel.render(spec)
        dialog?.setTitle(if (port > 0) R.string.dialog_adb_pairing_title else R.string.dialog_adb_pairing_discovery)
    }

    private fun waitingSpec(
        context: android.content.Context,
        port: Int,
    ): PairingStateSpec {
        if (port > 0) {
            return spec(
                "ready:$port",
                R.drawable.ic_code_24,
                PairingTone.NEEDS_YOU,
                R.string.pairing_v2_ready_title,
                getString(R.string.pairing_v2_ready_text, port),
            )
        }
        // Notifications don't matter here: this dialog is the way around them.
        when (PairingPreflight.check(context, includeNotifications = false)) {
            PairingBlocker.DEVELOPER_OPTIONS_OFF ->
                return spec(
                    "blocker:devopts",
                    R.drawable.ic_settings_outline_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_devopts_off_title,
                    getText(R.string.pairing_v2_devopts_off_text),
                    PairingAction(R.string.pairing_v2_action_open_about) { PairingPreflight.openAboutPhone(context) },
                )
            PairingBlocker.NO_WIFI -> {
                val hotspot = PairingPreflight.hotspotHelps
                return spec(
                    "blocker:wifi",
                    R.drawable.ic_wifi_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_no_wifi_title,
                    getText(if (hotspot) R.string.pairing_v2_no_wifi_text_hotspot else R.string.pairing_v2_no_wifi_text),
                    PairingAction(R.string.pairing_v2_action_wifi) { PairingPreflight.openWifi(context) },
                    if (hotspot) PairingAction(R.string.pairing_v2_action_hotspot) { PairingPreflight.openHotspot(context) } else null,
                )
            }
            PairingBlocker.WIRELESS_DEBUGGING_OFF ->
                // The dialog's own Developer options button is the way there.
                return spec(
                    "blocker:wd",
                    R.drawable.ic_wadb_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_wd_off_title,
                    getText(R.string.pairing_v2_wd_off_text),
                )
            PairingBlocker.LOCAL_NETWORK_DENIED ->
                return spec(
                    "blocker:localnet",
                    R.drawable.ic_wifi_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_local_net_title,
                    getText(R.string.pairing_v2_local_net_text),
                    PairingAction(R.string.pairing_v2_action_app_info) { PairingPreflight.openAppInfo(context) },
                )
            else -> Unit
        }
        if (!inMultiScreen()) {
            return spec(
                "split",
                R.drawable.ic_outline_info_24,
                PairingTone.NEEDS_YOU,
                R.string.pairing_v2_split_title,
                getText(R.string.pairing_v2_split_text),
            )
        }
        if (portLost) {
            return spec(
                "expired",
                R.drawable.ic_warning_24,
                PairingTone.NEEDS_YOU,
                R.string.pairing_v2_expired_title,
                getText(R.string.pairing_v2_expired_text),
            )
        }
        return spec(
            "searching",
            R.drawable.ic_wadb_24,
            PairingTone.WORKING,
            R.string.pairing_v2_searching_title,
            getText(R.string.pairing_v2_searching_text),
        )
    }

    private fun failureSpec(error: Throwable): PairingStateSpec {
        val context = requireContext()
        val doctor = PairingAction(R.string.pairing_v2_action_service_doctor) { PairingPreflight.openServiceDoctor(context) }
        return when (error) {
            is AdbInvalidPairingCodeException ->
                spec("failed:wrong", R.drawable.ic_code_24, PairingTone.PROBLEM, R.string.pairing_v2_wrong_code_title, getText(R.string.pairing_v2_wrong_code_text))
            is ConnectException ->
                spec(
                    "failed:connect",
                    R.drawable.ic_server_error_24,
                    PairingTone.PROBLEM,
                    R.string.pairing_v2_cant_connect_title,
                    getText(R.string.pairing_v2_cant_connect_text),
                )
            is AdbKeyException ->
                spec("failed:key", R.drawable.ic_server_error_24, PairingTone.PROBLEM, R.string.pairing_v2_key_title, getText(R.string.pairing_v2_key_text), doctor)
            else ->
                spec(
                    "failed:unknown",
                    R.drawable.ic_server_error_24,
                    PairingTone.PROBLEM,
                    R.string.pairing_v2_failed_title,
                    getText(R.string.pairing_v2_failed_text),
                    doctor,
                )
        }
    }

    private fun spec(
        key: String,
        icon: Int,
        tone: PairingTone,
        title: Int,
        text: CharSequence,
        primary: PairingAction? = null,
        secondary: PairingAction? = null,
    ) = PairingStateSpec(key, icon, tone, getText(title), text, primary, secondary)

    private fun onFailed(error: Throwable) {
        val context = requireContext()
        when (error) {
            is AdbInvalidPairingCodeException -> {
                showFieldError(binding.pairingCode, context.getString(R.string.paring_code_is_wrong))
                // Ready for the corrected code without clearing it by hand.
                binding.pairingCode.editText?.let {
                    it.requestFocus()
                    it.selectAll()
                }
            }
            is ConnectException -> showFieldError(binding.port, context.getString(R.string.cannot_connect_port))
            else -> Unit
        }
    }

    // Success keeps the old behaviour (close and start), after the check has had a moment on screen.
    private fun onPaired() {
        if (finishing) return
        finishing = true
        val delay = ShizukuSettings.scaledAnimationDuration(SUCCESS_HOLD_MS)
        binding.root.postDelayed({
            val act = activity
            dismissAllowingStateLoss()
            if (act is androidx.fragment.app.FragmentActivity) {
                StartWirelessAdbViewHolder.start(act, act.lifecycleScope)
            }
        }, delay)
    }

    private fun showFieldError(
        field: com.google.android.material.textfield.TextInputLayout,
        message: String,
    ) {
        field.error = message
        @Suppress("DEPRECATION")
        field.announceForAccessibility(getString(R.string.pairing_v2_code_error_a11y, message))
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }

    override fun getDialog(): AlertDialog? = super.getDialog() as AlertDialog?

    private companion object {
        const val SUCCESS_HOLD_MS = 700L
    }
}

sealed class PairingState {
    object Idle : PairingState()

    object Success : PairingState()

    data class Failure(
        val error: Throwable,
    ) : PairingState()
}

@SuppressLint("NewApi")
class ViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>().applicationContext

    private val _result = MutableLiveData<PairingState>(PairingState.Idle)
    val result = _result as LiveData<PairingState>

    private val _port = MutableLiveData<Int>()
    val port = _port as LiveData<Int>

    // The last port the dialog saw, so it can tell "not found yet" from "found, then closed".
    var lastPort = -1

    private val _isPairing = MutableLiveData<Boolean>(false)
    val isPairing = _isPairing as LiveData<Boolean>

    private val adbMdns: AdbMdns =
        AdbMdns(appContext, AdbMdns.TLS_PAIRING) {
            _port.postValue(it)
        }

    init {
        adbMdns.start()
    }

    fun run(
        port: Int,
        password: String,
    ) {
        _isPairing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val host = adbMdns.resolvedHost ?: "127.0.0.1"

            val key =
                try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
                } catch (e: Throwable) {
                    Timber.e(e, "failed to load or create AdbKey")
                    _isPairing.postValue(false)
                    _result.postValue(PairingState.Failure(AdbKeyException(e)))
                    return@launch
                }

            AdbPairingClient(host, port, password, key)
                .runCatching {
                    start()
                }.onFailure {
                    _isPairing.postValue(false)
                    _result.postValue(PairingState.Failure(it))
                    Timber.e(it, "adb pairing failed")
                }.onSuccess {
                    _isPairing.postValue(false)
                    if (it) {
                        _result.postValue(PairingState.Success)
                    } else {
                        _result.postValue(PairingState.Failure(Exception("Pairing failed")))
                    }
                }
        }
    }

    /** Clears terminal state so a reopened dialog starts fresh (see AdbPairDialogFragment). */
    fun reset() {
        lastPort = -1
        _result.value = PairingState.Idle
        _isPairing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        adbMdns.stop()
    }
}
