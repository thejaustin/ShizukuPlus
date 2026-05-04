package af.shizuku.manager.utils

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import af.shizuku.manager.R

import af.shizuku.manager.databinding.EmptyStateViewBinding

/**
 * A reusable empty state view that displays an icon, title, description, and optional action button.
 * Theme-aware: works in both light and dark modes.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: EmptyStateViewBinding

    init {
        binding = EmptyStateViewBinding.inflate(LayoutInflater.from(context), this, true)

        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.EmptyStateView,
            0, 0
        ).apply {
            try {
                val iconRes = getResourceId(R.styleable.EmptyStateView_emptyIcon, R.drawable.ic_help_outline_24)
                val titleRes = getResourceId(R.styleable.EmptyStateView_emptyTitle, R.string.empty_state_title_no_results)
                val descriptionRes = getResourceId(R.styleable.EmptyStateView_emptyDescription, R.string.empty_state_description_no_results)
                val actionTextRes = getResourceId(R.styleable.EmptyStateView_emptyActionText, 0)

                setIcon(iconRes)
                setTitle(titleRes)
                setDescription(descriptionRes)
                if (actionTextRes != 0) {
                    setActionText(actionTextRes)
                }
            } finally {
                recycle()
            }
        }
    }

    /**
     * Set the icon to display.
     * @param iconRes Drawable resource ID for the icon
     */
    fun setIcon(@DrawableRes iconRes: Int) {
        binding.emptyStateIcon.setImageResource(iconRes)
    }

    /**
     * Set the title text.
     * @param titleRes String resource ID for the title
     */
    fun setTitle(@StringRes titleRes: Int) {
        binding.emptyStateTitle.setText(titleRes)
    }

    /**
     * Set the title text.
     * @param title Title string
     */
    fun setTitle(title: CharSequence) {
        binding.emptyStateTitle.text = title
    }

    /**
     * Set the description text.
     * @param descriptionRes String resource ID for the description
     */
    fun setDescription(@StringRes descriptionRes: Int) {
        binding.emptyStateDescription.setText(descriptionRes)
    }

    /**
     * Set the description text.
     * @param description Description string
     */
    fun setDescription(description: CharSequence) {
        binding.emptyStateDescription.text = description
    }

    /**
     * Set the action button text and make it visible.
     * @param actionTextRes String resource ID for the action button text
     */
    fun setActionText(@StringRes actionTextRes: Int) {
        binding.emptyStateActionButton.setText(actionTextRes)
        binding.emptyStateActionButton.visibility = View.VISIBLE
    }

    /**
     * Set the action button text and make it visible.
     * @param actionText Action button text
     */
    fun setActionText(actionText: CharSequence) {
        binding.emptyStateActionButton.text = actionText
        binding.emptyStateActionButton.visibility = View.VISIBLE
    }

    /**
     * Hide the action button.
     */
    fun hideActionButton() {
        binding.emptyStateActionButton.visibility = View.GONE
    }

    /**
     * Show the action button.
     */
    fun showActionButton() {
        if (binding.emptyStateActionButton.text.isNotEmpty()) {
            binding.emptyStateActionButton.visibility = View.VISIBLE
        }
    }

    /**
     * Set click listener for the action button.
     * @param listener Click listener
     */
    fun setActionClickListener(listener: OnClickListener?) {
        binding.emptyStateActionButton.setOnClickListener(listener)
    }

    /**
     * Set click listener for the action button.
     * @param listener Click listener
     */
    fun setActionClickListener(listener: () -> Unit) {
        binding.emptyStateActionButton.setOnClickListener { listener() }
    }
}
