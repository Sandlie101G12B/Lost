package code.name.monkey.lost.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.annotation.RequiresPermission
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import code.name.monkey.lost.R
import code.name.monkey.lost.util.DownloadUtil
import code.name.monkey.lost.util.YTPlayerUtils
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import timber.log.Timber

@OptIn(UnstableApi::class)
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.downloading,
    0
), KoinComponent {
    private val downloadUtil: DownloadUtil = YTPlayerUtils.getDownloadUtil()!!
    private lateinit var terminalStateNotificationHelper: TerminalStateNotificationHelper

    private val speedTracker = mutableMapOf<String, Pair<Long, Long>>() // videoId -> (bytes, time)
    private val previousSecondaryIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val name = getString(R.string.downloading)
        val descriptionText = "Background downloads"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        terminalStateNotificationHelper = TerminalStateNotificationHelper(
            this,
            downloadUtil.downloadNotificationHelper,
            NOTIFICATION_ID + 1
        )
        downloadUtil.downloadManager.addListener(terminalStateNotificationHelper)
    }

    override fun onDestroy() {
        downloadUtil.downloadManager.removeListener(terminalStateNotificationHelper)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand with action: ${intent?.action}")
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            Timber.tag(TAG).d("Removing all pending downloads")
            downloadManager.currentDownloads.forEach { download ->
                Timber.tag(TAG).d("Removing download: ${download.request.id}")
                downloadManager.removeDownload(download.request.id)
            }
        } else if (intent?.action == ACTION_CANCEL_DOWNLOAD) {
            val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
            if (id != null) {
                Timber.tag(TAG).d("Cancelling download: $id")
                downloadManager.removeDownload(id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getDownloadManager() = downloadUtil.downloadManager

    @RequiresPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED)
    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        Timber.tag(TAG).d("getForegroundNotification for ${downloads.size} downloads")

        // Handle multiple notifications for downloads > 1
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        // Identify secondary downloads (all except the first one, which belongs to the service foreground notification)
        val secondaryDownloads = if (downloads.size > 1) downloads.subList(1, downloads.size) else emptyList()
        val currentSecondaryIds = secondaryDownloads.map { it.request.id }.toSet()

        // Cancel notifications for downloads that are no longer secondary (finished or moved to primary)
        val toCancel = previousSecondaryIds - currentSecondaryIds
        toCancel.forEach { videoId ->
            notificationManager.cancel(videoId.hashCode())
            speedTracker.remove(videoId)
        }
        previousSecondaryIds.clear()
        previousSecondaryIds.addAll(currentSecondaryIds)

        // Update/Post secondary notifications
        secondaryDownloads.forEach { download ->
            val notification = buildDownloadNotification(download, notMetRequirements)
            notificationManager.notify(download.request.id.hashCode(), notification)
        }

        // Return the primary notification (for the first download)
        return if (downloads.isNotEmpty()) {
            buildDownloadNotification(downloads[0], notMetRequirements)
        } else {
            // Fallback empty notification if list is empty (shouldn't happen in getForegroundNotification usually)
            downloadUtil.downloadNotificationHelper.buildProgressNotification(
                this,
                R.drawable.ic_download,
                null,
                null,
                downloads,
                notMetRequirements
            )
        }
    }

    private fun buildDownloadNotification(download: Download, notMetRequirements: Int): Notification {
        val videoId = download.request.id
        val title = getTitle(download.request.data)
        
        val bytesDownloaded = download.bytesDownloaded
        val contentLength = download.contentLength
        val progress = if (contentLength != -1L && contentLength > 0) {
            (bytesDownloaded * 100 / contentLength).toInt()
        } else {
            0
        }
        val indeterminate = contentLength == -1L

        // Calculate Speed
        val now = System.currentTimeMillis()
        var speedString = ""
        val lastTracked = speedTracker[videoId]
        if (lastTracked != null) {
            val deltaBytes = bytesDownloaded - lastTracked.first
            val deltaTime = now - lastTracked.second
            if (deltaTime > 0) {
                val speedBytesPerSec = (deltaBytes * 1000) / deltaTime
                // Average or smooth? For now, instantaneous.
                speedString = " • ${Formatter.formatFileSize(this, speedBytesPerSec)}/s"
            }
        }
        // Update tracker
        speedTracker[videoId] = bytesDownloaded to now

        // Format Sizes
        val downloadedSize = Formatter.formatFileSize(this, bytesDownloaded)
        val totalSize = if (contentLength != -1L) Formatter.formatFileSize(this, contentLength) else "?"
        val progressText = "$downloadedSize / $totalSize$speedString"

        val builder = Notification.Builder(this, CHANNEL_ID)

        builder.setContentTitle(title)
            .setContentText(progressText)
            .setSmallIcon(R.drawable.ic_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
        
        // Add Cancel Action
        val cancelIntent = Intent(this, ExoDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, videoId)
        }
        val pendingIntent = PendingIntent.getService(
            this, 
            videoId.hashCode(), 
            cancelIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_close),
                getString(android.R.string.cancel),
                pendingIntent
            ).build()
        )

        return builder.build()
    }

    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        @OptIn(UnstableApi::class)
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            // Clean up speed tracker for finished/failed downloads?
            // We rely on getForegroundNotification logic to clean up secondary IDs.
            
            Timber.tag(TAG).d("onDownloadChanged: ${download.request.id}, state: ${download.state}")
            if (download.state == Download.STATE_FAILED) {
                Timber.tag(TAG).e(finalException, "Download failed: ${download.request.id}")
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.ic_download,
                    null,
                    getTitle(download.request.data)
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            } else if (download.state == Download.STATE_COMPLETED) {
                Timber.tag(TAG).d("Download completed: ${download.request.id}")
                val notification = notificationHelper.buildDownloadCompletedNotification(
                    context,
                    R.drawable.ic_download,
                    null,
                    getTitle(download.request.data)
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        private val TAG = "YTPlayerUtils"
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
        const val ACTION_CANCEL_DOWNLOAD = "ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        fun getTitle(data: ByteArray): String {
            return try {
                val stringData = Util.fromUtf8Bytes(data)
                if (stringData.startsWith("{")) {
                    val json = JSONObject(stringData)
                    json.optString("title", "Unknown")
                } else {
                    if (stringData.isNotEmpty()) stringData else "Unknown"
                }
            } catch (_: Exception) {
                "Unknown"
            }
        }
    }
}
