package af.shizuku.manager.home

import af.shizuku.manager.databinding.HomeExtraStepRequiredBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class AdbPermissionLimitedViewHolder(
    private val binding: HomeExtraStepRequiredBinding,
    root: View,
) : BaseViewHolder<Any?>(root) {
    companion object {
        val CREATOR =
            Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                (outer.root as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(
                    outer.root.context.themeColor(com.google.android.material.R.attr.colorErrorContainer),
                )
                val inner = HomeExtraStepRequiredBinding.inflate(inflater, outer.cardContent, true)
                AdbPermissionLimitedViewHolder(inner, outer.root)
            }
    }

    init {
        itemView.applySpringTouch()
        binding.button1.setOnClickListener { v: View ->
            af.shizuku.manager.knowledgebase.KnowledgebaseHelper.show(
                v.context,
                af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_WIRELESS_ADB,
            )
        }
    }

    override fun onBind() {
        // Card body is already colorErrorContainer (soft red), so the pill must be darker to stand
        // out. colorOnErrorContainer (dark red) as the pill background with colorOnError (white)
        // as the icon tint gives a clearly legible warning icon with proper M3 contrast ratios.
        // Previous code had the tintColor set to colorErrorContainer (light red on dark red) which
        // produced near-invisible icon contrast.
        val context = binding.icon.context
        val pillColor = context.themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        val tintColor = context.themeColor(com.google.android.material.R.attr.colorOnError)
        IconStyleHelper.applyToStatusCardIcon(binding.icon, pillColor = pillColor, tintColor = tintColor)
    }
}
