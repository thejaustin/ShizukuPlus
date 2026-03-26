package moe.shizuku.manager.home

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeServerStatusBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

class ServerStatusViewHolder(private val binding: HomeServerStatusBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    private val cardView: com.google.android.material.card.MaterialCardView = itemView as com.google.android.material.card.MaterialCardView

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeServerStatusBinding.inflate(inflater, outer.cardContent, true)
            ServerStatusViewHolder(inner, outer.root)
        }
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon
    private inline val logButton get() = binding.btnActivityLog

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        
        // S-Pen / DeX Mouse Hover Effect (Expressive Polish)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            itemView.setOnHoverListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                        v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(8f).setDuration(200).start()
                        true
                    }
                    android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                        v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                        true
                    }
                    else -> false
                }
            }
        }

        logButton.visibility = if (ok && moe.shizuku.manager.ShizukuSettings.showActivityLogHome()) View.VISIBLE else View.GONE
        logButton.setOnClickListener {
            context.startActivity(android.content.Intent(context, moe.shizuku.manager.settings.ActivityLogActivity::class.java))
        }

        // Expressive M3 status-based styling
        val colorAttr = if (ok) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorErrorContainer
        val onColorAttr = if (ok) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnErrorContainer
        
        // Handle Expressive Shapes
        if (moe.shizuku.manager.ShizukuSettings.isExpressiveShapesEnabled()) {
            val shapeStyle = moe.shizuku.manager.ShizukuSettings.getShapeStyle()
            val bgRes = when (shapeStyle) {
                "zen" -> R.drawable.shape_droplet_background
                else -> R.drawable.shape_circle_icon_background // Use circle as default if rikka_rect is missing
            }
            iconView.setBackgroundResource(bgRes)
        } else {
            iconView.setBackgroundResource(R.drawable.shape_circle_icon_background)
        }

        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(colorAttr, typedValue, true)
        cardView.setCardBackgroundColor(typedValue.data)
        
        context.theme.resolveAttribute(onColorAttr, typedValue, true)
        val onColor = typedValue.data
        textView.setTextColor(onColor)
        summaryView.setTextColor(onColor)
        iconView.backgroundTintList = android.content.res.ColorStateList.valueOf(onColor)
        
        val iconColorAttr = if (ok) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorErrorContainer
        context.theme.resolveAttribute(iconColorAttr, typedValue, true)
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(typedValue.data)

        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24dp))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24dp))
        }
        val user = if (isRoot) context.getString(R.string.home_status_service_user_root) else context.getString(R.string.home_status_service_user_adb)
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            if (apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    "${apiVersion}.${patchVersion}",
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                context.getString(R.string.home_status_service_version, user, "${apiVersion}.${patchVersion}")
            }
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.visibility = if (TextUtils.isEmpty(summaryView.text)) View.GONE else View.VISIBLE
    }
}
