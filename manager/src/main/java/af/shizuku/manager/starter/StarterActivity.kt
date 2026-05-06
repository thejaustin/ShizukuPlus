package af.shizuku.manager.starter

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import timber.log.Timber
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import af.shizuku.manager.AppConstants.EXTRA
import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbKeyException
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.app.AppBarActivity
import af.shizuku.manager.utils.ActivityLogManager
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.Resource
import rikka.lifecycle.Status

private class NotRootedException: Exception()

class StarterActivity : AppBarActivity() {

    private val viewModel: ViewModel by viewModels()

    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        val binding = StarterActivityBinding.inflate(layoutInflater, rootView, true)
        
        val isRoot = intent.getBooleanExtra(EXTRA_IS_ROOT, false)
        binding.header.apply {
            headerIcon.setImageResource(if (isRoot) R.drawable.ic_root_24 else R.drawable.ic_adb_24)
            headerIcon.transitionName = if (isRoot) "icon_root" else "icon_adb"
            headerTitle.setText(if (isRoot) R.string.home_root_title else R.string.home_adb_title)
        }

        binding.cancelButton.setOnClickListener { finish() }

        viewModel.output.observe(this) { result ->
            val output = result.data?.trim() ?: return@observe
            if (output.endsWith(Starter.serviceStartedMessage)) {
                val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                binding.root.performHapticFeedback(haptic)
                if (!isFinishing) finish()
            } else if (result.status == Status.ERROR) {
                binding.progressIndicator.visibility = View.GONE
                binding.cancelButton.visibility = View.GONE
                var message = 0
                when (result.error) {
                    is AdbKeyException -> message = R.string.adb_error_key_store
                    is NotRootedException -> message = R.string.start_with_root_failed
                    is SocketTimeoutException -> message = R.string.cannot_connect_port
                    is ConnectException -> message = R.string.cannot_connect_port
                    is SSLProtocolException -> message = R.string.adb_pair_required
                    is TimeoutException -> message = R.string.adb_error_timeout
                }
                val dialogMessage = if (message != 0) message else R.string.adb_error_generic
                MaterialAlertDialogBuilder(this)
                    .setMessage(dialogMessage)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setNegativeButton(R.string.starter_retry) { _, _ ->
                        binding.progressIndicator.visibility = View.VISIBLE
                        binding.cancelButton.visibility = View.VISIBLE
                        viewModel.retry()
                    }
                    .show()
            }
            binding.text1.text = output
            binding.scrollView.post { binding.scrollView.scrollTo(0, Int.MAX_VALUE) }
        }
    }


    private var hasStarted = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasStarted) {
            hasStarted = true
            val port = intent.getIntExtra(EXTRA_PORT, 0)
            
            viewModel.start(
                intent.getBooleanExtra(EXTRA_IS_ROOT, false),
                port
            )
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

class ViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    private val handler = CoroutineExceptionHandler { _, throwable ->
        ShizukuStateMachine.update()
        log(error = throwable)
    }

    private var started = false
    private var lastRoot = false
    private var lastPort = 0

    fun start(root: Boolean, port: Int) {
        lastRoot = root
        lastPort = port
        if (!root && port !in 1..65535) {
            log(error = IllegalArgumentException("Invalid port value: $port. Port must be between 1 and 65535."))
            return
        }
        if (started) return
        started = true

        viewModelScope.launch(handler) {
            if (root) startRoot()
            else AdbStarter.startAdb(appContext, port, { log(it) })
            Starter.waitForBinder({ log(it) })
        }
    }

    fun retry() {
        started = false
        sb.clear()
        _output.postValue(Resource.success(sb))
        start(lastRoot, lastPort)
    }

    private fun log(line: String? = null, error: Throwable? = null) {
        line?.let { sb.appendLine(it) }
        error?.let {
            if (it !is TimeoutException) {
                sb.appendLine().appendLine(Log.getStackTraceString(it))
            }
        }

        if (error == null) _output.postValue(Resource.success(sb))
        else _output.postValue(Resource.error(error, sb))
    }

    private suspend fun startRoot() {
        log("Starting with root...\n")

        return withContext(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) {
                // Try again just in case
                Shell.getCachedShell()?.close()

                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    throw NotRootedException()
                }
            }

            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            suspendCancellableCoroutine { cont ->
                Shell.cmd(Starter.internalCommand)
                    .to(object : CallbackList<String?>() {
                        override fun onAddElement(s: String?) { s?.let { log(it) } }
                    })
                    .submit {
                        if (it.isSuccess) {
                            ShizukuStateMachine.update()
                            ActivityLogManager.log("Shizuku", appContext.packageName, "Service started via root")
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(Exception("Failed to start with root"))
                        }
                    }
            }
        }
    }
    
}
