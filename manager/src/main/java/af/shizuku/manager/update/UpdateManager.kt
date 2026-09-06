package af.shizuku.manager.update
import af.shizuku.manager.R

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import timber.log.Timber
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.ShizukuSettings
import java.io.File
import kotlinx.coroutines.*

/**
 * Manages downloading and installing updates
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val NOTIFICATION_CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DOWNLOAD_ID_PREF = "update_download_id"
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var downloadId: Long = -1
    private var monitorJob: Job? = null

    /**
     * Create notification channel for updates
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH
        )
            .setName(context.getString(R.string.update_notification_channel))
            .setDescription(context.getString(R.string.update_notification_channel_description))
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Download update APK
     * @param downloadUrl URL to download the APK from
     * @param versionName Version name for display
     */
    @SuppressLint("Range")
    fun downloadUpdate(downloadUrl: String, versionName: String) {
        createNotificationChannel()

        // Callers invoke this from a UI click handler; the file-exists check, delete, and
        // cleanup() below are all blocking disk I/O, so this whole body runs on IO instead of
        // whatever thread called downloadUpdate() (previously janked/risked ANR on slow storage
        // or with many stale APKs to clean up).
        scope.launch(Dispatchers.IO) {
            val fileName = "Shizuku+-v$versionName.apk"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

            // Check if file already exists and delete it
            if (file.exists()) {
                file.delete()
            }

            // Old update APKs are never referenced again once a newer one starts downloading.
            cleanup()

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(context.getString(R.string.update_downloading_title))
                .setDescription(context.getString(R.string.update_downloading_description, versionName))
                // HIDDEN, not VISIBLE_NOTIFY_COMPLETED — monitorDownload() already drives our own
                // progress/install notifications; VISIBLE_NOTIFY_COMPLETED would show a second,
                // redundant system download notification alongside them.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType("application/vnd.android.package-archive")

            // Add after-download broadcast
            request.addRequestHeader("User-Agent", "Shizuku+/${versionName}")

            try {
                downloadId = downloadManager.enqueue(request)

                // Save download ID
                context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong(DOWNLOAD_ID_PREF, downloadId)
                    .apply()

                Timber.tag(TAG).d("Download started: $downloadUrl, ID: $downloadId")

                // Monitor download progress
                monitorDownload(downloadId, file, versionName)
            } catch (e: Exception) {
                // WARN not ERROR: some ROMs (MIUI/HyperOS) don't expose the DownloadManager
                // content URI — expected incompatibility, not a crash (SHIZUKUPLUS-8N).
                Timber.tag(TAG).w(e, "Failed to start download")
                showDownloadErrorNotification()
            }
        }
    }

    /**
     * Monitor download progress
     */
    private fun monitorDownload(downloadId: Long, file: File, versionName: String) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    // Explicit projection avoids IllegalArgumentException("column local_filename is not allowed")
                    // thrown by DownloadManager on some Android 10+ OEM builds when the default
                    // projection internally includes the removed local_filename column.
                    val cursor = try {
                        withContext(Dispatchers.IO) { downloadManager.query(query) }
                    } catch (e: IllegalArgumentException) {
                        Timber.tag(TAG).w(e, "DownloadManager.query rejected by system; retrying bare filter")
                        null
                    }

                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val progressIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_RUNNING
                        val progress = if (progressIdx >= 0) cursor.getLong(progressIdx) else 0L
                        val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                cursor.close()
                                Timber.tag(TAG).d("Download completed: ${file.absolutePath}")
                                onDownloadComplete(file, versionName)
                                break
                            }
                            DownloadManager.STATUS_FAILED -> {
                                // COLUMN_REASON holds a DownloadManager.ERROR_* code when
                                // STATUS_FAILED - without it "Download failed" (SHIZUKUPLUS-8H)
                                // gives no way to tell insufficient-storage, HTTP errors, and
                                // unresumable transfers apart. WARN not ERROR: download failures
                                // are expected user-facing events (bad network, no storage, etc.).
                                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                                cursor.close()
                                Timber.tag(TAG).w("Download failed (reason=$reason)")
                                showDownloadErrorNotification(reason)
                                break
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                // Waiting for network
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                // Update progress notification. progress/total are Long — an
                                // Int (progress * 100) would overflow for any file over ~21.4MB.
                                val percent = if (total > 0) (progress * 100 / total).toInt() else 0
                                updateProgressNotification(percent, versionName)
                            }
                        }
                        cursor.close()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error monitoring download")
                }
                delay(500)
            }
        }
    }

    /**
     * Update progress notification
     */
    private fun updateProgressNotification(progress: Int, versionName: String) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.update_downloading_title))
            .setContentText(context.getString(R.string.update_downloading_progress, versionName, progress))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Called when download is complete
     */
    private fun onDownloadComplete(file: File, versionName: String) {
        // Remove progress notification
        notificationManager.cancel(NOTIFICATION_ID)

        if (ShizukuSettings.isAutoInstallEnabled()) {
            scope.launch {
                if (!installApk(file)) {
                    // installApk only returns false on an unexpected failure before it could
                    // even hand off to the system installer — fall back to the manual prompt.
                    showInstallNotification(file, versionName)
                }
            }
        } else {
            showInstallNotification(file, versionName)
        }
    }

    /**
     * Show notification to install the update
     */
    private fun showInstallNotification(file: File, versionName: String) {
        try {
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                shareableApkUri(file)
            } else {
                Uri.fromFile(file)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.update_ready_title))
                .setContentText(context.getString(R.string.update_ready_description, versionName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_notification_icon,
                    context.getString(R.string.update_install_now),
                    pendingIntent
                )
                .build()

            notificationManager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            // Never let a download-complete notification crash the app (e.g. a FileProvider
            // "Failed to find configured root" when the APK landed on a volume our paths don't
            // cover). The download itself succeeded; degrade to the error notification.
            Timber.tag(TAG).e(e, "Failed to build install notification")
            showDownloadErrorNotification()
        }
    }

    /**
     * Resolve a content:// URI that FileProvider can actually serve for [file].
     *
     * `getExternalFilesDir(...)` can return a secondary/removable volume (e.g. an SD card)
     * that our `file_paths.xml` primary `<external-files-path>` root doesn't cover, so
     * `getUriForFile` throws `IllegalArgumentException: Failed to find configured root`
     * (SHIZUKUPLUS-6P). Fall back to a copy in `cacheDir`, which the `<cache-path>` root
     * always covers, so the install action still works.
     */
    private fun shareableApkUri(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w(e, "APK path not FileProvider-shareable; copying to cache")
            val cached = File(context.cacheDir, file.name)
            file.copyTo(cached, overwrite = true)
            FileProvider.getUriForFile(context, authority, cached)
        }
    }

    /**
     * Show error notification. [reason] is a DownloadManager.ERROR_* code (from COLUMN_REASON)
     * when known — a few documented, OEM-independent codes get distinct, self-diagnosing text
     * (#414) instead of the generic message.
     */
    private fun showDownloadErrorNotification(reason: Int? = null) {
        val messageRes = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.update_download_failed_storage
            DownloadManager.ERROR_CANNOT_RESUME -> R.string.update_download_failed_cannot_resume
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> R.string.update_download_failed_http
            else -> R.string.update_download_failed_message
        }
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.update_download_failed_title))
            .setContentText(context.getString(messageRes))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    /**
     * Install APK directly (for auto-install when enabled).
     * Must be called from a background coroutine.
     * @return true only if the APK was installed silently without user interaction.
     *   Returns false for any failure or timeout — the caller then shows the install notification.
     */
    suspend fun installApk(file: File): Boolean {
        return try {
            // 60 s covers piping a multi-MB APK over Shizuku stdin + pm install processing.
            withTimeoutOrNull(60_000) {
                val isRoot = withContext(Dispatchers.IO) {
                    runCatching { com.topjohnwu.superuser.Shell.getShell().isRoot }.getOrDefault(false)
                }
                val hasShizuku = rikka.shizuku.Shizuku.pingBinder()

                when {
                    isRoot -> {
                        // Root shell can read files in getExternalFilesDir on all API levels.
                        val result = withContext(Dispatchers.IO) {
                            com.topjohnwu.superuser.Shell.cmd("pm install -r -d \"${file.absolutePath}\"").exec()
                        }
                        if (result.isSuccess) {
                            Timber.tag(TAG).i("Silent install via root succeeded")
                            true
                        } else {
                            Timber.tag(TAG).w("Root install failed (signature mismatch?): ${result.out}")
                            UpdateInstaller.forceUpdateWithShizuku(context, file)
                        }
                    }
                    hasShizuku -> {
                        // Shell UID 2000 cannot read getExternalFilesDir on Android 11+ scoped
                        // storage. Read bytes in the app process (which owns the file) and pipe
                        // them to pm install via stdin — same pattern as compat hub install.
                        withContext(Dispatchers.IO) { installViaShizukuStdin(file) }
                    }
                    else -> false
                }
            } == true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "installApk failed unexpectedly")
            false
        }
    }

    /**
     * Reads the APK in the app process and streams it to `pm install` via Shizuku stdin,
     * sidestepping Android 11+ scoped-storage restrictions on shell UID 2000.
     */
    private fun installViaShizukuStdin(file: File): Boolean {
        return try {
            val apkBytes = file.readBytes()
            val script = "cat > /data/local/tmp/update.apk && chmod 644 /data/local/tmp/update.apk" +
                " && pm install -r -d /data/local/tmp/update.apk 2>&1; echo EXIT:\$?; rm -f /data/local/tmp/update.apk"
            val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
                ?: run {
                    Timber.tag(TAG).w("Shizuku.newProcess returned null")
                    return false
                }
            process.outputStream.use { it.write(apkBytes) }
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val exitCode = Regex("EXIT:(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
            val success = exitCode == 0
            if (success) {
                Timber.tag(TAG).i("Shizuku stdin install succeeded")
            } else {
                Timber.tag(TAG).w("Shizuku stdin install failed (exit=$exitCode): $output")
            }
            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "installViaShizukuStdin failed")
            false
        }
    }

    /**
     * Check if user has granted install permission
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageManager = context.packageManager
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Cancel ongoing downloads and coroutines. Call when the owner is done with this manager.
     */
    fun cancel() {
        monitorJob?.cancel()
        job.cancel()
    }

    /**
     * Clean up downloaded files
     */
    fun cleanup() {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles { file -> file.name.endsWith(".apk") }?.forEach { file ->
                file.delete()
                Timber.tag(TAG).d("Cleaned up old APK: ${file.name}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error cleaning up")
        }
    }
}
