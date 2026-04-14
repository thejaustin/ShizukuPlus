package af.shizuku.manager.update

import android.util.Xml
import timber.log.Timber
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.withContext
import af.shizuku.manager.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
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
    // Fallback: GitHub's Atom feed is served from github.com CDN — different IP range
    // than api.github.com, so routing issues specific to that host don't affect it.
    private const val ATOM_URL = "https://github.com/thejaustin/ShizukuPlus/releases.atom"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val RETRY_DELAY_MS = 2_000L

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val publishedAt: String,
        val isPrerelease: Boolean,
        // True when only the Atom fallback succeeded — no direct APK URL available.
        val requiresManualDownload: Boolean = false
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        object NetworkError : CheckResult()
    }

    /**
     * Check for an update, with retry + Atom feed fallback.
     *
     * Strategy:
     *   1. Try GitHub API (up to 2 attempts, 2 s apart)
     *   2. If both fail with a network error, fall back to the GitHub Atom feed
     *      (can detect an update exists but can't supply a download URL — user is
     *      directed to GitHub Releases manually)
     *   3. If all three fail → NetworkError
     */
    suspend fun checkForUpdate(channel: String = "stable"): CheckResult = withContext(Dispatchers.IO) {
        for (attempt in 0 until 2) {
            if (attempt > 0) delay(RETRY_DELAY_MS)
            try {
                return@withContext checkViaApi(channel)
            } catch (e: Exception) {
                if (e.isNetworkError()) {
                    Timber.tag(TAG).w("Update check attempt ${attempt + 1} failed (network): ${e.message}")
                } else {
                    Sentry.captureException(e)
                    return@withContext CheckResult.NetworkError
                }
            }
        }

        Timber.tag(TAG).w("API unreachable after 2 attempts, trying Atom feed fallback")
        try {
            val fallback = checkViaAtomFeed()
            if (fallback != null) return@withContext CheckResult.UpdateAvailable(fallback)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Atom feed fallback also failed")
        }

        CheckResult.NetworkError
    }

    private fun checkViaApi(channel: String): CheckResult {
        val json: JSONObject = if (channel == "dev") {
            val arr = fetchJson("$RELEASES_URL?per_page=1") as? JSONArray
            arr?.optJSONObject(0) ?: return CheckResult.UpToDate
        } else {
            fetchJson(LATEST_URL) as? JSONObject ?: return CheckResult.UpToDate
        }

        val tagName = json.getString("tag_name")
        val versionName = tagName.removePrefix("v")
        val isPrerelease = json.getBoolean("prerelease")
        val releaseNotes = json.optString("body", "No release notes available")
        val publishedAt = json.getString("published_at")

        val assets = json.getJSONArray("assets")
        val downloadUrl = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("browser_download_url")
            ?: return CheckResult.UpToDate

        val versionCode = parseVersionCode(versionName)
        val currentVersionCode = parseVersionCode(BuildConfig.VERSION_NAME)

        return if (versionCode > currentVersionCode) {
            Timber.tag(TAG).d("Update available: $versionName (channel=$channel, current=${BuildConfig.VERSION_NAME})")
            CheckResult.UpdateAvailable(
                UpdateInfo(versionName, versionCode, releaseNotes, downloadUrl, publishedAt, isPrerelease)
            )
        } else {
            Timber.tag(TAG).d("Already on latest ($channel): ${BuildConfig.VERSION_NAME}")
            CheckResult.UpToDate
        }
    }

    /**
     * Reads GitHub's public Atom feed as a last-resort fallback.
     * Served from github.com CDN — a different network path than api.github.com.
     * Can tell us whether an update exists but cannot provide a direct APK URL.
     */
    private fun checkViaAtomFeed(): UpdateInfo? {
        val connection = (URL(ATOM_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(connection.inputStream, null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "link") {
                    val href = parser.getAttributeValue(null, "href") ?: ""
                    if (href.contains("/releases/tag/")) {
                        val tagName = href.substringAfterLast("/releases/tag/")
                        val versionName = tagName.removePrefix("v")
                        val versionCode = parseVersionCode(versionName)
                        val currentVersionCode = parseVersionCode(BuildConfig.VERSION_NAME)
                        if (versionCode > currentVersionCode) {
                            Timber.tag(TAG).d("Atom fallback: update available $versionName")
                            return UpdateInfo(
                                versionName = versionName,
                                versionCode = versionCode,
                                releaseNotes = "Download manually from GitHub Releases.",
                                downloadUrl = "",
                                publishedAt = "",
                                isPrerelease = versionName.contains("beta", ignoreCase = true)
                                        || versionName.contains("alpha", ignoreCase = true),
                                requiresManualDownload = true
                            )
                        }
                        return null // First release entry checked — already up to date
                    }
                }
                eventType = parser.next()
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun Exception.isNetworkError(): Boolean =
        this is UnknownHostException || this is SocketTimeoutException ||
        this is ConnectException || this is SSLException || this is IOException

    private fun fetchJson(urlString: String): Any? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.tag(TAG).w("HTTP ${connection.responseCode} from $urlString")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        } finally {
            connection.disconnect()
        }
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
