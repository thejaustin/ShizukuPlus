package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeServerStatusBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.springTo
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

class ServerStatusViewHolder(
    private val binding: HomeServerStatusBinding,
    root: View,
) : BaseViewHolder<ServiceStatus>(root) {
    /** Last running state the icon morph was drawn for (null = first bind, no animation). */
    private var lastMorphOk: Boolean? = null

    private val cardView: com.google.android.material.card.MaterialCardView = itemView as com.google.android.material.card.MaterialCardView

    companion object {
        val CREATOR =
            Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeServerStatusBinding.inflate(inflater, outer.cardContent, true)
                ServerStatusViewHolder(inner, outer.root)
            }
    }

    private var prevBgColor: Int? = null

    init {
        cardView.applySpringTouch()
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon
    private inline val logChip get() = binding.btnActivityLog
    private inline val diagnosticsChip get() = binding.btnDiagnostics

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val state =
            af.shizuku.manager.utils.ShizukuStateMachine
                .get()

        // S-Pen / DeX Mouse Hover Effect (Expressive Polish)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            itemView.setOnHoverListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                        v
                            ?.animate()
                            ?.scaleX(1.015f)
                            ?.scaleY(1.015f)
                            ?.translationZ(6f)
                            ?.setDuration(
                                af.shizuku.manager.ShizukuSettings
                                    .scaledAnimationDuration(150),
                            )?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.start()
                        true
                    }
                    android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                        v
                            ?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.translationZ(0f)
                            ?.setDuration(
                                af.shizuku.manager.ShizukuSettings
                                    .scaledAnimationDuration(150),
                            )?.setInterpolator(android.view.animation.AccelerateInterpolator())
                            ?.start()
                        true
                    }
                    else -> false
                }
            }
        }

        logChip.visibility =
            if (ok &&
                af.shizuku.manager.ShizukuSettings
                    .showActivityLogHome()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        logChip.setOnClickListener {
            val activity = context.asActivity<android.app.Activity>() ?: return@setOnClickListener
            activity.startWithSceneTransition(
                android.content.Intent(activity, af.shizuku.manager.activitylog.ActivityLogActivity::class.java),
            )
        }

        // The long-press and tooltip only belong to the "not running" chip; clear them first so
        // they don't stick around once the service is back.
        diagnosticsChip.setOnLongClickListener(null)
        diagnosticsChip.isLongClickable = false
        ViewCompat.setTooltipText(diagnosticsChip, null)
        if (ok) {
            diagnosticsChip.visibility = View.VISIBLE
            diagnosticsChip.text = context.getString(R.string.settings_diagnostics_category)
            diagnosticsChip.setChipIconResource(R.drawable.ic_server_ok_24)
            diagnosticsChip.setOnClickListener {
                val activity = context.asActivity<android.app.Activity>() ?: return@setOnClickListener
                activity.startWithSceneTransition(android.content.Intent(activity, SystemHubActivity::class.java))
            }
        } else if (af.shizuku.manager.ShizukuSettings
                .isDiagnosticsEnabled()
        ) {
            diagnosticsChip.visibility = View.VISIBLE
            diagnosticsChip.text = context.getString(R.string.home_status_diagnostics_tips)
            diagnosticsChip.setChipIconResource(R.drawable.ic_settings_suggest_24)
            ViewCompat.setTooltipText(diagnosticsChip, context.getString(R.string.home_service_doctor_title))
            diagnosticsChip.setOnClickListener {
                af.shizuku.manager.utils.DeactivationAdvisor.showStabilityTipsDialog(context) {
                    onBind()
                }
            }
            diagnosticsChip.setOnLongClickListener {
                context.startActivity(android.content.Intent(context, af.shizuku.manager.settings.ServiceDoctorActivity::class.java))
                true
            }
        } else {
            diagnosticsChip.visibility = View.GONE
        }

        val okColorAttr = if (ok) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorErrorContainer
        val onColorAttr = if (ok) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnErrorContainer

        val bgColor =
            com.google.android.material.color.MaterialColors.getColor(
                context,
                okColorAttr,
                com.google.android.material.color.MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    android.graphics.Color.TRANSPARENT,
                ),
            )
        val textColor =
            com.google.android.material.color.MaterialColors.getColor(
                context,
                onColorAttr,
                com.google.android.material.color.MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurface,
                    android.graphics.Color.BLACK,
                ),
            )

        // Animate background color transition when service state changes (running ↔ stopped)
        val prevBg = prevBgColor
        prevBgColor = bgColor
        if (prevBg != null && prevBg != bgColor) {
            android.animation.ValueAnimator
                .ofArgb(prevBg, bgColor)
                .apply {
                    duration =
                        af.shizuku.manager.ShizukuSettings
                            .scaledAnimationDuration(450)
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { cardView.setCardBackgroundColor(it.animatedValue as Int) }
                }.start()
        } else {
            cardView.setCardBackgroundColor(bgColor)
        }

        // Outline: hidden when user disables it. Style toggles between Material (theme roles) and
        // Status (semantic green/red).
        if (af.shizuku.manager.ShizukuSettings
                .isShowStatusCardOutlineEnabled()
        ) {
            val strokeDp = 2f
            cardView.strokeWidth = (strokeDp * context.resources.displayMetrics.density + 0.5f).toInt()
            val useStatusColors =
                af.shizuku.manager.ShizukuSettings
                    .getStatusCardOutlineStyle() == "status"
            cardView.strokeColor =
                when {
                    ok ->
                        if (useStatusColors) {
                            com.google.android.material.color.MaterialColors
                                .harmonizeWithPrimary(context, ContextCompat.getColor(context, R.color.status_ok))
                        } else {
                            com.google.android.material.color.MaterialColors.getColor(
                                context,
                                R.attr.colorPrimary,
                                ContextCompat.getColor(context, R.color.status_ok),
                            )
                        }
                    else ->
                        if (useStatusColors) {
                            com.google.android.material.color.MaterialColors
                                .harmonizeWithPrimary(context, ContextCompat.getColor(context, R.color.status_error))
                        } else {
                            com.google.android.material.color.MaterialColors.getColor(
                                context,
                                R.attr.colorError,
                                ContextCompat.getColor(context, R.color.status_error),
                            )
                        }
                }
        } else {
            cardView.strokeWidth = 0
        }

        textView.setTextColor(textColor)
        summaryView.setTextColor(textColor)
        logChip.setTextColor(textColor)
        logChip.chipIconTint =
            android.content.res.ColorStateList
                .valueOf(textColor)
        diagnosticsChip.setTextColor(textColor)
        diagnosticsChip.chipIconTint =
            android.content.res.ColorStateList
                .valueOf(textColor)

        // Icon pill uses vivid semantic role colors so it stands out against the card's lighter
        // container background — matching pill-to-card was invisible in users' issue screenshots.
        val (iconPillColor, iconOnPillColor) =
            when {
                ok -> {
                    com.google.android.material.color.MaterialColors.getColor(
                        context,
                        R.attr.colorPrimary,
                        android.graphics.Color.TRANSPARENT,
                    ) to
                        com.google.android.material.color.MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnPrimary,
                            android.graphics.Color.WHITE,
                        )
                }
                else -> {
                    com.google.android.material.color.MaterialColors.getColor(
                        context,
                        R.attr.colorError,
                        android.graphics.Color.RED,
                    ) to
                        com.google.android.material.color.MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnError,
                            android.graphics.Color.WHITE,
                        )
                }
            }
        af.shizuku.manager.utils.IconStyleHelper
            .applyToStatusCardIcon(iconView, pillColor = iconPillColor, tintColor = iconOnPillColor)
        // With an expressive icon shape picked, running = that shape, stopped = plain circle, and
        // the icon springs between them when the state changes (M3E MaterialShapes morph, #430).
        (iconView.background as? af.shizuku.manager.utils.MorphShapeDrawable)?.let { morph ->
            val target = if (ok) 1f else 0f
            val previous = lastMorphOk
            lastMorphOk = ok
            if (previous == null || previous == ok) {
                morph.progress = target
            } else {
                morph.progress = 1f - target
                morph.springTo(target)
            }
        }

        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24))
        }
        val user =
            when {
                isRoot -> context.getString(R.string.home_status_service_user_root)
                !status.startMethod.isNullOrEmpty() -> status.startMethod
                else -> context.getString(R.string.home_status_service_user_adb)
            }
        val title =
            if (ok) {
                context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
            } else {
                context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
            }
        val summary =
            if (ok) {
                // patchVersion is -1 when unknown (not yet delivered / not supported by the server);
                // 0 is a legitimate patch value. Don't claim the server is outdated based on an unknown
                // patch, or the "restart to update" prompt shows spuriously.
                val patchKnown = patchVersion >= 0
                val versionText = if (patchKnown) "$apiVersion.$patchVersion" else "$apiVersion"
                if (apiVersion != Shizuku.getLatestServiceVersion() || (patchKnown && patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION)) {
                    context.getString(
                        R.string.home_status_service_version_update,
                        user,
                        versionText,
                        "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}",
                    )
                } else {
                    context.getString(R.string.home_status_service_version, user, versionText)
                }
            } else {
                context.getString(R.string.home_status_service_not_running_summary, context.getString(R.string.app_name))
            }
        // What the helper notifications would say, here too, for whoever has them off.
        val issue =
            af.shizuku.manager.utils.ShizukuIssues
                .top()
                ?.takeIf {
                    af.shizuku.manager.ShizukuSettings
                        .isProblemsOnHomeEnabled()
                }
        val problem =
            issue?.let {
                "<br><br><b>${TextUtils.htmlEncode(it.title)}</b><br>" +
                    TextUtils.htmlEncode(it.text.substringBefore("\n\n"))
            } ?: ""
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = (summary + problem).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.visibility = if (TextUtils.isEmpty(summaryView.text)) View.GONE else View.VISIBLE
    }
}
