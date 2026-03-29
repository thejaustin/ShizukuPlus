package moe.shizuku.manager.update

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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.sentry.Sentry
import moe.shizuku.manager.R
import moe.shizuku.manager.home.HomeActivity
import java.io.File

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
        const val ACTION_DOWNLOAD_COMPLETE = "moe.shizuku.manager.action.DOWNLOAD_COMPLETE"
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1

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

        val fileName = "ShizukuPlus-v$versionName.apk"
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
        request.addRequestHeader("User-Agent", "ShizukuPlus/${versionName}")

        try {
            downloadId = downloadManager.enqueue(request)

            // Save download ID
            context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong(DOWNLOAD_ID_PREF, downloadId)
                .apply()

            Log.d(TAG, "Download started: $downloadUrl, ID: $downloadId")

            // Monitor download progress
            monitorDownload(downloadId, file, versionName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            Sentry.captureException(e)
            showDownloadErrorNotification()
        }
    }

    /**
     * Monitor download progress
     */
    private fun monitorDownload(downloadId: Long, file: File, versionName: String) {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        val progress = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                cursor.close()
                                Log.d(TAG, "Download completed: ${file.absolutePath}")
                                onDownloadComplete(file, versionName)
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                cursor.close()
                                Log.e(TAG, "Download failed")
                                showDownloadErrorNotification()
                                return
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

                    // Continue monitoring
                    handler.postDelayed(this, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring download", e)
                    Sentry.captureException(e)
                }
            }
        })
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
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
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
     * Install APK directly (for auto-install when enabled)
     */
    fun installApk(file: File) {
        try {
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            Log.d(TAG, "Install intent launched for: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch install intent", e)
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
     * Clean up downloaded files
     */
    fun cleanup() {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles { file -> file.name.endsWith(".apk") }?.forEach { file ->
                file.delete()
                Log.d(TAG, "Cleaned up old APK: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up", e)
        }
    }
}
