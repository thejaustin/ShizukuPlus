package af.shizuku.manager.home

import android.annotation.SuppressLint
import android.app.Application
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Toast
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.*
import af.shizuku.manager.databinding.AdbPairDialogBinding
import timber.log.Timber
import af.shizuku.manager.utils.SettingsHelper
import java.net.ConnectException

@RequiresApi(VERSION_CODES.R)
class AdbPairDialogFragment : DialogFragment() {

    private lateinit var binding: AdbPairDialogBinding

    private val viewModel: ViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbPairDialogBinding.inflate(LayoutInflater.from(context))

        val builder = MaterialAlertDialogBuilder(context).apply {
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
        codeEditText?.doAfterTextChanged {
            binding.pairingCode.error = null
        }

        binding.pairingCode.error = null

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = false

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            SettingsHelper.launchOrHighlightWirelessDebugging(it.context)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val context = it.context
            val portEditText = binding.port.editText
            val port = try {
                portEditText?.text.toString().toInt()
            } catch (e: Exception) {
                -1
            }
            if (port > 65535 || port < 1) {
                binding.port.isVisible = true
                binding.port.error = context.getString(R.string.dialog_adb_invalid_port)
                return@setOnClickListener
            }

            val password = codeEditText?.text.toString() ?: ""

            viewModel.run(port, password)
        }

        viewModel.port.observe(this) { portValue ->
            val portEditText = binding.port.editText
            if (portValue == -1) {
                dialog.setTitle(R.string.dialog_adb_pairing_discovery)
                binding.text1.isVisible = true
                binding.pairingCode.isVisible = false
                binding.progress.isVisible = true
                binding.status.isVisible = true
                if (!af.shizuku.manager.utils.NetworkStateHelper.isWirelessAdbSupportedNetwork(requireContext())) {
                    val tip = if (af.shizuku.manager.utils.EnvironmentUtils.isSamsung()) {
                        getString(R.string.dialog_adb_samsung_hotspot_warning)
                    } else {
                        getString(R.string.dialog_adb_hotspot_tip)
                    }
                    binding.status.text = getString(R.string.dialog_adb_wifi_disconnected_warning) + "\n\n" + tip
                } else {
                    binding.status.text = getString(R.string.adb_pairing_searching)
                }
                portEditText?.setText(portValue.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible = true
            } else {
                dialog.setTitle(R.string.dialog_adb_pairing_title)
                binding.text1.isVisible = false
                binding.pairingCode.isVisible = true
                binding.progress.isVisible = false
                binding.status.isVisible = false
                portEditText?.setText(portValue.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = true
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible = false
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val context = requireContext()
        val inMultiScreenOrDisplay = (requireActivity().isInMultiWindowMode
                || (requireActivity().window?.decorView?.display?.displayId ?: -1) > 0)

        binding.text1.isVisible = inMultiScreenOrDisplay
        binding.text2.isVisible = !inMultiScreenOrDisplay

        if (inMultiScreenOrDisplay) {
            dialog?.setTitle(R.string.dialog_adb_pairing_discovery)
        } else {
            dialog?.setTitle(R.string.dialog_adb_pairing_title)
        }

        viewModel.isPairing.observe(this) { isPairing ->
            binding.progress.isVisible = isPairing
            binding.status.isVisible = isPairing
            if (isPairing) {
                binding.status.text = getString(R.string.adb_pairing_status_pairing)
                binding.pairingCode.isEnabled = false
                getDialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            } else {
                binding.pairingCode.isEnabled = true
                getDialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
        }

        viewModel.result.observe(this) { state ->
            when (state) {
                is PairingState.Idle -> {
                    // Fresh session — nothing to show yet.
                }
                is PairingState.Success -> dismissAllowingStateLoss()
                is PairingState.Failure -> {
                    binding.progress.isVisible = false
                    binding.status.isVisible = true
                    binding.status.text = getString(R.string.adb_pairing_status_failed)
                    when (val error = state.error) {
                        is ConnectException -> {
                            binding.port.error = context.getString(R.string.cannot_connect_port)
                        }
                        is AdbInvalidPairingCodeException -> {
                            binding.pairingCode.error = context.getString(R.string.paring_code_is_wrong)
                        }
                        is AdbKeyException -> {
                            Toast.makeText(context, context.getString(R.string.adb_error_key_store), Toast.LENGTH_LONG)
                                .apply { setGravity(Gravity.CENTER, 0, 0) }.show()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }

    override fun getDialog(): AlertDialog? {
        return super.getDialog() as AlertDialog?
    }
}

sealed class PairingState {
    object Idle : PairingState()
    object Success : PairingState()
    data class Failure(val error: Throwable) : PairingState()
}

@SuppressLint("NewApi")
class ViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext

    private val _result = MutableLiveData<PairingState>(PairingState.Idle)
    val result = _result as LiveData<PairingState>

    private val _port = MutableLiveData<Int>()
    val port = _port as LiveData<Int>

    private val _isPairing = MutableLiveData<Boolean>(false)
    val isPairing = _isPairing as LiveData<Boolean>

    private val adbMdns: AdbMdns = AdbMdns(appContext, AdbMdns.TLS_PAIRING) {
        _port.postValue(it)
    }

    init {
        adbMdns.start()
    }

    fun run(port: Int, password: String) {
        _isPairing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val host = adbMdns.resolvedHost

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku+")
            } catch (e: Throwable) {
                Timber.e("failed to load or create AdbKey", e)
                _isPairing.postValue(false)
                _result.postValue(PairingState.Failure(AdbKeyException(e)))
                return@launch
            }

            AdbPairingClient(host, port, password, key).runCatching {
                start()
            }.onFailure {
                _isPairing.postValue(false)
                _result.postValue(PairingState.Failure(it))
                Timber.e("adb pairing failed", it)
            }.onSuccess {
                _isPairing.postValue(false)
                if (it) {
                    _result.postValue(PairingState.Success)
                }
            }
        }
    }

    /** Clears terminal state so a reopened dialog starts fresh (see AdbPairDialogFragment). */
    fun reset() {
        _result.value = PairingState.Idle
        _isPairing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        adbMdns.stop()
    }
}
