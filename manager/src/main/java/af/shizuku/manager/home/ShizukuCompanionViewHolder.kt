package af.shizuku.manager.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeShizukuCompanionBinding
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.StockShizukuCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

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
    private suspend fun runPrivilegedCommand(cmd: String): Boolean {
        return if (Shizuku.pingBinder()) {
            try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val success = process.waitFor() == 0
                process.destroy()
                success
            } catch (e: Exception) {
                false
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
                        Toast.makeText(
                            v.context,
                            if (success) R.string.companion_disable_success else R.string.companion_disable_failure,
                            Toast.LENGTH_SHORT
                        ).show()
                        homeModel.reload()
                    }
                }
            } else {
                // Must be on external storage, not the app's private cache/files dir: `pm install`
                // runs via a shell process spawned by Shizuku.newProcess (UID 2000) or root, neither
                // of which can read /data/user/0/<pkg>/... due to per-app UID sandboxing on internal
                // storage. getExternalFilesDir is readable by shell/root and is the same location
                // UpdateManager/UpdateInstaller already use successfully for APK installs.
                val dir = v.context.getExternalFilesDir(null) ?: v.context.cacheDir ?: v.context.filesDir ?: return@setOnClickListener
                val tmpApk = java.io.File(dir, "compat.apk")
                try {
                    v.context.assets.open("compat.apk").use { input ->
                        tmpApk.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(v.context, R.string.compat_hub_install_fail, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                setBusy(v.context, R.string.compat_hub_installing)
                scope.launch {
                    val tmpPath = "/data/local/tmp/compat.apk"
                    val success = runPrivilegedCommand("cp '${tmpApk.absolutePath}' '$tmpPath' && chmod 644 '$tmpPath' && pm install -r '$tmpPath' && rm '$tmpPath'")
                    try {
                        tmpApk.delete()
                    } catch (e: Exception) {
                        // ignore
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            v.context,
                            if (success) R.string.compat_hub_install_success else R.string.compat_hub_install_fail,
                            Toast.LENGTH_SHORT
                        ).show()
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
