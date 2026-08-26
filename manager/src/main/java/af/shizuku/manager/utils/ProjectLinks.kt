package af.shizuku.manager.utils

object ProjectLinks {
    const val REPOSITORY = "https://github.com/qianyumeng0228/ShizukuPlus"
    const val README_DEVELOPER_GUIDE = "https://github.com/qianyumeng0228/ShizukuPlus/tree/master/README.md#developer-guide"
    const val OPEN_SOURCE_LICENSES = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/OPEN_SOURCE_LICENSES.md"

    const val RELEASES = "https://github.com/qianyumeng0228/ShizukuPlus/releases"
    const val LATEST_RELEASE = "https://github.com/qianyumeng0228/ShizukuPlus/releases/latest"
    const val RELEASES_ATOM = "https://github.com/qianyumeng0228/ShizukuPlus/releases.atom"

    const val ISSUES = "https://github.com/qianyumeng0228/ShizukuPlus/issues"
    const val NEW_ISSUE = "https://github.com/qianyumeng0228/ShizukuPlus/issues/new/choose"
    const val NEW_PREFILLED_ISSUE = "https://github.com/qianyumeng0228/ShizukuPlus/issues/new"

    const val API_RELEASES = "https://api.github.com/repos/qianyumeng0228/ShizukuPlus/releases"
    const val API_LATEST_RELEASE = "https://api.github.com/repos/qianyumeng0228/ShizukuPlus/releases/latest"

    const val APP_CONTEXT_DB = "https://raw.githubusercontent.com/qianyumeng0228/ShizukuPlus/master/app-context-db.json"
    const val APPS_DB = "https://raw.githubusercontent.com/qianyumeng0228/ShizukuPlus/master/database/apps.json"

    const val HELP_HOME = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/index.md"
    const val SERVICE_CONNECTION = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/service-connection.md"
    const val KNOWLEDGEBASE = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/knowledgebase.md"
    const val AUTOMATION_APPS = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/automation-apps.md"
    const val HELP_PC_ADB = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/service-connection.md#通过电脑-adb-启动"
    const val HELP_WIRELESS_ADB = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/service-connection.md#通过无线-adb-启动"
    const val HELP_ERROR_REFERENCE = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/service-connection.md#错误参考"
    const val HELP_WATCHDOG = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/knowledgebase.md#shizuku-一直随机停止"
    const val HELP_BOOT = "https://github.com/qianyumeng0228/ShizukuPlus/blob/master/docs/zh-CN/knowledgebase.md#shizuku-无法开机自启动"
    const val RISH = "https://github.com/qianyumeng0228/ShizukuPlus/tree/master/api/rish"

    fun releaseTag(tagName: String): String = "$RELEASES/tag/$tagName"
}
