package af.shizuku.manager.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeLearnMoreBinding
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class LearnMoreViewHolder(
    private val binding: HomeLearnMoreBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeLearnMoreBinding.inflate(inflater, outer.cardContent, true)
            LearnMoreViewHolder(inner, outer)
        }
    }

    private val originalIcon = binding.icon.drawable

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnClickListener { v: View -> CustomTabsHelper.launchUrlOrCopy(v.context, Helps.HOME.get()) }
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@LearnMoreViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }

    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_learn_more")
    }
}
