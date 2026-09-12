package af.shizuku.manager.backup

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import af.shizuku.manager.databinding.ItemBackupAppBinding

class BackupAdapter : ListAdapter<BackupViewModel.AppEntry, BackupAppViewHolder>(DIFF) {

    var onBackupClick: ((BackupViewModel.AppEntry) -> Unit)? = null
    var onFreezeClick: ((BackupViewModel.AppEntry) -> Unit)? = null

    private var busyPackages: Set<String> = emptySet()

    fun setBusy(packages: Set<String>) {
        val old = busyPackages
        busyPackages = packages
        // Notify only items that changed busy state to avoid full rebind.
        for (i in 0 until itemCount) {
            val pkg = getItem(i).packageName
            if ((pkg in old) != (pkg in packages)) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BackupAppViewHolder {
        val binding = ItemBackupAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BackupAppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BackupAppViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry, entry.packageName in busyPackages, onBackupClick, onFreezeClick)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BackupViewModel.AppEntry>() {
            override fun areItemsTheSame(
                oldItem: BackupViewModel.AppEntry,
                newItem: BackupViewModel.AppEntry
            ) = oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(
                oldItem: BackupViewModel.AppEntry,
                newItem: BackupViewModel.AppEntry
            ) = oldItem == newItem
        }
    }
}
