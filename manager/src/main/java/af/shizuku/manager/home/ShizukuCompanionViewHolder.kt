package af.shizuku.manager.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeShizukuCompanionBinding
import af.shizuku.manager.utils.StockShizukuCompat
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import rikka.shizuku.Shizuku
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

        binding.button1.setOnClickListener { v ->
            val cmd = "pm disable-user --user 0 ${StockShizukuCompat.PACKAGE}"
            if (Shizuku.pingBinder()) {
                try {
                    Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                    Toast.makeText(v.context, R.string.companion_disable_success, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                }
            } else if (af.shizuku.manager.migration.MigrationHelper.isRootAvailable()) {
                try {
                    com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                    Toast.makeText(v.context, R.string.companion_disable_success, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(v.context, R.string.companion_disable_failure, Toast.LENGTH_SHORT).show()
            }
        }
        binding.button2.setOnClickListener { v ->
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:${StockShizukuCompat.PACKAGE}")
            v.context.startActivity(intent)
        }
    }

    override fun onBind() {
        val installed = data ?: false
        if (installed) {
            binding.title.setText(R.string.companion_conflict_title)
            binding.text1.setText(R.string.companion_conflict_description)
            binding.button1.setText(R.string.companion_action_disable)
            binding.button1.isEnabled = true
            binding.button2.visibility = View.VISIBLE
        } else {
            binding.title.setText(R.string.home_companion_title)
            binding.text1.setText(R.string.home_companion_not_installed)
            binding.button1.setText(R.string.home_companion_install)
            binding.button1.isEnabled = false
            binding.button2.visibility = View.GONE
        }
        HomeEditMode.applyOverlay(containerBinding)
    }
}
