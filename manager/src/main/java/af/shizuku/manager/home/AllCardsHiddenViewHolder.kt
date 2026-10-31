package af.shizuku.manager.home

import af.shizuku.manager.databinding.HomeAllHiddenBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.utils.HapticUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/** Shown under the status card when every other card is hidden; its data restores them. */
class AllCardsHiddenViewHolder(
    private val binding: HomeAllHiddenBinding,
    root: View,
) : BaseViewHolder<() -> Unit>(root) {
    companion object {
        val CREATOR =
            Creator<() -> Unit> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                outer.root.isClickable = false
                outer.root.isFocusable = false
                val inner = HomeAllHiddenBinding.inflate(inflater, outer.cardContent, true)
                AllCardsHiddenViewHolder(inner, outer.root)
            }
    }

    init {
        binding.button1.setOnClickListener { v ->
            HapticUtils.success(v)
            data?.invoke()
        }
    }
}
