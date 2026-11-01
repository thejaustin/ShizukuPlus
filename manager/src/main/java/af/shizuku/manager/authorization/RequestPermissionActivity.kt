package af.shizuku.manager.authorization

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.databinding.ConfirmationDialogBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.core.res.resolveColor
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

class RequestPermissionActivity : AppActivity() {
    private lateinit var dialog: Dialog

    // Dispatches the result to the requesting app via Binder.
    // Must be called from Dispatchers.IO — dispatchPermissionConfirmationResult is a
    // synchronous Binder call; running it on Main causes ANR (SHIZUKUPLUS-21).
    private fun dispatchResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        allowed: Boolean,
        onetime: Boolean,
    ) {
        val data = Bundle()
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: Throwable) {
            LOGGER.e("dispatchPermissionConfirmationResult")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        // ai is null when the server couldn't resolve the caller via PackageManager (device/profile
        // PM-lookup gap, #391 follow-up) - previously that silently finish()'d here with no dispatch,
        // leaving the caller's requestPermission() call hanging forever. uid/pid/requestCode are
        // always present (the server only starts this activity with them set), so fall back to the
        // callingPackage label instead of dropping the request.
        val ai =
            androidx.core.content.IntentCompat
                .getParcelableExtra(intent, "applicationInfo", ApplicationInfo::class.java)
        val callingPackage = intent.getStringExtra("callingPackage")
        if (uid == -1 || pid == -1) {
            finish()
            return
        }

        lifecycleScope.launch {
            // pingBinder() is a synchronous Binder call — run on IO so the main thread
            // stays free. withTimeout only cancels at suspension points, so moving the
            // blocking call to IO is the only safe way to prevent ANR here.
            val binderReady =
                try {
                    withTimeout(5000) {
                        while (true) {
                            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING) {
                                val alive =
                                    withContext(Dispatchers.IO) {
                                        try {
                                            Shizuku.pingBinder()
                                        } catch (_: Exception) {
                                            false
                                        }
                                    }
                                if (alive) break
                            }
                            delay(100)
                        }
                    }
                    true
                } catch (e: TimeoutCancellationException) {
                    LOGGER.e(e, "Binder not received or ping failed in 5s")
                    false
                }

            if (isFinishing || isDestroyed) return@launch

            if (!binderReady) {
                finish()
                return@launch
            }

            // checkRemotePermission is a synchronous Binder call — run on IO
            val hasSelfPermission =
                withContext(Dispatchers.IO) {
                    try {
                        Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
                    } catch (e: Exception) {
                        LOGGER.w(e, "checkRemotePermission failed, denying by default")
                        false
                    }
                }

            if (isFinishing || isDestroyed) return@launch
            showPermissionDialog(uid, pid, requestCode, ai, callingPackage, hasSelfPermission)
        }
    }

    private fun showPermissionDialog(
        uid: Int,
        pid: Int,
        requestCode: Int,
        ai: ApplicationInfo?,
        callingPackage: String?,
        hasSelfPermission: Boolean,
    ) {
        if (!hasSelfPermission) {
            // Can't grant — dispatch denial and show informational dialog
            lifecycleScope.launch(Dispatchers.IO) {
                dispatchResult(uid, pid, requestCode, allowed = false, onetime = true)
            }
            showSelfPermissionMissingDialog()
            return
        }

        val label =
            ai?.let {
                try {
                    it.loadLabel(packageManager)
                } catch (_: Exception) {
                    it.packageName
                }
            } ?: callingPackage ?: "Shell"

        val binding =
            ConfirmationDialogBinding.inflate(layoutInflater).apply {
                button1.setOnClickListener {
                    // Dispatch result on IO then dismiss — keeps UI responsive while
                    // the Binder call completes without blocking the main thread
                    val pkg = ai?.packageName ?: callingPackage ?: "unknown"
                    af.shizuku.manager.database.ActivityLogManager
                        .log(label.toString(), pkg, "Permission: Granted by user")
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            dispatchResult(uid, pid, requestCode, allowed = true, onetime = false)
                        }
                        if (!isFinishing && !isDestroyed) dialog.dismiss()
                    }
                }
                button3.setOnClickListener {
                    val pkg = ai?.packageName ?: callingPackage ?: "unknown"
                    af.shizuku.manager.database.ActivityLogManager
                        .log(label.toString(), pkg, "Permission: Denied by user")
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            dispatchResult(uid, pid, requestCode, allowed = false, onetime = true)
                        }
                        if (!isFinishing && !isDestroyed) dialog.dismiss()
                    }
                }
                title.text =
                    HtmlCompat.fromHtml(
                        getString(R.string.permission_warning_template, label, getString(R.string.permission_group_description)),
                    )
            }
        (ai?.packageName ?: callingPackage)?.let { bindSafetyCheck(binding.safetyCheck, it) }

        dialog =
            MaterialAlertDialogBuilder(this)
                .setView(binding.root)
                .setCancelable(false)
                .setOnDismissListener { finish() }
                .create()
        dialog.setCanceledOnTouchOutside(false)
        try {
            dialog.show()
        } catch (_: WindowManager.BadTokenException) {
            // Activity window detached by the time the coroutine resumed
            finish()
        }
    }

    /**
     * The quick safety check under the request, all opt-in (Settings → App safety check): install
     * source, signer, and VirusTotal / Pithus verdicts when the user turned those on and added a key.
     * With everything off, nothing runs and the request looks like stock Shizuku's. Fills in as answers arrive; the
     * buttons work the whole time.
     */
    private fun bindSafetyCheck(
        view: android.widget.TextView,
        packageName: String,
    ) {
        val prefs =
            af.shizuku.manager.ShizukuSettings
                .getPreferences()
        val showLocal = prefs?.getBoolean("verify_apk_local", false) == true
        val vtKey =
            af.shizuku.manager.ShizukuSettings
                .getVirusTotalApiKey()
                .takeIf { it.isNotBlank() && prefs?.getBoolean("verify_apk_virustotal", false) == true }
        val pithusKey =
            af.shizuku.manager.ShizukuSettings
                .getPithusApiKey()
                .takeIf { it.isNotBlank() && prefs?.getBoolean("verify_apk_pithus", false) == true }
        if (!showLocal && vtKey == null && pithusKey == null) return

        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { AppSafetyCheck.localInfo(this@RequestPermissionActivity, packageName) } ?: return@launch
            val lines = mutableListOf<CharSequence>()

            fun render() {
                view.text = lines.joinToString("\n")
                view.visibility = android.view.View.VISIBLE
            }
            if (showLocal) {
                val from =
                    when (AppSafetyCheck.source(local.installer)) {
                        AppSafetyCheck.Source.STORE, AppSafetyCheck.Source.OPEN_SOURCE_STORE ->
                            getString(R.string.safety_check_from_store, local.installerLabel ?: local.installer)
                        AppSafetyCheck.Source.FILE -> getString(R.string.safety_check_from_file)
                        AppSafetyCheck.Source.OTHER_APP -> getString(R.string.safety_check_from_app, local.installerLabel ?: local.installer)
                        AppSafetyCheck.Source.UNKNOWN -> getString(R.string.safety_check_from_unknown)
                    }
                val days = ((System.currentTimeMillis() - local.firstInstallTime) / 86_400_000L).toInt().coerceAtLeast(0)
                val age =
                    if (days == 0) {
                        getString(R.string.safety_check_installed_today)
                    } else {
                        resources.getQuantityString(R.plurals.safety_check_installed_days, days, days)
                    }
                lines += "$from · $age"
                // Short form, grouped like a fingerprint; enough to compare against the developer's.
                local.signerSha256?.let { sha ->
                    lines +=
                        getString(
                            R.string.safety_check_signer,
                            sha
                                .take(16)
                                .uppercase()
                                .chunked(4)
                                .joinToString(" "),
                        )
                }
                render()
            }

            val services = listOfNotNull(vtKey?.let { "VirusTotal" to it }, pithusKey?.let { "Pithus" to it })
            if (services.isEmpty()) return@launch
            val firstServiceLine = lines.size
            services.forEach { (name, _) -> lines += getString(R.string.safety_check_checking, name) }
            render()
            val sha = withContext(Dispatchers.IO) { local.apkPath?.let { AppSafetyCheck.sha256Of(java.io.File(it)) } }
            services.forEachIndexed { i, (name, key) ->
                launch {
                    val verdict =
                        if (sha == null) {
                            AppSafetyCheck.Verdict.Unavailable
                        } else {
                            withContext(Dispatchers.IO) {
                                if (name == "VirusTotal") AppSafetyCheck.virusTotal(key, sha) else AppSafetyCheck.pithus(key, sha)
                            }
                        }
                    lines[firstServiceLine + i] =
                        when (verdict) {
                            is AppSafetyCheck.Verdict.Clean -> getString(R.string.safety_check_clean, name, verdict.engines)
                            is AppSafetyCheck.Verdict.Flagged ->
                                verdict.family?.let { getString(R.string.safety_check_known_malware, name, it) }
                                    ?: getString(R.string.safety_check_flagged, name, verdict.detections, verdict.engines)
                            AppSafetyCheck.Verdict.Unknown -> getString(R.string.safety_check_unknown, name)
                            AppSafetyCheck.Verdict.BadKey -> getString(R.string.safety_check_bad_key, name)
                            AppSafetyCheck.Verdict.Unavailable -> getString(R.string.safety_check_unavailable, name)
                        }
                    if (!isFinishing && !isDestroyed) render()
                }
            }
        }
    }

    private fun showSelfPermissionMissingDialog() {
        val icon = getDrawable(R.drawable.ic_notification_icon)
        icon?.setTint(theme.resolveColor(android.R.attr.colorAccent))

        val d =
            MaterialAlertDialogBuilder(this)
                .setIcon(icon)
                .setTitle("Shizuku: ${getString(R.string.app_management_dialog_adb_is_limited_title)}")
                .setMessage(
                    getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get())
                        .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE),
                ).setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { finish() }
                .create()
        d.setOnShowListener {
            (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        }
        try {
            d.show()
        } catch (e: Throwable) {
            LOGGER.w("Failed to show permission dialog (window may be detached)", e)
        }
    }
}
