package af.shizuku.manager.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.utils.ProjectLinks
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class RemoteDbSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "remote_app_db_sync"
        private const val DB_URL = ProjectLinks.APP_CONTEXT_DB
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        // Only re-fetch if the cached data is older than 20 hours
        private const val MIN_REFRESH_INTERVAL_MS = 20 * 60 * 60 * 1_000L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<RemoteDbSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val lastUpdate = ShizukuSettings.getLastDbUpdate()
        if (lastUpdate > 0 && System.currentTimeMillis() - lastUpdate < MIN_REFRESH_INTERVAL_MS) {
            Timber.d("RemoteDbSync: skipping — cache is fresh (last update ${(System.currentTimeMillis() - lastUpdate) / 3600_000}h ago)")
            return Result.success()
        }

        return try {
            val json = fetch(DB_URL)
            if (json != null) {
                AppContextManager.updateDatabase(json)
                Timber.d("RemoteDbSync: app context database updated successfully")
            } else {
                Timber.w("RemoteDbSync: server returned empty or error response")
            }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "RemoteDbSync: network fetch failed, will retry next cycle")
            Result.retry()
        }
    }

    private fun fetch(urlString: String): String? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Shizuku+/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")

            ShizukuSettings.getRemoteDbEtag()?.let {
                setRequestProperty("If-None-Match", it)
            }
            ShizukuSettings.getRemoteDbLastModified()?.let {
                setRequestProperty("If-Modified-Since", it)
            }
        }
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val etag = connection.getHeaderField("ETag")
                    val lastModified = connection.getHeaderField("Last-Modified")

                    ShizukuSettings.setRemoteDbEtag(etag)
                    ShizukuSettings.setRemoteDbLastModified(lastModified)

                    connection.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Timber.d("RemoteDbSync: 304 Not Modified — using local cache")
                    // If not modified, the cache is implicitly fresh, update the timestamp
                    ShizukuSettings.setLastDbUpdate(System.currentTimeMillis())
                    null
                }
                else -> {
                    Timber.w("RemoteDbSync: HTTP ${connection.responseCode} from $urlString")
                    null
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
