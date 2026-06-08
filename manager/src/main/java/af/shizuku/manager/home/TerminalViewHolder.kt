package af.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import androidx.core.view.isVisible
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeTerminalBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.shell.ShellTutorialActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

import af.shizuku.manager.utils.MotionUtils.applySpringTouch

class TerminalViewHolder(
    private val binding: HomeTerminalBinding,
    private val containerBinding: HomeItemContainerBinding
) : BaseViewHolder<ServiceStatus>(containerBinding.root),
    View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeTerminalBinding.inflate(inflater, outer.cardContent, true)
            TerminalViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.root.applySpringTouch()
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    HomeEditMode.startDragCallback?.invoke(this@TerminalViewHolder)
                }
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }

    }

    private inline val summary get() = binding.text2

    override fun onBind() {
        val context = itemView.context
        HomeEditMode.applyOverlay(containerBinding)
        if (!data.isRunning) {
            containerBinding.root.isEnabled = false
            summary.text =
                context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        } else {
            containerBinding.root.isEnabled = true
            summary.text = context.getString(R.string.home_terminal_description)
        }
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, ShellTutorialActivity::class.java),
            binding.icon, "icon_terminal"
        )
    }
}
