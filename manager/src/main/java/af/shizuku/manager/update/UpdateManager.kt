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
import io.sentry.Sentry
import af.shizuku.manager.home.HomeActivity
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

        /**
         * Action for download complete broadcast
         */
        const val ACTION_DOWNLOAD_COMPLETE = "af.shizuku.manager.action.DOWNLOAD_COMPLETE"
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

        val fileName = "Shizuku+-v$versionName.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        // Check if file already exists and delete it
        if (file.exists()) {
            file.delete()
        }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle(context.getString(R.string.update_downloading_title))
            .setDescription(context.getString(R.string.update_downloading_description, versionName))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
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
            Timber.tag(TAG).e(e, "Failed to start download")
            Sentry.captureException(e)
            showDownloadErrorNotification()
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
                        downloadManager.query(query)
                    } catch (e: IllegalArgumentException) {
                        Timber.tag(TAG).w(e, "DownloadManager.query rejected by system; retrying bare filter")
                        null
                    }

                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val progressIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_RUNNING
                        val progress = if (progressIdx >= 0) cursor.getInt(progressIdx) else 0
                        val total = if (totalIdx >= 0) cursor.getInt(totalIdx) else 0

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                cursor.close()
                                Timber.tag(TAG).d("Download completed: ${file.absolutePath}")
                                onDownloadComplete(file, versionName)
                                break
                            }
                            DownloadManager.STATUS_FAILED -> {
                                cursor.close()
                                Timber.tag(TAG).e("Download failed")
                                showDownloadErrorNotification()
                                break
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                // Waiting for network
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                // Update progress notification
                                val percent = if (total > 0) (progress * 100 / total) else 0
                                updateProgressNotification(percent, versionName)
                            }
                        }
                        cursor.close()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error monitoring download")
                    Sentry.captureException(e)
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
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.update_downloading_title))
            .setContentText(context.getString(R.string.update_downloading_progress, versionName, progress))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
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

        // Show install notification
        showInstallNotification(file, versionName)
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
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(context.getString(R.string.update_ready_title))
                .setContentText(context.getString(R.string.update_ready_description, versionName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_system_icon,
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
            Sentry.captureException(e)
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
     * Show error notification
     */
    private fun showDownloadErrorNotification() {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.update_download_failed_title))
            .setContentText(context.getString(R.string.update_download_failed_message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    /**
     * Install APK directly (for auto-install when enabled).
     * Must be called from a background coroutine — Shell.getShell() blocks until a shell is ready.
     */
    suspend fun installApk(file: File) {
        try {
            val isRootOrShizuku = withContext(Dispatchers.IO) {
                com.topjohnwu.superuser.Shell.getShell().isRoot || rikka.shizuku.Shizuku.pingBinder()
            }
            if (isRootOrShizuku) {
                Timber.tag(TAG).d("Attempting silent install via Shizuku/Root...")
                val result = withContext(Dispatchers.IO) {
                    com.topjohnwu.superuser.Shell.cmd("pm install -r -d \"${file.absolutePath}\"").exec()
                }
                if (result.isSuccess) {
                    Timber.tag(TAG).i("Silent install successful")
                    return
                } else {
                    Timber.tag(TAG).w("Silent install failed (likely signature mismatch): ${result.out}")
                    if (UpdateInstaller.forceUpdateWithShizuku(context, file)) {
                        Timber.tag(TAG).i("Force-update background script initiated to handle signature mismatch")
                        return
                    }
                }
            }

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

            context.startActivity(installIntent)
            Timber.tag(TAG).d("Install intent launched for: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to launch install intent")
            Sentry.captureException(e)
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
