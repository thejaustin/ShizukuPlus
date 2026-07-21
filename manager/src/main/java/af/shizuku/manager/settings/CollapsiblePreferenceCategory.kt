package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class CollapsiblePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    private var expanded = false
    var onExpansionChanged: ((Boolean) -> Unit)? = null

    private var defaultExpanded = false
    private var collapsible = true

    // Keys of children that are conditionally unavailable (e.g. hidden by an OWNER fragment
    // based on OS version or another setting). Such children must stay hidden even when the
    // category is expanded, and the collapse toggle must not resurrect them.
    private val unavailableChildKeys = mutableSetOf<String>()

    init {
        layoutResource = R.layout.collapsible_preference_category_card

        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.defaultValue, R.attr.collapsible))
        defaultExpanded = a.getBoolean(0, false)
        collapsible = a.getBoolean(1, true)
        a.recycle()

        // A non-collapsible category (e.g. a top-level nav menu) still needs the M3E card-grouping
        // treatment from isHeader()/tag, but must never hide its own entries - there'd be no other
        // way back to them.
        expanded = if (collapsible) defaultExpanded else true
        isSelectable = collapsible
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.tag = "category_header"

        val arrow = holder.findViewById(R.id.category_arrow)
        if (!collapsible) {
            arrow?.visibility = android.view.View.GONE
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
            return
        }

        // Sync arrow to current state without animation on first bind
        arrow?.rotation = if (expanded) 180f else 0f

        holder.itemView.setOnClickListener {
            expanded = !expanded
            if (shouldPersist()) persistBoolean(expanded)
            // Animate arrow with M3E spring-style motion
            arrow?.animate()
                ?.rotation(if (expanded) 180f else 0f)
                ?.setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(300))
                ?.setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                ?.start()
            // updateChildren() already notifies the adapter per child via Preference.setVisible();
            // an additional notifyChanged() here used to schedule a rebind of this same header
            // ViewHolder mid-animation, which snapped the arrow's rotation back and forth against
            // the running ViewPropertyAnimator and could leave the RecyclerView's sync pass
            // needing a second click to fully settle on the expanded child list.
            updateChildren()
            onExpansionChanged?.invoke(expanded)
        }
    }

    fun isExpanded() = expanded

    fun setExpanded(expanded: Boolean) {
        if (this.expanded != expanded) {
            this.expanded = expanded
            if (shouldPersist()) persistBoolean(expanded)
            updateChildren()
            onExpansionChanged?.invoke(expanded)
            notifyChanged()
        }
    }

    /**
     * Declare whether a child should participate in the expand/collapse cycle. A child marked
     * unavailable stays hidden regardless of the expanded state. Owners should call this instead
     * of setting the child's [Preference.isVisible] directly, so the collapse toggle does not
     * override the condition.
     */
    fun setChildAvailable(key: String, available: Boolean) {
        val changed = if (available) unavailableChildKeys.remove(key) else unavailableChildKeys.add(key)
        if (changed) updateChildren()
    }

    private fun updateChildren() {
        for (i in 0 until preferenceCount) {
            val child = getPreference(i)
            child.isVisible = expanded && !unavailableChildKeys.contains(child.key)
        }
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        // Restore persisted state if we have a key, otherwise use defaultValue. A non-collapsible
        // category always stays expanded regardless, even if a key is added later.
        if (collapsible && shouldPersist()) {
            expanded = getPersistedBoolean(defaultExpanded)
        }
        updateChildren()
    }

    override fun addPreference(preference: Preference): Boolean {
        val result = super.addPreference(preference)
        if (result) {
            preference.isVisible = expanded && !unavailableChildKeys.contains(preference.key)
        }
        return result
    }
}
