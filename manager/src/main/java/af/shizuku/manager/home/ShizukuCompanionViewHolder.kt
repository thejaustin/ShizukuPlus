package af.shizuku.manager.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeShizukuCompanionBinding
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.StockShizukuCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import timber.log.Timber

class ShizukuCompanionViewHolder(
    private val binding: HomeShizukuCompanionBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
    private val homeModel: HomeViewModel,
) : BaseViewHolder<Pair<Boolean, Boolean>>(containerBinding.root) {

    companion object {
        fun creator(scope: CoroutineScope, homeModel: HomeViewModel): Creator<Pair<Boolean, Boolean>> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeShizukuCompanionBinding.inflate(inflater, outer.cardContent, true)
                ShizukuCompanionViewHolder(inner, outer, scope, homeModel)
            }
        }
    }

    /**
     * Runs [cmd] via Shizuku if available, falling back to root. Returns true on success.
     * Centralizes the Shizuku-then-root fallback so install/disable don't duplicate it.
     */
    private suspend fun runPrivilegedCommand(cmd: String): Boolean = withContext(Dispatchers.IO) {
        // Callers launch via HomeActivity's Main-dispatched coroutine scope - process.waitFor()
        // and Shell.exec() are both blocking IPC/subprocess waits, so without this dispatch
        // they'd block the main thread for the duration of the command (ANR - SHIZUKUPLUS-7H/7P).
        if (Shizuku.pingBinder()) {
            var process: Process? = null
            try {
                process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            } finally {
                // waitFor() can throw if the binder dies mid-command; without the finally the
                // process handle (and its pipes) leaks on that path.
                try { process?.destroy() } catch (_: Exception) {}
            }
        } else if (MigrationHelper.isRootAvailable()) {
            try {
                Shell.cmd(cmd).exec().isSuccess
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    private val originalIcon = binding.icon.drawable

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@ShizukuCompanionViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }

        binding.button1.setOnClickListener { v ->
            af.shizuku.manager.utils.HapticUtils.tap(v)
            val companionInstalled = data?.first ?: false
            if (companionInstalled) {
                setBusy(v.context, R.string.companion_action_disabling)
                scope.launch {
                    val success = runPrivilegedCommand("pm disable-user --user 0 ${StockShizukuCompat.PACKAGE}")
                    withContext(Dispatchers.Main) {
                        // Re-enable unconditionally so a reload Fail (e.g. binder death) doesn't
                        // leave the button permanently locked — onBind() resets it on success.
                        binding.button1.isEnabled = true
                        Toast.makeText(
                            v.context,
                            if (success) R.string.companion_disable_success else R.string.companion_disable_failure,
                            Toast.LENGTH_SHORT
                        ).show()
                        homeModel.reload()
                    }
                }
            } else if (v.context.packageName == StockShizukuCompat.PACKAGE) {
                // The dropin flavor's own applicationId IS StockShizukuCompat.PACKAGE, and the compat
                // shim APK targets that same package with the same signing key — installing it here
                // would silently pm-install the shim stub over this running app (#334). HomeAdapter
                // shouldn't route dropin builds into this branch (isCompatAppInstalled() now treats
                // self as already occupying the role), but this is the hard stop that actually
                // prevents the destructive install regardless of how this click was reached.
                Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
            } else if (StockShizukuCompat.isPackageOccupiedByDifferentSigner(v.context)) {
                // #412: pm install -r always fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE here
                // (opaquely, before this check existed) - something else, e.g. genuine stock
                // Shizuku, already occupies moe.shizuku.privileged.api with a different cert.
                Toast.makeText(v.context, R.string.compat_hub_install_signature_conflict, Toast.LENGTH_LONG).show()
            } else {
                setBusy(v.context, R.string.compat_hub_installing)
                scope.launch {
                    // Read APK bytes from assets into memory — avoids writing to any app-owned
                    // storage path before invoking the privileged process. On Android 11+, scoped
                    // storage blocks shell (UID 2000) from reading /sdcard/Android/data/<pkg>/,
                    // and internal cacheDir is sandboxed from shell too, so the old cp-then-install
                    // approach silently failed with "Permission denied" on these paths (#446).
                    val apkBytes = withContext(Dispatchers.IO) {
                        try {
                            v.context.assets.open("compat.apk").use { it.readBytes() }
                        } catch (e: Exception) {
                            Timber.tag("ShizukuCompanion").e(e, "compat.apk asset read failed")
                            null
                        }
                    }
                    if (apkBytes == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                            homeModel.reload()
                        }
                        return@launch
                    }

                    // rm -f first: a previous failed install may have left a chmod 000 file that
                    // would block the cat redirect. /data/local/tmp is world-writable so rm works
                    // even on a 000-permission file owned by shell UID 2000.
                    val installScript = "rm -f /data/local/tmp/compat.apk; cat > /data/local/tmp/compat.apk && chmod 644 /data/local/tmp/compat.apk && pm install -r /data/local/tmp/compat.apk 2>&1; echo EXIT:\$?; rm -f /data/local/tmp/compat.apk"
                    val installOutput = withContext(Dispatchers.IO) {
                        if (Shizuku.pingBinder()) {
                            // Pipe APK bytes directly to the shell's stdin — cat writes to
                            // /data/local/tmp (world-accessible by shell UID 2000) without
                            // ever touching app-private storage the privileged process can't read.
                            // cat finishes when we close stdin, then && runs pm install; no
                            // concurrent stdout/stdin needed so no deadlock risk.
                            val process = try {
                                Shizuku.newProcess(arrayOf("sh", "-c", installScript), null, null)
                            } catch (e: Exception) {
                                Timber.tag("ShizukuCompanion").w(e, "Shizuku.newProcess() threw")
                                null
                            }
                            if (process != null) {
                                try {
                                    process.outputStream.use { it.write(apkBytes) }
                                    process.inputStream.bufferedReader().readText().also { process.waitFor() }
                                } catch (e: Exception) {
                                    e.message ?: "unknown error"
                                } finally {
                                    try { process.destroy() } catch (_: Exception) {}
                                }
                            } else {
                                // newProcess() returned null — device restricts Shizuku process
                                // spawning (Samsung One UI 8 / Android 16 SELinux policy). Signal
                                // the caller to fall back to the system installer UI.
                                "NEWPROCESS_NULL"
                            }
                        } else if (MigrationHelper.isRootAvailable()) {
                            // Root can read internal storage — extract there and install directly.
                            val tmpApk = java.io.File(v.context.cacheDir, "compat.apk")
                            try {
                                tmpApk.writeBytes(apkBytes)
                                Shell.cmd("pm install -r '${tmpApk.absolutePath}' 2>&1; echo EXIT:\$?").exec().out.joinToString("\n")
                            } catch (e: Exception) {
                                e.message ?: "unknown error"
                            } finally {
                                try { tmpApk.delete() } catch (_: Exception) {}
                            }
                        } else {
                            "no privileged access available"
                        }
                    }

                    // If newProcess was blocked, prepare the APK in cacheDir for the system
                    // installer fallback (IO work done here so Main dispatch below is fast).
                    val systemInstallerUri = if (installOutput == "NEWPROCESS_NULL") {
                        withContext(Dispatchers.IO) {
                            try {
                                val tmpApk = java.io.File(v.context.cacheDir, "compat_install.apk")
                                tmpApk.writeBytes(apkBytes)
                                FileProvider.getUriForFile(
                                    v.context,
                                    "${v.context.packageName}.fileprovider",
                                    tmpApk
                                )
                            } catch (e: Exception) {
                                Timber.tag("ShizukuCompanion").e(e, "system installer fallback prep failed")
                                null
                            }
                        }
                    } else null

                    val success = installOutput.contains("EXIT:0")
                    if (!success && installOutput != "NEWPROCESS_NULL") {
                        Timber.tag("ShizukuCompanion").e("compat hub install failed: %s", installOutput.take(1000))
                    }
                    withContext(Dispatchers.Main) {
                        when {
                            success ->
                                Toast.makeText(v.context, R.string.compat_hub_install_success, Toast.LENGTH_SHORT).show()
                            systemInstallerUri != null -> {
                                // Device blocks newProcess — open system installer as fallback.
                                Toast.makeText(v.context, R.string.compat_hub_install_fail_newprocess, Toast.LENGTH_LONG).show()
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(systemInstallerUri, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    v.context.startActivity(intent)
                                } catch (e: Exception) {
                                    Timber.tag("ShizukuCompanion").e(e, "system installer launch failed")
                                }
                            }
                            installOutput.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                                Toast.makeText(v.context, R.string.compat_hub_install_fail_storage, Toast.LENGTH_SHORT).show()
                            installOutput.contains("INSTALL_FAILED_NO_MATCHING_ABIS") ->
                                Toast.makeText(v.context, R.string.compat_hub_install_fail_abi, Toast.LENGTH_SHORT).show()
                            installOutput.contains("INSTALL_FAILED_USER_RESTRICTED") ||
                            installOutput.contains("INSTALL_FAILED_VERIFICATION_FAILURE") ||
                            installOutput.contains("INSTALL_FAILED_BLOCKED") ||
                            installOutput.contains("INSTALL_FAILED_POLICY_ERROR") ->
                                Toast.makeText(v.context, R.string.compat_hub_install_fail_restricted, Toast.LENGTH_LONG).show()
                            else -> {
                                val errorSnippet = installOutput
                                    .lines()
                                    .firstOrNull { it.contains("INSTALL_FAILED") || it.contains("Failure") }
                                    ?.trim()
                                    ?.take(80)
                                if (errorSnippet != null) {
                                    Toast.makeText(
                                        v.context,
                                        v.context.getString(R.string.compat_hub_install_fail_detail, errorSnippet),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        homeModel.reload()
                    }
                }
            }
        }
        binding.button2.setOnClickListener { v ->
            af.shizuku.manager.utils.HapticUtils.tap(v)
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:${StockShizukuCompat.PACKAGE}")
            v.context.startActivity(intent)
        }
    }

    /** Disables the action button and shows a busy label so a slow pm call can't be double-tapped. */
    private fun setBusy(context: Context, labelRes: Int) {
        binding.button1.isEnabled = false
        binding.button1.text = context.getString(labelRes)
    }

    override fun onBind() {
        val companionInstalled = data?.first ?: false
        val compatHubInstalled = data?.second ?: false

        if (compatHubInstalled && !companionInstalled) {
            binding.title.setText(R.string.compat_hub_installed_title)
            binding.text1.setText(R.string.compat_hub_installed_desc)
            binding.button1.visibility = View.GONE
            binding.button2.visibility = View.GONE
        } else if (companionInstalled) {
            binding.title.setText(R.string.companion_conflict_title)
            binding.text1.setText(R.string.companion_conflict_description)
            binding.button1.setText(R.string.companion_action_disable)
            binding.button1.isEnabled = true
            binding.button1.visibility = View.VISIBLE
            binding.button2.visibility = View.VISIBLE
        } else {
            binding.title.setText(R.string.compat_hub_missing_title)
            binding.text1.setText(R.string.compat_hub_missing_desc)
            binding.button1.setText(R.string.compat_hub_install_btn)
            binding.button1.isEnabled = true
            binding.button1.visibility = View.VISIBLE
            binding.button2.visibility = View.GONE
        }
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_shizuku_companion")
    }
}
