package moe.shizuku.manager.home

import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartAdbBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.starter.Starter
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartAdbViewHolder(
    binding: HomeStartAdbBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartAdbBinding.inflate(inflater, outer.cardContent, true)
            StartAdbViewHolder(inner, outer)
        }
    }

    init {
        binding.button1.setOnClickListener { v: View ->
            val context = v.context
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.home_adb_button_view_command)
                .setMessage(
                    HtmlCompat.fromHtml(
                        context.getString(
                            R.string.home_adb_dialog_view_command_message,
                            Starter.adbCommand
                        )
                    )
                )
                .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                    if (ClipboardUtils.put(context, Starter.adbCommand)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_copied_to_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                    var intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                    intent = Intent.createChooser(
                        intent,
                        context.getString(R.string.home_adb_dialog_view_command_button_send)
                    )
                    context.startActivity(intent)
                }
                .show()
        }
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
        binding.text1.text = context.getString(R.string.home_adb_description, Helps.ADB.get())
            .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        containerBinding.dragHandle.apply {
            visibility = View.VISIBLE
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartAdbViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
        containerBinding.removeBtn.setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(HomeAdapter.ID_START_ADB)
        }
    }

    override fun onBind() {
        containerBinding.removeBtn.isVisible = HomeEditMode.isActive
        containerBinding.dragHandle.isVisible = HomeEditMode.isActive

        // Handle Expressive Shapes
        if (moe.shizuku.manager.ShizukuSettings.isExpressiveShapesEnabled()) {
            val shapeStyle = moe.shizuku.manager.ShizukuSettings.getShapeStyle()
            val bgRes = when (shapeStyle) {
                "zen" -> R.drawable.shape_expressive_leaf_background
                else -> R.drawable.shape_circle_icon_background
            }
            itemView.findViewById<View>(android.R.id.icon)?.setBackgroundResource(bgRes)
        } else {
            itemView.findViewById<View>(android.R.id.icon)?.setBackgroundResource(R.drawable.shape_circle_icon_background)
        }
    }
}
