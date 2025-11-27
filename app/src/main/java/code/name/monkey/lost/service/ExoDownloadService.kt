package code.name.monkey.lost.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import timber.log.Timber
import java.net.URL

@OptIn(UnstableApi::class)
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.downloading,
    0
), KoinComponent {
    private val downloadUtil: DownloadUtil = YTPlayerUtils.getDownloadUtil()!!
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    private val fetchingBitmaps = mutableSetOf<String>()
    private lateinit var terminalStateNotificationHelper: TerminalStateNotificationHelper

    override fun onCreate() {
        super.onCreate()
        // Create the NotificationChannel on API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.downloading)
            val descriptionText = "Background downloads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        terminalStateNotificationHelper = TerminalStateNotificationHelper(
            this,
            downloadUtil.downloadNotificationHelper,
            NOTIFICATION_ID + 1
        )
        downloadUtil.downloadManager.addListener(terminalStateNotificationHelper)
    }

    override fun onDestroy() {
        downloadUtil.downloadManager.removeListener(terminalStateNotificationHelper)
        serviceScope.cancel()
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
        
        val title = if (downloads.size == 1) {
            getTitle(downloads[0].request.data)
        } else {
            resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size)
        }

        val notification = downloadUtil.downloadNotificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_download,
            null,
            title,
            downloads,
            notMetRequirements
        )

        val builder = Notification.Builder.recoverBuilder(this, notification)

        // Set the song image as the large icon (thumbnail)
        val download = downloads.firstOrNull()
        if (download != null) {
            val videoId = download.request.id
            val bitmap = bitmapCache[videoId]
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            } else if (!fetchingBitmaps.contains(videoId)) {
                fetchingBitmaps.add(videoId)
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("https://img.youtube.com/vi/$videoId/mqdefault.jpg")
                        val bmp = BitmapFactory.decodeStream(url.openStream())
                        if (bmp != null) {
                            withContext(Dispatchers.Main) {
                                bitmapCache[videoId] = bmp
                                invalidateForegroundNotification()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to load notification icon for $videoId")
                    } finally {
                        withContext(Dispatchers.Main) {
                            fetchingBitmaps.remove(videoId)
                        }
                    }
                }
            }
        }

        return builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_close),
                getString(android.R.string.cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ExoDownloadService::class.java).setAction(
                        REMOVE_ALL_PENDING_DOWNLOADS
                    ),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        ).build()
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
