package moe.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import moe.shizuku.manager.R

class CollapsiblePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    private var expanded = false
    var onExpansionChanged: ((Boolean) -> Unit)? = null

    init {
        layoutResource = R.layout.collapsible_preference_category_card
        isSelectable = true
        
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.defaultValue))
        expanded = a.getBoolean(0, false)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        // In M3E Expressive, we toggle by clicking the header label
        // We've removed the arrow as per latest guidelines
        
        holder.itemView.tag = "category_header"
        holder.itemView.setOnClickListener {
            expanded = !expanded
            updateChildren()
            onExpansionChanged?.invoke(expanded)
            notifyChanged()
        }
        
        // Ensure children visibility is in sync
        updateChildren()
    }

    fun isExpanded() = expanded

    fun setExpanded(expanded: Boolean) {
        if (this.expanded != expanded) {
            this.expanded = expanded
            updateChildren()
            onExpansionChanged?.invoke(expanded)
            notifyChanged()
        }
    }

    private fun updateChildren() {
        for (i in 0 until preferenceCount) {
            getPreference(i).isVisible = expanded
        }
    }
    
    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateChildren()
    }

    override fun addPreference(preference: Preference): Boolean {
        val result = super.addPreference(preference)
        if (result) {
            preference.isVisible = expanded
        }
        return result
    }
}
