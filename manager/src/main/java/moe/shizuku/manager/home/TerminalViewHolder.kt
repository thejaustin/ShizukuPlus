package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeTerminalBinding
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.shell.ShellTutorialActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

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
        containerBinding.dragHandle.apply {
            visibility = View.VISIBLE
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    HomeEditMode.startDragCallback?.invoke(this@TerminalViewHolder)
                }
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
        containerBinding.removeBtn.setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(HomeAdapter.ID_TERMINAL)
        }
    }

    private inline val summary get() = binding.text2

    override fun onBind() {
        val context = itemView.context
        containerBinding.removeBtn.isVisible = HomeEditMode.isActive
        containerBinding.dragHandle.isVisible = HomeEditMode.isActive
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
        v.context.startActivity(Intent(v.context, ShellTutorialActivity::class.java))
    }
}
