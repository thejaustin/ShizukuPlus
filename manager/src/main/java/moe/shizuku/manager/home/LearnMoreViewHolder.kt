package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeLearnMoreBinding
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class LearnMoreViewHolder(binding: HomeLearnMoreBinding, root: View) : BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeLearnMoreBinding.inflate(inflater, outer.cardContent, true)
            LearnMoreViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener { v: View -> CustomTabsHelper.launchUrlOrCopy(v.context, Helps.HOME.get()) }
        itemView.findViewById<View>(R.id.drag_handle).apply {
            visibility = View.VISIBLE
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@LearnMoreViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
        itemView.findViewById<View>(R.id.remove_btn).setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(HomeAdapter.ID_LEARN_MORE)
        }
    }

    override fun onBind() {
        itemView.findViewById<View>(R.id.remove_btn).isVisible = HomeEditMode.isActive
    }
}
