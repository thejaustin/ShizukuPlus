package af.shizuku.manager.home

import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartAdbBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartAdbViewHolder(
    private val binding: HomeStartAdbBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus?>(containerBinding.root) {
    companion object {
        val CREATOR =
            Creator<ServiceStatus?> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartAdbBinding.inflate(inflater, outer.cardContent, true)
                StartAdbViewHolder(inner, outer)
            }
    }

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener {
            HomeEditMode.enter()
            true
        }
        binding.button1.setOnClickListener { v: View ->
            HapticUtils.tap(v)
            val context = v.context
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.home_adb_button_view_command)
                .setMessage(
                    HtmlCompat.fromHtml(
                        context.getString(
                            R.string.home_adb_dialog_view_command_message,
                            Starter.adbCommand,
                        ),
                    ),
                ).setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                    if (ClipboardUtils.put(context, Starter.adbCommand)) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.toast_copied_to_clipboard),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                    var intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                    intent =
                        Intent.createChooser(
                            intent,
                            context.getString(R.string.home_adb_dialog_view_command_button_send),
                        )
                    context.startActivity(intent)
                }.show()
        }
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartAdbViewHolder)
                false
            }
            setOnLongClickListener {
                HomeEditMode.enter()
                true
            }
        }
    }

    private val originalIcon = binding.icon.drawable

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_start_adb")

        val runningViaAdb = data?.isRunning == true && data?.uid != 0
        val descRes = if (runningViaAdb) R.string.home_adb_description_active else R.string.home_adb_description
        binding.text1.text =
            androidx.core.text.HtmlCompat
                .fromHtml(
                    context.getString(descRes, Helps.ADB.get()),
                    androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY,
                ).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
    }
}
