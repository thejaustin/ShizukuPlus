package af.shizuku.manager.management

import af.shizuku.manager.R
import af.shizuku.manager.databinding.AppListSectionHeaderBinding
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
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
        // Allowed reads in the accent colour, Not allowed stays muted; the dot carries the same colour.
        val color =
            MaterialColors.getColor(
                binding.root,
                if (data.allowed) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        binding.root.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(binding.root, ColorStateList.valueOf(color))
    }

    companion object {
        @JvmField
        val CREATOR =
            Creator<AppSection> { inflater: LayoutInflater, parent: ViewGroup? ->
                SectionHeaderViewHolder(AppListSectionHeaderBinding.inflate(inflater, parent, false))
            }
    }
}
