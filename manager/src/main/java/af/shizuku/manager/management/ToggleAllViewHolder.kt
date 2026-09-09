package af.shizuku.manager.management

import android.content.pm.PackageInfo
import timber.log.Timber
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.databinding.AppListToggleAllBinding
import af.shizuku.manager.management.AppsAdapter.HeaderMarker
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class ToggleAllViewHolder(private val binding: AppListToggleAllBinding) : BaseViewHolder<HeaderMarker>(binding.root), View.OnClickListener {

    companion object {
        @JvmField
        val CREATOR = Creator<HeaderMarker> { inflater: LayoutInflater, parent: ViewGroup? -> ToggleAllViewHolder(AppListToggleAllBinding.inflate(inflater, parent, false)) }
        private const val TAG = "ToggleAllViewHolder"
    }

    private val switchWidget get() = binding.switchWidget
    private var job: Job? = null

    init {
        itemView.filterTouchesWhenObscured = true
        itemView.setOnClickListener(this)
    }

    // areAllEnabled()/setAllEnabled() each make one Shizuku binder call per installed app. Doing
    // that synchronously on the main thread - as this holder previously did in both onClick() and
    // onBind() - is an ANR risk that fires on every single per-app toggle (AppViewHolder rebinds
    // this header via notifyItemChanged(0) after each grant/revoke), not just an explicit
    // "toggle all" tap. Snapshot the list on the calling (main) thread to avoid a concurrent
    // modification if the adapter's backing list changes while the IO work runs.
    override fun onClick(v: View) {
        val items = snapshotPackages()
        switchWidget.isEnabled = false
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            val makeEnabled = !areAllEnabled(items)
            setAllEnabled(items, makeEnabled)
            withContext(Dispatchers.Main) {
                switchWidget.isEnabled = true
                switchWidget.isChecked = makeEnabled
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onBind() {
        refreshChecked()
    }

    override fun onBind(payloads: List<Any>) {
        refreshChecked()
    }

    override fun onRecycle() {
        job?.cancel()
    }

    private fun refreshChecked() {
        job?.cancel()
        val items = snapshotPackages()
        job = CoroutineScope(Dispatchers.IO).launch {
            val enabled = areAllEnabled(items)
            withContext(Dispatchers.Main) {
                switchWidget.isChecked = enabled
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotPackages(): List<PackageInfo> =
        (adapter.getItems() as ArrayList<*>).filterIsInstance<PackageInfo>()

    private fun setAllEnabled(items: List<PackageInfo>, enabled: Boolean) {
        for (pi in items) {
            val appInfo = pi.applicationInfo ?: continue
            try {
                if (enabled) {
                    AuthorizationManager.grant(pi.packageName, appInfo.uid)
                } else {
                    AuthorizationManager.revoke(pi.packageName, appInfo.uid)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to ${if (enabled) "grant" else "revoke"} permission for ${pi.packageName}")
            }
        }
    }

    private fun areAllEnabled(items: List<PackageInfo>): Boolean {
        if (items.isEmpty()) {
            return false
        }
        for (pi in items) {
            val appInfo = pi.applicationInfo ?: return false
            try {
                if (!AuthorizationManager.granted(pi.packageName, appInfo.uid)) return false
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Failed to check grant status for ${pi.packageName}")
                return false
            }
        }
        return true
    }
}
