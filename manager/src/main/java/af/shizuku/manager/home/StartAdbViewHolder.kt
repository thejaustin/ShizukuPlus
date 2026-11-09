package af.shizuku.manager.home

import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartAdbBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.AdaptiveRecommendationManager
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.checkbox.MaterialCheckBox
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

        fun showViewCommandDialog(context: android.content.Context) {
            val density = context.resources.displayMetrics.density
            val padH = (24 * density).toInt()
            val padTop = (12 * density).toInt()
            val padBottom = (4 * density).toInt()

            val container =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padH, padTop, padH, padBottom)
                }

            fun formatMessage(cmd: String): CharSequence =
                HtmlCompat.fromHtml(
                    context.getString(
                        R.string.home_adb_dialog_view_command_message,
                        cmd,
                    ),
                )

            val messageView =
                TextView(context).apply {
                    text = formatMessage(Starter.adbCommand)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    movementMethod = LinkMovementMethod.getInstance()
                }
            container.addView(messageView)

            val tcpCheckbox =
                MaterialCheckBox(context).apply {
                    text = context.getString(R.string.home_adb_dialog_tcp_toggle)
                    isChecked = ShizukuSettings.getTcpMode()
                    val lp =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = (16 * density).toInt()
                            }
                    layoutParams = lp
                    setOnCheckedChangeListener { _, checked ->
                        ShizukuSettings.setTcpMode(checked)
                        messageView.text = formatMessage(Starter.adbCommand)
                        if (checked) {
                            AdaptiveRecommendationManager.maybeRecommendTcpNetworkGuard(context)
                        }
                    }
                }
            container.addView(tcpCheckbox)

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.home_adb_button_view_command)
                .setView(container)
                .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                    val cmd = Starter.adbCommand
                    if (ClipboardUtils.put(context, cmd)) {
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.toast_copied_to_clipboard),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                    val cmd = Starter.adbCommand
                    var intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, cmd)
                    intent =
                        Intent.createChooser(
                            intent,
                            context.getString(R.string.home_adb_dialog_view_command_button_send),
                        )
                    context.startActivity(intent)
                }.show()
        }
    }

    init {
        HomeEditMode.bindCardGestures(containerBinding, this)
        binding.button1.setOnClickListener { v: View ->
            HapticUtils.tap(v)
            showViewCommandDialog(v.context)
        }
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
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
