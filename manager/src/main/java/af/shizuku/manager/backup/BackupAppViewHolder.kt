package af.shizuku.manager.backup

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.databinding.ItemBackupAppBinding

class BackupAppViewHolder(private val binding: ItemBackupAppBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(
        entry: BackupViewModel.AppEntry,
        isBusy: Boolean,
        onBackup: ((BackupViewModel.AppEntry) -> Unit)?,
        onFreeze: ((BackupViewModel.AppEntry) -> Unit)?
    ) {
        val context = binding.root.context
        val pm = context.packageManager

        binding.appLabel.text = entry.label
        binding.packageName.text = entry.packageName

        try {
            val info = pm.getApplicationInfo(entry.packageName, 0)
            binding.appIcon.setImageDrawable(pm.getApplicationIcon(info))
        } catch (e: Exception) {
            binding.appIcon.setImageResource(R.drawable.ic_server_error_24)
        }

        binding.busyIndicator.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.btnBackup.isEnabled = !isBusy
        binding.btnBackup.setText(if (isBusy) R.string.backup_in_progress else R.string.backup_action_backup)

        binding.btnFreeze.isEnabled = !isBusy
        binding.btnFreeze.setText(
            if (entry.isFrozen) R.string.backup_action_unfreeze else R.string.backup_action_freeze
        )

        binding.btnBackup.setOnClickListener { if (!isBusy) onBackup?.invoke(entry) }
        binding.btnFreeze.setOnClickListener { if (!isBusy) onFreeze?.invoke(entry) }
    }
}
