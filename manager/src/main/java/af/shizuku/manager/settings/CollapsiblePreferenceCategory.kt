package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class CollapsiblePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    private var expanded = false
    private var isAnimating = false
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

        val cornerStyle = ShizukuSettings.getPreferences().getString("settings_card_corner_style", "global") ?: "global"
        if (cornerStyle != "global") {
            val card = holder.itemView as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                val density = card.context.resources.displayMetrics.density
                card.radius = when (cornerStyle) {
                    "sharp" -> 0f
                    "rounded" -> 12f * density
                    "large" -> 24f * density
                    "squircle" -> 28f * density
                    "xlarge" -> 36f * density
                    else -> card.radius
                }
            }
        }

        val arrow = holder.findViewById(R.id.category_arrow)
        if (!collapsible) {
            arrow?.visibility = android.view.View.GONE
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
            return
        }

        // Cancel any in-flight animator before snapping to current state on rebind —
        // otherwise a running ViewPropertyAnimator takes priority over the direct rotation setter
        // and can leave the arrow pointing the wrong way for the actual expanded state.
        arrow?.animate()?.cancel()
        arrow?.rotation = if (expanded) 180f else 0f
        updateExpandedStateDescription(holder.itemView)

        holder.itemView.setOnClickListener {
            // Guard against fast double-taps: a second tap before the arrow finishes rotating
            // would flip `expanded` twice and leave the arrow snapped to the wrong angle.
            if (isAnimating) return@setOnClickListener
            expanded = !expanded
            if (shouldPersist()) persistBoolean(expanded)
            // Animate arrow with M3E spring-style motion
            arrow?.animate()
                ?.rotation(if (expanded) 180f else 0f)
                ?.setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(300))
                ?.setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                ?.withStartAction { isAnimating = true }
                ?.withEndAction { isAnimating = false }
                ?.start()
            // updateChildren() already notifies the adapter per child via Preference.setVisible();
            // an additional notifyChanged() here used to schedule a rebind of this same header
            // ViewHolder mid-animation, which snapped the arrow's rotation back and forth against
            // the running ViewPropertyAnimator and could leave the RecyclerView's sync pass
            // needing a second click to fully settle on the expanded child list.
            updateChildren()
            updateExpandedStateDescription(holder.itemView)
            onExpansionChanged?.invoke(expanded)
        }
    }

    /** The rotating arrow is the only visual cue this header is an expand/collapse toggle -
     *  TalkBack users get neither that affordance nor a state change announcement without this. */
    private fun updateExpandedStateDescription(itemView: android.view.View) {
        ViewCompat.setStateDescription(
            itemView,
            itemView.context.getString(
                if (expanded) R.string.accessibility_state_expanded else R.string.accessibility_state_collapsed
            )
        )
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
