package af.shizuku.manager.knowledgebase

import af.shizuku.manager.Helps
import af.shizuku.manager.ktx.findActivity
import af.shizuku.manager.utils.CustomTabsHelper
import android.content.Context
import androidx.fragment.app.FragmentActivity

object KnowledgebaseHelper {
    fun show(
        context: Context,
        topicId: String,
    ) {
        val activity = context.findActivity<FragmentActivity>()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            KnowledgebaseBottomSheet.show(activity.supportFragmentManager, topicId)
        } else {
            val fallbackUrl = KnowledgebaseRegistry.getTopic(topicId)?.webFallbackUrl ?: Helps.HOME.get()
            CustomTabsHelper.launchUrlOrCopy(context, fallbackUrl)
        }
    }

    /** Help for one Service Doctor check: its own wiki section, not the broad topic. */
    fun showForCheck(
        context: Context,
        checkId: String?,
    ) {
        val guide = KnowledgebaseRegistry.guideForCheck(checkId)
        if (guide == null) {
            show(context, KnowledgebaseRegistry.TOPIC_BATTERY_OEM)
            return
        }
        val activity = context.findActivity<FragmentActivity>()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            KnowledgebaseBottomSheet.show(activity.supportFragmentManager, guide.topicId, guide.wikiUrl)
        } else {
            CustomTabsHelper.launchUrlOrCopy(context, guide.wikiUrl)
        }
    }
}
