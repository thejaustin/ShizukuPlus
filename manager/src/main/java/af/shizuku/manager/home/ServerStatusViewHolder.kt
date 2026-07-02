package af.shizuku.manager.home

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import androidx.core.content.ContextCompat
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeServerStatusBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

import af.shizuku.manager.utils.MotionUtils.applySpringTouch

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

    init {
        cardView.applySpringTouch()
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon
    private inline val logChip get() = binding.btnActivityLog
    private inline val diagnosticsChip get() = binding.btnDiagnostics
    private inline val statusIndicator get() = binding.statusIndicator
    private inline val sentryButton get() = binding.btnSentryOffline

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val state = af.shizuku.manager.utils.ShizukuStateMachine.get()

        // Live Status Indicator
        statusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                ok -> ContextCompat.getColor(context, R.color.status_ok)
                state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING -> ContextCompat.getColor(context, R.color.status_starting)
                else -> ContextCompat.getColor(context, R.color.status_error)
            }
        )

        // Pulse animation for Starting/Running state
        if (state == af.shizuku.manager.utils.ShizukuStateMachine.State.STARTING || ok) {
            val pulse = android.view.animation.AlphaAnimation(0.4f, 1.0f).apply {
                duration = if (ok) 1500 else 600
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            statusIndicator.startAnimation(pulse)
        } else {
            statusIndicator.clearAnimation()
        }

        // Show Sentry offline button only if limit is reached
        sentryButton.visibility = if (af.shizuku.manager.ShizukuSettings.isSentryLimitReached()) View.VISIBLE else View.GONE
        sentryButton.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.sentry_offline_notice_title)
                .setMessage(R.string.sentry_offline_notice_learn_more)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.update_view_on_github) { _, _ ->
                    af.shizuku.manager.utils.CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus/issues")
                }
                .show()
        }

        // S-Pen / DeX Mouse Hover Effect (Expressive Polish)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            itemView.setOnHoverListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                        v?.animate()
                            ?.scaleX(1.015f)
                            ?.scaleY(1.015f)
                            ?.translationZ(6f)
                            ?.setDuration(150)
                            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.start()
                        true
                    }
                    android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                        v?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.translationZ(0f)
                            ?.setDuration(150)
                            ?.setInterpolator(android.view.animation.AccelerateInterpolator())
                            ?.start()
                        true
                    }
                    else -> false
                }
            }
        }

        logChip.visibility = if (ok && af.shizuku.manager.ShizukuSettings.showActivityLogHome()) View.VISIBLE else View.GONE
        logChip.setOnClickListener {
            val activity = context.asActivity<android.app.Activity>() ?: return@setOnClickListener
            activity.startWithSceneTransition(
                android.content.Intent(activity, af.shizuku.manager.activitylog.ActivityLogActivity::class.java),
                iconView, "icon_server_status"
            )
        }

        diagnosticsChip.visibility = if (ok) View.VISIBLE else View.GONE
        diagnosticsChip.setOnClickListener {
            val activity = context.asActivity<android.app.Activity>() ?: return@setOnClickListener
            activity.startActivity(android.content.Intent(activity, SystemHubActivity::class.java))
        }

        val typedValue = android.util.TypedValue()
        val okColorAttr = if (ok) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorTertiaryContainer
        val onColorAttr = if (ok) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnTertiaryContainer

        fun resolveColor(attr: Int, fallbackAttr: Int, hardcodedFallback: Int): Int {
            context.theme.resolveAttribute(attr, typedValue, true)
            if (typedValue.type != android.util.TypedValue.TYPE_NULL && typedValue.data != 0) return typedValue.data
            context.theme.resolveAttribute(fallbackAttr, typedValue, true)
            return if (typedValue.type != android.util.TypedValue.TYPE_NULL && typedValue.data != 0) typedValue.data else hardcodedFallback
        }

        val bgColor = resolveColor(
            okColorAttr,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            if (ok) 0xFFE8EAF6.toInt() else 0xFFE8F5E9.toInt()
        )
        val textColor = resolveColor(
            onColorAttr,
            com.google.android.material.R.attr.colorOnSurface,
            0xFF1A1C1E.toInt()
        )

        cardView.setCardBackgroundColor(bgColor)

        textView.setTextColor(textColor)
        summaryView.setTextColor(textColor)
        logChip.setTextColor(textColor)
        diagnosticsChip.setTextColor(textColor)

        iconView.setBackgroundResource(R.drawable.shape_droplet_background)
        iconView.backgroundTintList = android.content.res.ColorStateList.valueOf(textColor)
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(bgColor)

        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24))
        }
        val user = if (isRoot) context.getString(R.string.home_status_service_user_root) else context.getString(R.string.home_status_service_user_adb)
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            // patchVersion is -1 when unknown (not yet delivered / not supported by the server);
            // 0 is a legitimate patch value. Don't claim the server is outdated based on an unknown
            // patch, or the "restart to update" prompt shows spuriously.
            val patchKnown = patchVersion >= 0
            val versionText = if (patchKnown) "${apiVersion}.${patchVersion}" else "$apiVersion"
            if (apiVersion != Shizuku.getLatestServiceVersion() || (patchKnown && patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION)) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    versionText,
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                context.getString(R.string.home_status_service_version, user, versionText)
            }
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.visibility = if (TextUtils.isEmpty(summaryView.text)) View.GONE else View.VISIBLE
    }
}
