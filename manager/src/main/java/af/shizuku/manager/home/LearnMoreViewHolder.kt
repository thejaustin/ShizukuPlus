package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeLearnMoreBinding
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.IconStyleHelper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class LearnMoreViewHolder(
    private val binding: HomeLearnMoreBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Any?>(containerBinding.root) {
    companion object {
        val CREATOR =
            Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeLearnMoreBinding.inflate(inflater, outer.cardContent, true)
                LearnMoreViewHolder(inner, outer)
            }
    }

    private val originalIcon = binding.icon.drawable

    init {
        containerBinding.root.setOnClickListener { v: View ->
            af.shizuku.manager.knowledgebase.KnowledgebaseHelper.show(
                v.context,
                af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_SECURITY_PRIVACY,
            )
        }
        HomeEditMode.bindCardGestures(containerBinding, this)
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_learn_more")

        val sentryLimitReached = ShizukuSettings.isSentryLimitReached()
        val sentrySection = itemView.findViewById<View>(R.id.sentry_offline_section)
        sentrySection?.isVisible = sentryLimitReached
        if (sentryLimitReached) {
            val summaryView = itemView.findViewById<android.widget.TextView>(R.id.sentry_offline_summary)
            if (summaryView != null) {
                val cal =
                    java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.MONTH, 1)
                        set(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                val resetDate =
                    java.text.DateFormat
                        .getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
                        .format(cal.time)
                summaryView.text = context.getString(R.string.sentry_offline_notice_summary_format, resetDate)
            }
            val chipReport = itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.chip_report_issue)
            chipReport?.setOnClickListener { v ->
                CustomTabsHelper.launchUrlOrCopy(v.context, "https://github.com/thejaustin/ShizukuPlus/issues")
            }
        }
    }
}
