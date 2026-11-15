package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

object SettingsSearchEngine {
    data class SettingItem(
        val key: String?,
        val title: String,
        val summary: String?,
        val category: String?,
        val fragmentClass: String,
        val xmlResId: Int,
        val iconResId: Int = 0,
        val categoryKey: String? = null,
    )

    private var indexedItems: List<SettingItem>? = null

    fun reset() {
        indexedItems = null
    }

    private val screens =
        mapOf(
            R.xml.settings_personalization to "af.shizuku.manager.settings.PersonalizationSettingsFragment",
            R.xml.settings_behavior to "af.shizuku.manager.settings.BehaviorSettingsFragment",
            R.xml.settings_advanced to "af.shizuku.manager.settings.AdvancedSettingsFragment",
            R.xml.settings_developer_options to "af.shizuku.manager.settings.DeveloperOptionsFragment",
            R.xml.settings_experimental to "af.shizuku.manager.settings.ExperimentalSettingsFragment",
            R.xml.settings_root_integration to "af.shizuku.manager.settings.RootIntegrationSettingsFragment",
            R.xml.settings_about to "af.shizuku.manager.settings.AboutSettingsFragment",
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
                var currentCategoryKey: String? = null

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        if (tagName == "PreferenceCategory" ||
                            tagName == "af.shizuku.manager.settings.CollapsiblePreferenceCategory" ||
                            tagName.endsWith("PreferenceCategory")
                        ) {
                            val catTitleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                            currentCategory = if (catTitleResId != 0) context.getString(catTitleResId) else parser.getAttributeValue(namespace, "title")
                            currentCategoryKey = parser.getAttributeValue(namespace, "key")
                        } else if (tagName != "PreferenceScreen") {
                            val key = parser.getAttributeValue(namespace, "key")

                            val titleResId = parser.getAttributeResourceValue(namespace, "title", 0)
                            val title = if (titleResId != 0) context.getString(titleResId) else parser.getAttributeValue(namespace, "title")

                            val summaryResId = parser.getAttributeResourceValue(namespace, "summary", 0)
                            val summary = if (summaryResId != 0) context.getString(summaryResId) else parser.getAttributeValue(namespace, "summary")

                            val iconResId = parser.getAttributeResourceValue(namespace, "icon", 0)

                            if (!title.isNullOrBlank()) {
                                items.add(SettingItem(key, title, summary, currentCategory, fragmentClass, xmlId, iconResId, currentCategoryKey))
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

    fun findItemByKey(
        context: Context,
        key: String,
    ): SettingItem? {
        init(context)
        val direct = indexedItems?.firstOrNull { it.key == key }
        if (direct != null) return direct

        val namespace = "http://schemas.android.com/apk/res/android"
        for ((xmlId, fragmentClass) in screens) {
            try {
                val parser = context.resources.getXml(xmlId)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val k = parser.getAttributeValue(namespace, "key")
                        if (k == key) {
                            val titleRes = parser.getAttributeResourceValue(namespace, "title", 0)
                            val title = if (titleRes != 0) context.getString(titleRes) else (parser.getAttributeValue(namespace, "title") ?: key)
                            val iconRes = parser.getAttributeResourceValue(namespace, "icon", 0)
                            return SettingItem(key, title, null, null, fragmentClass, xmlId, iconRes)
                        }
                    }
                    eventType = parser.next()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun search(
        context: Context,
        query: String,
    ): List<SettingItem> {
        init(context)
        val items = indexedItems ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val devUnlocked = ShizukuSettings.isDevOptionsUnlocked()
        val appIdentityOn = ShizukuSettings.isExperimentalAppIdentityEnabled()

        return items.filter { item ->
            if (!devUnlocked && item.fragmentClass == "af.shizuku.manager.settings.DeveloperOptionsFragment") return@filter false
            // Experimental > Developer previews is hidden until Developer options are unlocked.
            if (!devUnlocked && item.categoryKey == DEV_PREVIEWS_CATEGORY) return@filter false
            // Experimental app identity rows stay out of search while the dev toggle is off.
            if (!appIdentityOn && item.key in APP_IDENTITY_KEYS) return@filter false
            item.title.lowercase().contains(q) ||
                (item.summary != null && item.summary.lowercase().contains(q)) ||
                (item.category != null && item.category.lowercase().contains(q))
        }
    }

    private const val DEV_PREVIEWS_CATEGORY = "category_experimental"
    private val APP_IDENTITY_KEYS = setOf("category_app_identity", "custom_icon_studio", "app_icon_alias", "custom_app_name")
}
