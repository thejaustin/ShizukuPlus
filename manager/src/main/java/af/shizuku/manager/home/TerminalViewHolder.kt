package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeTerminalBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.shell.ShellTutorialActivity
import af.shizuku.manager.utils.IconStyleHelper
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class TerminalViewHolder(
    private val binding: HomeTerminalBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus>(containerBinding.root),
    View.OnClickListener {
    companion object {
        val CREATOR =
            Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeTerminalBinding.inflate(inflater, outer.cardContent, true)
                TerminalViewHolder(inner, outer)
            }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        HomeEditMode.bindCardGestures(containerBinding, this)
    }

    private inline val summary get() = binding.text2
    private val originalIcon = binding.icon.drawable

    override fun onBind() {
        val context = itemView.context
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_terminal")
        if (!data.isRunning) {
            summary.text =
                context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        } else {
            summary.text = context.getString(R.string.home_terminal_description)
        }
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, ShellTutorialActivity::class.java),
        )
    }
}
