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
        binding.removeBtn.isVisible = isActive
        binding.dragHandle.isVisible = isActive
        val res = binding.cardContent.resources
        val base = res.getDimensionPixelSize(R.dimen.card_content_padding)
        val handleClearance = if (isActive)
            (40 * res.displayMetrics.density).toInt() else 0
        binding.cardContent.updatePaddingRelative(end = base + handleClearance)
    }
}
