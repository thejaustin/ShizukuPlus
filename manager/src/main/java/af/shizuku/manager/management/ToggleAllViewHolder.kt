package af.shizuku.manager.management

import android.content.pm.PackageInfo
import timber.log.Timber
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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

    init {
        itemView.filterTouchesWhenObscured = true
        itemView.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        setAllEnabled(!areAllEnabled())
        switchWidget.isChecked = areAllEnabled()
    }

    override fun onBind() {
        switchWidget.isChecked = areAllEnabled()
    }

    override fun onBind(payloads: List<Any>) {
        switchWidget.isChecked = areAllEnabled()
    }

    override fun onRecycle() {}

    private fun setAllEnabled(enabled: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val items = adapter.getItems() as ArrayList<*>
        for (item in items) {
            if (item is PackageInfo) {
                val pi = item
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
        adapter.notifyDataSetChanged()
    }

    private fun areAllEnabled(): Boolean {
        @Suppress("UNCHECKED_CAST")
        val items = adapter.getItems() as ArrayList<*>
        if (items.size <= 1) {
            return false
        }
        for (item in items) {
            if (item is PackageInfo) {
                val pi = item
                val appInfo = pi.applicationInfo ?: return false
                try {
                    if (!AuthorizationManager.granted(pi.packageName, appInfo.uid)) return false
                } catch (e: Exception) {
                    Timber.tag(TAG).d(e, "Failed to check grant status for ${pi.packageName}")
                    return false
                }
            }
        }
        return true
    }
}
