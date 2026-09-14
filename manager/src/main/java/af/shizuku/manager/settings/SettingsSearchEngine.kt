package af.shizuku.manager.settings

import android.content.Context
import android.content.res.XmlResourceParser
import timber.log.Timber
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import org.xmlpull.v1.XmlPullParser

object SettingsSearchEngine {

    data class SettingItem(
        val key: String?,
        val title: String,
        val summary: String?,
        val category: String?,
        val fragmentClass: String,
        val xmlResId: Int
    )

    private var indexedItems: List<SettingItem>? = null

    fun reset() {
        indexedItems = null
    }

    private val screens = mapOf(
        R.xml.settings_shizuku_plus to "af.shizuku.manager.settings.ShizukuPlusSettingsFragment",
        R.xml.settings_personalization to "af.shizuku.manager.settings.PersonalizationSettingsFragment",
        R.xml.settings_behavior to "af.shizuku.manager.settings.BehaviorSettingsFragment",
        R.xml.settings_advanced to "af.shizuku.manager.settings.AdvancedSettingsFragment",
        R.xml.settings_developer_options to "af.shizuku.manager.settings.DeveloperOptionsFragment",
        R.xml.settings_root_integration to "af.shizuku.manager.settings.RootIntegrationSettingsFragment",
        R.xml.settings_app_management to "af.shizuku.manager.settings.AppManagementSettingsFragment",
        R.xml.settings_about to "af.shizuku.manager.settings.AboutSettingsFragment"
    )

    fun init(context: Context) {
        if (indexedItems != null) return
        val items = mutableListOf<SettingItem>()

        val namespace = "http://schemas.android.com/apk/res/android"

        for ((xmlId, fragmentClass) in screens) {
            try {
                val parser = context.resources.getXml(xmlId)
                var eventType = parser.eventType
                var currentCategory: String? = null

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        if (tagName == "PreferenceCategory" ||
                            tagName == "af.shizuku.manager.settings.CollapsiblePreferenceCategory" ||
                            tagName.endsWith("PreferenceCategory")) {

                            val catTitleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                            currentCategory = if (catTitleResId != 0) context.getString(catTitleResId) else parser.getAttributeValue(namespace, "title")
                        } else if (tagName != "PreferenceScreen") {
                            val key = parser.getAttributeValue(namespace, "key")

                            val titleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                            val title = if (titleResId != 0) context.getString(titleResId) else parser.getAttributeValue(namespace, "title")

                            val summaryResId = parser.getAttributeResourceValue(namespace, "summary", 0)
                            val summary = if (summaryResId != 0) context.getString(summaryResId) else parser.getAttributeValue(namespace, "summary")

                            if (!title.isNullOrBlank()) {
                                items.add(SettingItem(key, title, summary, currentCategory, fragmentClass, xmlId))
                            }
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                Timber.tag("SettingsSearchEngine").w(e, "Failed to index preferences from XML")
            }
        }
        indexedItems = items
    }

    fun search(context: Context, query: String): List<SettingItem> {
        init(context)
        val items = indexedItems ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val devUnlocked = ShizukuSettings.isVectorEnabled()

        return items.filter { item ->
            if (!devUnlocked && item.fragmentClass == "af.shizuku.manager.settings.DeveloperOptionsFragment") return@filter false
            item.title.lowercase().contains(q) ||
            (item.summary != null && item.summary.lowercase().contains(q)) ||
            (item.category != null && item.category.lowercase().contains(q))
        }
    }
}
