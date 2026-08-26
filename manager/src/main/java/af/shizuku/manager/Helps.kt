package af.shizuku.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import af.shizuku.manager.utils.MultiLocaleEntity
import af.shizuku.manager.utils.ProjectLinks

object Helps {
    // Points at Service-Connection's "Starting via PC ADB" section specifically — this link is
    // shown alongside "requires computer connection" copy, so it should land the reader
    // directly on the PC-adb walkthrough, not the page top (which used to be a dead
    // "Setup" page 404 before that, and even after fixing it to a real page, this exact
    // section didn't exist yet — the link resolved but didn't actually answer what the
    // reader came for).
    val ADB = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.HELP_PC_ADB)
        put("zh-TW", ProjectLinks.HELP_PC_ADB)
        put("en", ProjectLinks.SERVICE_CONNECTION)
    }

    val ADB_ANDROID11 = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.HELP_WIRELESS_ADB)
        put("zh-TW", ProjectLinks.HELP_WIRELESS_ADB)
        put("en", ProjectLinks.SERVICE_CONNECTION)
    }

    // "Supported-apps" doesn't exist as its own page either — Knowledgebase is the closest
    // real landing page until a dedicated compatibility list is written.
    val APPS = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.KNOWLEDGEBASE)
        put("zh-TW", ProjectLinks.KNOWLEDGEBASE)
        put("en", ProjectLinks.KNOWLEDGEBASE)
    }

    val HOME = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.HELP_HOME)
        put("zh-TW", ProjectLinks.HELP_HOME)
        put("en", ProjectLinks.README_DEVELOPER_GUIDE)
    }

    val DOWNLOAD = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.RELEASES)
        put("zh-TW", ProjectLinks.RELEASES)
        put("en", ProjectLinks.RELEASES)
    }

    val ADB_PERMISSION = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.HELP_ERROR_REFERENCE)
        put("zh-TW", ProjectLinks.HELP_ERROR_REFERENCE)
        put("en", ProjectLinks.SERVICE_CONNECTION)
    }

    val SUI = MultiLocaleEntity().apply {
        put("en", "https://github.com/RikkaApps/Sui")
    }

    val RISH = MultiLocaleEntity().apply {
        put("zh-CN", ProjectLinks.RISH)
        put("zh-TW", ProjectLinks.RISH)
        put("en", ProjectLinks.RISH)
    }

    /**
     * Get help URL for the given locale
     */
    fun getHelpUrl(locale: String?): String {
        return HOME.get(locale) ?: HOME.get("en") ?: ProjectLinks.HELP_HOME
    }

    /**
     * Open URL in browser
     */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
