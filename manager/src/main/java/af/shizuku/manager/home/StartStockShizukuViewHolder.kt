package af.shizuku.manager.home

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.core.content.asActivity
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartRootBinding
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.StockShizukuCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartStockShizukuViewHolder(
    private val binding: HomeStartRootBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
) : BaseViewHolder<Boolean>(containerBinding.root) {

    companion object {
        fun creator(scope: CoroutineScope): Creator<Boolean> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartRootBinding.inflate(inflater, outer.cardContent, true)
                StartStockShizukuViewHolder(inner, outer, scope)
            }
        }
    }

    private inline val start get() = binding.button1
    private inline val restart get() = binding.button2

    init {
        val listener = View.OnClickListener { v: View -> onStartClicked(v) }
        start.setOnClickListener(listener)
        restart.visibility = View.GONE
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun onStartClicked(v: View) {
        if (af.shizuku.manager.migration.MigrationHelper.isRootAvailable()) {
            val starterCmd = af.shizuku.manager.starter.Starter.internalCommand
            val cmd = "am force-stop moe.shizuku.privileged.api && am force-stop af.shizuku.plus.api && nohup sh -c 'sleep 1 && $starterCmd' >/dev/null 2>&1 &"
            val activity = v.context.asActivity<android.app.Activity>() ?: return
            start.isEnabled = false
            // Shell.cmd().exec() runs the su/shell invocation synchronously; on the calling
            // (main) thread that risks jank or an ANR if root takes a moment to attach.
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                } catch (e: Exception) {
                    // Ignore
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    start.isEnabled = true
                    android.widget.Toast.makeText(activity, R.string.home_stock_restart_via_root, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val activity = v.context.asActivity<android.app.Activity>() ?: return
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.home_stock_incompatible_title)
                .setMessage(R.string.home_stock_incompatible_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onBind() {
        start.isEnabled = true
        start.text = binding.root.context.getString(R.string.home_stock_fix_conflict)
        binding.title.text = binding.root.context.getString(R.string.home_stock_incompatible_card_title)
        binding.text1.text = binding.root.context.getString(R.string.home_stock_incompatible_card_message)
        binding.icon.setImageResource(R.drawable.ic_warning_24)

        // Proper Material error-container two-tone instead of a hardcoded Color.RED tint on the
        // default secondary/tertiary-container pill, so this warning reads correctly in both
        // light/dark themes and shares the same shape as every other Two-Tone icon in the app.
        val context = binding.icon.context
        val errorContainer = context.themeColor(com.google.android.material.R.attr.colorErrorContainer)
        val onErrorContainer = context.themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        af.shizuku.manager.utils.IconStyleHelper.applyToStatusCardIcon(binding.icon, pillColor = errorContainer, tintColor = onErrorContainer)
    }
}
