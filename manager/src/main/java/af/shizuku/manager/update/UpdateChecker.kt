package af.shizuku.manager.update

import timber.log.Timber
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import af.shizuku.manager.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/thejaustin/ShizukuPlus/releases"
    private const val LATEST_URL = "$RELEASES_URL/latest"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val publishedAt: String,
        val isPrerelease: Boolean
    )

    /**
     * Check for an update.
     * @param channel "stable" → /releases/latest (skips prereleases)
     *                "dev"    → /releases (first result, includes prereleases)
     */
    suspend fun checkForUpdate(channel: String = "stable"): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val json: JSONObject = if (channel == "dev") {
                // Fetch list and take the first (most recent) release, including prereleases
                val arr = fetchJson(RELEASES_URL + "?per_page=1") as? JSONArray
                arr?.optJSONObject(0) ?: return@withContext null
            } else {
                fetchJson(LATEST_URL) as? JSONObject ?: return@withContext null
            }

            val tagName = json.getString("tag_name")
            val versionName = tagName.removePrefix("v")
            val isPrerelease = json.getBoolean("prerelease")
            val releaseNotes = json.optString("body", "No release notes available")
            val publishedAt = json.getString("published_at")

            // Find the APK asset by name rather than assuming index 0
            val assets = json.getJSONArray("assets")
            val downloadUrl = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url")
                ?: return@withContext null

            val versionCode = parseVersionCode(versionName)
            val currentVersionCode = parseVersionCode(BuildConfig.VERSION_NAME)

            if (versionCode > currentVersionCode) {
                Timber.tag(TAG).d("Update available: $versionName (channel=$channel, current=${BuildConfig.VERSION_NAME})")
                UpdateInfo(versionName, versionCode, releaseNotes, downloadUrl, publishedAt, isPrerelease)
            } else {
                Timber.tag(TAG).d("Already on latest ($channel): ${BuildConfig.VERSION_NAME}")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error checking for update (channel=$channel)")
            Sentry.captureException(e)
            null
        }
    }

    // Returns JSONObject for single release or JSONArray for list endpoint
    private fun fetchJson(urlString: String): Any? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            Timber.tag(TAG).w("HTTP ${connection.responseCode} from $urlString")
            return null
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
    }

    /** Extracts the build number from "13.6.0.r1488-shizukuplus" → 1488 */
    fun parseVersionCode(versionName: String): Int = try {
        """\.\br(\d+)\b""".toRegex().find(versionName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }

    fun formatPublishedDate(dateString: String): String = try {
        val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(input.parse(dateString) as Date)
    } catch (e: Exception) {
        dateString
    }
}
