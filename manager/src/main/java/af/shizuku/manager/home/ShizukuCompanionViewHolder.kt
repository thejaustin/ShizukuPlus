package af.shizuku.manager.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeShizukuCompanionBinding
import af.shizuku.manager.utils.StockShizukuCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class ShizukuCompanionViewHolder(
    private val binding: HomeShizukuCompanionBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Boolean>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Boolean> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeShizukuCompanionBinding.inflate(inflater, outer.cardContent, true)
            ShizukuCompanionViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@ShizukuCompanionViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
        containerBinding.removeBtn.setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(HomeAdapter.ID_COMPANION)
        }
        binding.button1.setOnClickListener { v ->
            StockShizukuCompat.launch(v.context)
        }
    }

    override fun onBind() {
        val installed = data ?: false
        if (installed) {
            binding.text1.setText(R.string.home_companion_description)
            binding.button1.setText(R.string.home_companion_open)
            binding.button1.isEnabled = true
        } else {
            binding.text1.setText(R.string.home_companion_not_installed)
            binding.button1.setText(R.string.home_companion_install)
            binding.button1.isEnabled = false
        }
        HomeEditMode.applyOverlay(containerBinding)
    }
}
