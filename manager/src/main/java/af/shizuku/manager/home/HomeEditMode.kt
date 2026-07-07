package af.shizuku.manager.home

import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding

object HomeEditMode {
    var isActive: Boolean = false
        private set

    var onChanged: (() -> Unit)? = null
    var startDragCallback: ((RecyclerView.ViewHolder) -> Unit)? = null
    var removeCardCallback: ((Long) -> Unit)? = null

    fun enter() {
        if (!isActive) {
            isActive = true
            io.sentry.Sentry.addBreadcrumb("HomeEditMode: enter()")
            onChanged?.invoke()
        }
    }

    fun exit() {
        if (isActive) {
            isActive = false
            onChanged?.invoke()
        }
    }

    fun toggle() {
        if (isActive) exit() else enter()
    }

    /** Toggle drag handle / remove button visibility AND reserve end-padding so
     *  the overlay icons don't sit on top of card title/summary text. */
    fun applyOverlay(binding: HomeItemContainerBinding) {
        io.sentry.Sentry.addBreadcrumb("HomeEditMode: applyOverlay() isActive=$isActive")
        binding.removeBtn.isVisible = isActive
        binding.dragHandle.isVisible = isActive

        val isHidden = binding.root.tag as? Boolean ?: false

        if (isActive && isHidden) {
            binding.cardContent.alpha = 0.45f
            binding.dragHandle.alpha = 0.35f // Fade drag handle even more
            binding.removeBtn.setImageResource(R.drawable.ic_add_24)
            val activeColor = binding.root.context.getColor(R.color.system_accent1_600)
            binding.removeBtn.imageTintList = android.content.res.ColorStateList.valueOf(activeColor)
        } else {
            binding.cardContent.alpha = 1.0f
            binding.dragHandle.alpha = 0.85f // Restore original drag handle alpha
            binding.removeBtn.setImageResource(R.drawable.ic_close_24)
            val errorTint = android.util.TypedValue()
            binding.root.context.theme.resolveAttribute(android.R.attr.colorError, errorTint, true)
            binding.removeBtn.imageTintList = android.content.res.ColorStateList.valueOf(errorTint.data)
        }

        val res = binding.cardContent.resources
        val base = res.getDimensionPixelSize(R.dimen.card_content_padding)
        // drag_handle and remove_btn now sit side-by-side in a single top-end row (40dp each +
        // 4dp gap + 4dp row margin) instead of stacked/overlapping on the same corner; reserve
        // clearance for the whole row so content never sits under either control.
        val overlayClearance = if (isActive)
            (92 * res.displayMetrics.density).toInt() else 0
        binding.cardContent.updatePaddingRelative(end = base + overlayClearance)
    }
}
