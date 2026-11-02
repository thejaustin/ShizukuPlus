package af.shizuku.manager.update

import af.shizuku.manager.BuildConfig
import android.util.Xml
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException

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

    data class ReleaseEntry(
        val tagName: String,
        val publishedAt: String,
        val body: String,
        val isNew: Boolean = false,
        val isPrerelease: Boolean = false,
    )

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val publishedAt: String,
        val isPrerelease: Boolean,
        // True when only the Atom fallback succeeded — no direct APK URL available.
        val requiresManualDownload: Boolean = false,
    )

    sealed class CheckResult {
        data class UpdateAvailable(
            val info: UpdateInfo,
        ) : CheckResult()

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
    suspend fun checkForUpdate(channel: String = "stable"): CheckResult =
        withContext(Dispatchers.IO) {
            val transaction = Sentry.startTransaction("UpdateCheck", "check_for_update")
            Sentry.getSpan()?.setTag("channel", channel)

            try {
                for (attempt in 0 until 2) {
                    if (attempt > 0) delay(RETRY_DELAY_MS)
                    val span = transaction.startChild("github_api", "attempt_$attempt")
                    try {
                        val result = checkViaApi(channel)
                        span.finish(io.sentry.SpanStatus.OK)
                        return@withContext result
                    } catch (e: Exception) {
                        span.throwable = e
                        span.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
                        if (e.isNetworkError()) {
                            Timber.tag(TAG).w("Update check attempt ${attempt + 1} failed (network): ${e.message}")
                        } else {
                            Sentry.captureException(e)
                            return@withContext CheckResult.NetworkError
                        }
                    }
                }

                Timber.tag(TAG).w("API unreachable after 2 attempts, trying Atom feed fallback")
                val fallbackSpan = transaction.startChild("atom_feed", "fallback")
                try {
                    val fallback = checkViaAtomFeed()
                    fallbackSpan.finish(io.sentry.SpanStatus.OK)
                    if (fallback != null) return@withContext CheckResult.UpdateAvailable(fallback)
                } catch (e: Exception) {
                    fallbackSpan.throwable = e
                    fallbackSpan.finish(io.sentry.SpanStatus.INTERNAL_ERROR)
                    Timber.tag(TAG).w(e, "Atom feed fallback also failed")
                }

                CheckResult.NetworkError
            } finally {
                transaction.finish()
            }
        }

    private fun checkViaApi(channel: String): CheckResult {
        val json: JSONObject =
            if (channel == "dev" || channel == "beta") {
                val arr = fetchJson("$RELEASES_URL?per_page=5") as? JSONArray
                // Dev/beta channel must track the newest *prerelease* build, not simply the newest
                // release entry overall. Index 0 is whichever release was created most recently
                // regardless of its "prerelease" flag, so as soon as a stable release is cut after a
                // dev build, index 0 silently becomes that stable release and the dev channel starts
                // resolving to the exact same release as the stable channel's /releases/latest — the
                // user's channel choice stops making any difference. Explicitly prefer the newest
                // entry flagged prerelease=true within the fetched page, falling back to index 0 only
                // if none of the recent releases are marked as a prerelease.
                val prerelease =
                    arr?.let { a ->
                        (0 until a.length())
                            .map { a.getJSONObject(it) }
                            .firstOrNull { it.optBoolean("prerelease", false) }
                    }
                val newestOverall = arr?.optJSONObject(0)
                // ...but a stable release is still a valid, newer build for a Dev/Beta user — a
                // prerelease cut before the latest stable shouldn't keep them stuck on an older
                // version just because it's tagged "prerelease". Compare actual version codes and
                // take whichever is newer, so a fresher stable release "wins" over a stale dev build.
                val prereleaseCode = prerelease?.let { parseVersionCode(it.optString("tag_name", "").removePrefix("v")) } ?: -1
                val overallCode = newestOverall?.let { parseVersionCode(it.optString("tag_name", "").removePrefix("v")) } ?: -1
                when {
                    prerelease != null && prereleaseCode >= overallCode -> prerelease
                    newestOverall != null -> newestOverall
                    else -> return CheckResult.UpToDate
                }
            } else {
                fetchJson(LATEST_URL) as? JSONObject ?: return CheckResult.UpToDate
            }

        val tagName = json.getString("tag_name")
        val versionName = tagName.removePrefix("v")
        val isPrerelease = json.optBoolean("prerelease", false)
        val releaseNotes = json.optString("body", "")
        val publishedAt = json.optString("published_at", "")

        val assets = json.getJSONArray("assets")
        val isDropIn = BuildConfig.APPLICATION_ID == "moe.shizuku.privileged.api"
        val apkAssets =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }

        val targetAsset =
            if (isDropIn) {
                apkAssets.firstOrNull {
                    val name = it.getString("name")
                    (name.contains("Drop-In", ignoreCase = true) || name.contains("dropin", ignoreCase = true)) &&
                        !name.contains("Compat", ignoreCase = true)
                }
            } else {
                apkAssets.firstOrNull {
                    val name = it.getString("name")
                    !name.contains("Drop-In", ignoreCase = true) &&
                        !name.contains("dropin", ignoreCase = true) &&
                        !name.contains("Compat", ignoreCase = true)
                }
            } ?: apkAssets.firstOrNull { !it.getString("name").contains("Compat", ignoreCase = true) }

        val downloadUrl =
            targetAsset?.getString("browser_download_url")
                ?: return CheckResult.UpToDate

        val versionCode = parseVersionCode(versionName)
        val currentVersionCode = parseVersionCode(BuildConfig.VERSION_NAME)

        return if (versionCode > currentVersionCode) {
            Timber.tag(TAG).d("Update available: $versionName (channel=$channel, current=${BuildConfig.VERSION_NAME})")
            CheckResult.UpdateAvailable(
                UpdateInfo(versionName, versionCode, releaseNotes, downloadUrl, publishedAt, isPrerelease),
            )
        } else {
            Timber.tag(TAG).d("Already on latest ($channel): ${BuildConfig.VERSION_NAME}")
            CheckResult.UpToDate
        }
    }

    /**
     * Fetches the release notes body for a specific tag (e.g. "v13.6.0.r2162") — used by the
     * in-app changelog dialog to show what changed in the version the user just updated to,
     * as opposed to [checkForUpdate]'s "latest" which may have moved on by the time they open
     * the app. Returns null on any failure (offline, tag not found, etc.) so callers can fall
     * back to a generic message instead of failing the whole dialog.
     */
    suspend fun fetchReleaseNotesForTag(tag: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val json = fetchJson("$RELEASES_URL/tags/$tag") as? JSONObject
                val body = json?.optString("body", "")?.takeIf { it.isNotBlank() }
                if (body != null) return@withContext body
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to fetch release notes for tag $tag")
            }

            try {
                val atomReleases = fetchReleasesFromAtom(0, 15)
                val cleanTag = tag.removePrefix("v").trim()
                val match =
                    atomReleases.firstOrNull {
                        it.tagName
                            .removePrefix("v")
                            .trim()
                            .equals(cleanTag, ignoreCase = true)
                    }
                if (match != null && match.body.isNotBlank()) {
                    return@withContext match.body
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to fetch release notes for tag $tag via Atom feed")
            }
            null
        }

    /**
     * Fetches up to [maxReleases] from GitHub.
     * When [sinceVersionCode] > 0, releases with versionCode > [sinceVersionCode] are marked
     * with [ReleaseEntry.isNew] = true.
     *
     * Unlike prior behavior which terminated immediately upon hitting [sinceVersionCode] (which
     * truncated older releases and left the user unable to browse historical releases in-app),
     * this continues collecting releases up to [maxReleases] so the What's New dialog has both
     * all new updates and previous releases available.
     */

    /** One page (1-based) of releases, newest first; null on failure so callers can offer a retry. */
    suspend fun fetchReleasesPage(
        page: Int,
        perPage: Int = 15,
    ): List<ReleaseEntry>? =
        withContext(Dispatchers.IO) {
            try {
                val arr = fetchJson("$RELEASES_URL?per_page=$perPage&page=$page") as? JSONArray
                if (arr != null) {
                    return@withContext (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.getJSONObject(i)
                        val tag = obj.optString("tag_name", "").ifBlank { return@mapNotNull null }
                        ReleaseEntry(
                            tagName = tag,
                            publishedAt = obj.optString("published_at", ""),
                            body = obj.optString("body", ""),
                            isPrerelease = obj.optBoolean("prerelease", false),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to fetch releases page $page")
            }

            if (page == 1) {
                val fallback = fetchReleasesFromAtom(0, perPage)
                if (fallback.isNotEmpty()) return@withContext fallback
            }
            null
        }

    suspend fun fetchReleasesSince(
        sinceVersionCode: Int,
        maxReleases: Int = 25,
    ): List<ReleaseEntry> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<ReleaseEntry>()
            try {
                var page = 1
                val maxPages = 4
                val perPage = minOf(maxReleases, 30)
                while (page <= maxPages && result.size < maxReleases) {
                    val arr = fetchJson("$RELEASES_URL?per_page=$perPage&page=$page") as? JSONArray ?: break
                    if (arr.length() == 0) break
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val tag = obj.optString("tag_name", "").ifBlank { continue }
                        val vc = parseVersionCode(tag.removePrefix("v"))
                        val isNew = sinceVersionCode > 0 && vc > sinceVersionCode
                        result.add(
                            ReleaseEntry(
                                tagName = tag,
                                publishedAt = obj.optString("published_at", ""),
                                body = obj.optString("body", ""),
                                isNew = isNew,
                                isPrerelease = obj.optBoolean("prerelease", false),
                            ),
                        )
                        if (result.size >= maxReleases) break
                    }
                    if (arr.length() < perPage) break
                    page++
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to fetch releases since versionCode $sinceVersionCode")
            }

            if (result.isEmpty()) {
                val atomReleases = fetchReleasesFromAtom(sinceVersionCode, maxReleases)
                if (atomReleases.isNotEmpty()) {
                    return@withContext atomReleases
                }
            }
            result
        }

    private fun htmlToMarkdown(html: String): String =
        html
            .replace(Regex("""<h1>(.*?)</h1>""", RegexOption.IGNORE_CASE), "# $1\n\n")
            .replace(Regex("""<h2>(.*?)</h2>""", RegexOption.IGNORE_CASE), "## $1\n\n")
            .replace(Regex("""<h3>(.*?)</h3>""", RegexOption.IGNORE_CASE), "### $1\n\n")
            .replace(Regex("""<h4>(.*?)</h4>""", RegexOption.IGNORE_CASE), "#### $1\n\n")
            .replace(Regex("""<li>(.*?)</li>""", RegexOption.IGNORE_CASE), "- $1\n")
            .replace(Regex("""<a\s+[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE), "[$2]($1)")
            .replace(Regex("""<blockquote>(.*?)</blockquote>""", RegexOption.IGNORE_CASE), "> $1\n\n")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<p>(.*?)</p>""", RegexOption.IGNORE_CASE), "$1\n\n")
            .replace(Regex("""</?[a-zA-Z0-9]+[^>]*>"""), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    private fun fetchReleasesFromAtom(
        sinceVersionCode: Int,
        maxReleases: Int,
    ): List<ReleaseEntry> {
        val connection =
            try {
                (URL(ATOM_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to open connection to Atom feed")
                return emptyList()
            }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.tag(TAG).w("HTTP ${connection.responseCode} from Atom feed")
                return emptyList()
            }

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(connection.inputStream, null)

            val results = mutableListOf<ReleaseEntry>()
            var eventType = parser.eventType
            var currentTag = ""
            var currentUpdated = ""
            var currentContent = ""
            var inEntry = false

            while (eventType != XmlPullParser.END_DOCUMENT && results.size < maxReleases) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (name) {
                            "entry" -> {
                                inEntry = true
                                currentTag = ""
                                currentUpdated = ""
                                currentContent = ""
                            }
                            "title" ->
                                if (inEntry) {
                                    currentTag = parser.nextText().trim()
                                }
                            "updated" ->
                                if (inEntry) {
                                    currentUpdated = parser.nextText().trim()
                                }
                            "content" ->
                                if (inEntry) {
                                    currentContent = htmlToMarkdown(parser.nextText())
                                }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "entry" && inEntry) {
                            inEntry = false
                            if (currentTag.isNotBlank()) {
                                val vc = parseVersionCode(currentTag.removePrefix("v"))
                                val isNew = sinceVersionCode > 0 && vc > sinceVersionCode
                                results.add(
                                    ReleaseEntry(
                                        tagName = currentTag,
                                        publishedAt = currentUpdated,
                                        body = currentContent,
                                        isNew = isNew,
                                        isPrerelease =
                                            currentTag.contains("beta", ignoreCase = true) ||
                                                currentTag.contains("alpha", ignoreCase = true),
                                    ),
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            return results
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse releases from Atom feed")
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads GitHub's public Atom feed as a last-resort fallback.
     * Served from github.com CDN — a different network path than api.github.com.
     * Can tell us whether an update exists but cannot provide a direct APK URL.
     */
    private fun checkViaAtomFeed(): UpdateInfo? {
        val connection =
            (URL(ATOM_URL).openConnection() as HttpURLConnection).apply {
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
                                releaseNotes = "",
                                downloadUrl = "",
                                publishedAt = "",
                                isPrerelease =
                                    versionName.contains("beta", ignoreCase = true) ||
                                        versionName.contains("alpha", ignoreCase = true),
                                requiresManualDownload = true,
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
        this is UnknownHostException ||
            this is SocketTimeoutException ||
            this is ConnectException ||
            this is SSLException ||
            this is IOException

    private fun fetchJson(urlString: String): Any? {
        val connection =
            (URL(urlString).openConnection() as HttpURLConnection).apply {
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

    /** Extracts the build number from "r2673", "Shizuku+ r2673", or "13.6.0.r1488" → the number. */
    fun parseVersionCode(versionName: String): Int =
        try {
            """\br(\d+)\b""".toRegex().find(versionName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }

    fun formatPublishedDate(dateString: String): String =
        try {
            val input =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(input.parse(dateString) as Date)
        } catch (_: Exception) {
            dateString
        }
}
