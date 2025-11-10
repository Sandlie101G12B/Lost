package code.name.monkey.lost.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber


@OptIn(UnstableApi::class)
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.downloading,
    0
), KoinComponent {
    private val downloadUtil: DownloadUtil by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("SpotifyPlaylist").d("onStartCommand with action: ${intent?.action}")
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            Timber.tag("SpotifyPlaylist").d("Removing all pending downloads")
            downloadManager.currentDownloads.forEach { download ->
                Timber.tag("SpotifyPlaylist").d("Removing download: ${download.request.id}")
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
        Timber.tag("SpotifyPlaylist").d("getForegroundNotification for ${downloads.size} downloads")
        return Notification.Builder.recoverBuilder(
            this, downloadUtil.downloadNotificationHelper.buildProgressNotification(
                this,
                R.drawable.ic_download,
                null,
                if (downloads.size == 1) Util.fromUtf8Bytes(downloads[0].request.data)
                else resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size),
                downloads,
                notMetRequirements
            )
        ).addAction(
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
            Timber.tag("SpotifyPlaylist").d("onDownloadChanged: ${download.request.id}, state: ${download.state}")
            if (download.state == Download.STATE_FAILED) {
                Timber.tag("SpotifyPlaylist").e(finalException, "Download failed: ${download.request.id}")
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.ic_error,
                    null,
                    Util.fromUtf8Bytes(download.request.data)
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            } else if (download.state == Download.STATE_COMPLETED) {
                Timber.tag("SpotifyPlaylist").d("Download completed: ${download.request.id}")
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
    }
}
