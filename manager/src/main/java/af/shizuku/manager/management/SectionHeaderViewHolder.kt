package af.shizuku.manager.management

import af.shizuku.manager.R
import af.shizuku.manager.databinding.AppListSectionHeaderBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/** One of the two group labels in the All apps list. */
data class AppSection(
    val allowed: Boolean,
    val count: Int,
)

class SectionHeaderViewHolder(
    private val binding: AppListSectionHeaderBinding,
) : BaseViewHolder<AppSection>(binding.root) {
    override fun onBind() {
        val res = if (data.allowed) R.string.app_management_section_allowed else R.string.app_management_section_not_allowed
        binding.root.text = itemView.context.getString(res, data.count)
    }

    companion object {
        @JvmField
        val CREATOR =
            Creator<AppSection> { inflater: LayoutInflater, parent: ViewGroup? ->
                SectionHeaderViewHolder(AppListSectionHeaderBinding.inflate(inflater, parent, false))
            }
    }
}
