package code.name.monkey.lost.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import code.name.monkey.lost.R
import code.name.monkey.lost.db.PlaylistDao
import code.name.monkey.lost.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import kotlin.random.Random
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX

// NEW sealed class to represent a download's final outcome
sealed class DownloadResult {
    abstract val videoId: String
    data class Success(override val videoId: String, val uri: Uri) : DownloadResult()
    data class Failure(override val videoId: String, val reason: String) : DownloadResult()
}


class DownloadService : LifecycleService(), KoinComponent {
    private val downloadManager: DownloadManager by inject()
    override fun onCreate() {
        super.onCreate()
        DownloadManager.createNotificationChannel(this, DownloadManager.CHANNEL_ID_DOWNLOADS)
        DownloadManager.createNotificationChannel(this, DownloadManager.CHANNEL_ID_FOREGROUND)
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
        downloadManager.downloading
            .onEach { inProgress ->
                if (inProgress.isEmpty()) {
                    Timber.tag(TAG).i("All downloads complete, stopping service.")
                    stopSelf()
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val song: Song? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_SONG, Song::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_SONG)
                }
                val targetPlaylistId: Long? = if (intent.hasExtra(EXTRA_PLAYLIST_ID)) {
                    intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L).takeIf { it != -1L }
                } else null

                if (song != null) {
                    downloadManager.enqueue(song, null)
                } else {
                    Timber.tag(TAG).e("Start download action received without a Song extra.")
                }
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val videoId: String? = intent.getStringExtra(EXTRA_VIDEO_ID)
                if (videoId != null) {
                    downloadManager.cancel(videoId)
                } else {
                    Timber.tag(TAG).e("Cancel download action received without a videoId extra.")
                }
            }
        }
        return START_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, DownloadManager.CHANNEL_ID_FOREGROUND)
            .setContentTitle("Lost Music Download Service")
            .setContentText("Running in background to manage downloads.")
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SpotifyPlaylist"
        private const val FOREGROUND_NOTIFICATION_ID = 1001 // Unique ID for the persistent foreground notification
        const val ACTION_START_DOWNLOAD = "DownloadService.START"
        const val ACTION_CANCEL_DOWNLOAD = "DownloadService.CANCEL"
        const val EXTRA_SONG = "DownloadService.EXTRA_SONG"
        const val EXTRA_VIDEO_ID = "DownloadService.EXTRA_VIDEO_ID"
        const val EXTRA_PLAYLIST_ID = "DownloadService.EXTRA_PLAYLIST_ID"
    }
}

class DownloadManager (
    private val context: Context
): KoinComponent {
    private val playlistDao:PlaylistDao by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloading = MutableStateFlow<Map<String, Int>>(emptyMap()) // videoId → progress
    val downloadResult = MutableSharedFlow<DownloadResult>(extraBufferCapacity = 10)
    private val jobs = mutableMapOf<String, Job>()
    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun enqueue(song: Song, targetPlaylistId: Long? = null) {
        val videoId = YTPlayerUtils.extractYouTubeVideoId(song.data)
        if (videoId == null) {
            Timber.tag(TAG).e("Failed to extract videoId for ${song.title}, data: ${song.data}")
            postFailureNotification(song, "Invalid video URL", song.id.toString())
            scope.launch { downloadResult.emit(DownloadResult.Failure(song.id.toString(), "Invalid video URL")) }
            return
        }
        if (jobs.containsKey(videoId)) {
            Timber.tag(TAG).i("Download already in progress for $videoId")
            return
        }

        val notificationId = videoId.hashCode()
        val job = scope.launch {
            var tempAudioFile: File? = null
            try {
                postInitialNotification(song, videoId)
                val tempFileName = "download_${videoId}_${Random.nextInt(10000)}.tmp"
                tempAudioFile = File(context.cacheDir, tempFileName)
                val resultUri = attemptDownloadWithRetry(song, videoId, tempAudioFile)
                withContext(Dispatchers.Main) {
                    try {
                        NotificationManagerCompat.from(context).cancel(notificationId)
                        Timber.tag(TAG).d("Cancelled progress notification $notificationId for completion.")
                    } catch (e: SecurityException) {
                        Timber.tag(TAG).e(e, "Failed to cancel progress notification. Permission missing?")
                    }
                }

                downloadResult.emit(DownloadResult.Success(videoId, resultUri))
                postCompleteNotification(song, notificationId, resultUri)

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download failed permanently for ${song.title} (videoId: $videoId)")
                postFailureNotification(song, e.message ?: "Unknown error", videoId)
                downloadResult.emit(DownloadResult.Failure(videoId, e.message ?: "Unknown error"))
            } finally {
                downloading.update { it - videoId }
                jobs.remove(videoId)
                tempAudioFile?.delete()
                Timber.tag(TAG).d("Cleaned up temporary file: ${tempAudioFile?.name}")
            }
        }
        jobs[videoId] = job
    }

    fun cancel(videoId: String) {
        jobs[videoId]?.let {
            it.cancel()
            Timber.tag(TAG).i("Cancelled download for $videoId")
            downloading.update { map -> map - videoId }
            jobs.remove(videoId)
        }
        try {
            NotificationManagerCompat.from(context).cancel(videoId.hashCode())
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to cancel progress notification on explicit user cancel.")
        }
    }

    private suspend fun attemptDownloadWithRetry(
        song: Song,
        videoId: String,
        tempAudioFile: File,
        maxRetries: Int = 3
    ): Uri {
        var lastError: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                downloadSong(song, videoId, tempAudioFile)
                return injectMetadataAndMove(song, videoId, tempAudioFile)
            } catch (e: Exception) {
                lastError = e
                Timber.tag(TAG).w(e, "Download attempt $attempt failed for ${song.title}. Retrying in 2 seconds...")
                if (currentCoroutineContext().isActive && attempt < maxRetries) {
                    delay(2000L)
                } else {
                    break
                }
            }
        }
        throw lastError ?: IOException("Download failed after $maxRetries attempts for unknown reason.")
    }

    @OptIn(UnstableApi::class)
    private suspend fun downloadSong(
        song: Song,
        videoId: String,
        outputFile: File
    ) {
        val streamData = withContext(Dispatchers.IO) {
            YouTube.player(videoId = videoId, client = WEB_REMIX).getOrThrow()
        }
        val audioStream = streamData.streamingData?.adaptiveFormats
            ?.filter { it.mimeType.contains("audio") }
            ?.maxByOrNull { it.bitrate }
            ?: streamData.streamingData?.formats?.firstOrNull()
            ?: throw IOException("No suitable audio stream found for videoId: $videoId")
        val url = audioStream.url
        val dataSource = DefaultHttpDataSource.Factory().createDataSource()
        var output: FileOutputStream? = null

        try {
            output = FileOutputStream(outputFile)

            val dataSpec = DataSpec(Uri.parse(url))
            val totalBytes = dataSource.open(dataSpec)
            var bytesRead = 0L
            val buffer = ByteArray(4096)
            var readCount: Int

            while (dataSource.read(buffer, 0, buffer.size).also { readCount = it } != -1) {
                if (!currentCoroutineContext().isActive) {
                    throw IOException("Download cancelled.")
                }
                output.write(buffer, 0, readCount)
                bytesRead += readCount.toLong()

                if (System.currentTimeMillis() % 500 < 50) {
                    val progress = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt().coerceIn(0, 99) else 0
                    updateProgressNotification(song, videoId, progress)
                    downloading.update { it + (videoId to progress) }
                }
            }
            output.flush()
            Timber.tag(TAG).i("Download successful for ${song.title} to temporary file: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download error for ${song.title}")
            outputFile.delete()
            throw e
        } finally {
            try { dataSource.close() } catch (e: IOException) { Timber.tag(TAG).e(e, "Failed to close data source") }
            try { output?.close() } catch (e: IOException) { Timber.tag(TAG).e(e, "Failed to close output stream") }
        }
    }

    private fun injectMetadataAndMove(song: Song, videoId: String, tempAudioFile: File): Uri {
        try {
            val audioFile = AudioFileIO.read(tempAudioFile)
            val tag = audioFile.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artistName)
            tag.setField(FieldKey.ALBUM, song.albumName.ifBlank { "Unknown Album" })

            try {
                val artworkUrl = "https://img.youtube.com/vi/${videoId}/mqdefault.jpg"
                val artwork = StandardArtwork()
                artwork.binaryData = URL(artworkUrl).readBytes()
                artwork.mimeType = "image/jpeg"
                artwork.description = "Cover"
                tag.setField(artwork)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to download or set artwork for ${song.title}")
            }

            AudioFileIO.write(audioFile)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to inject metadata for ${song.title}")
        }

        val resolver = context.contentResolver
        val mimeType = "audio/mpeg"
        val displayName = "${sanitize(song.artistName)} - ${sanitize(song.title)}.mp3"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LostMusic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val appDir = File(musicDir, "LostMusic")
                appDir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(appDir, displayName).absolutePath)
            }
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artistName)
            put(MediaStore.Audio.Media.ALBUM, song.albumName)
            put(MediaStore.Audio.Media.DURATION, song.duration)
            put(MediaStore.Audio.Media.YEAR, song.year)
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                throw IOException("Failed to create new MediaStore entry.")
            }
            resolver.openOutputStream(uri).use { os ->
                FileInputStream(tempAudioFile).use { input ->
                    input.copyTo(os!!)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Timber.tag(TAG).i("File moved successfully to MediaStore URI: $uri")
            return uri
        } catch (e: Exception) {
            if (uri != null) {
                resolver.delete(uri, null, null)
            }
            throw IOException("Error saving file to MediaStore.", e)
        }
    }
    private fun updateProgressNotification(song: Song, videoId: String, progress: Int) {
        if (!canPostNotifications()) return
        val notificationId = videoId.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Downloading: ${song.title}")
            .setContentText("Progress: $progress%")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(createCancelAction(videoId))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post progress notification.")
        }
    }
    private fun postInitialNotification(song: Song, videoId: String) {
        if (!canPostNotifications()) return
        val notificationId = videoId.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Starting Download: ${song.title}")
            .setContentText("Download is starting...")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(0, 0, true) // Indeterminate progress
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(createCancelAction(videoId))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post initial notification.")
        }
    }
    private fun postCompleteNotification(song: Song, notificationId: Int, contentUri: Uri) {
        if (!canPostNotifications()) return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Download Complete")
            .setContentText("${song.title} downloaded successfully.")
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post complete notification.")
        }
    }
    private fun postFailureNotification(song: Song, reason: String, uniqueId: String) {
        if (!canPostNotifications()) return
        val notificationId = uniqueId.hashCode() + 50000 + Random.nextInt(100)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Download Failed: ${song.title}")
            .setContentText("Reason: $reason")
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post failure notification.")
        }
    }
    private fun createCancelAction(videoId: String): NotificationCompat.Action {
        val cancelIntent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
        }
        val pendingCancelIntent = PendingIntent.getService(
            context,
            videoId.hashCode() + 1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            pendingCancelIntent
        )
    }
    private fun sanitize(text: String): String {
        return text.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    companion object {
        private const val TAG = "DownloadManager"
        const val CHANNEL_ID_DOWNLOADS = "downloads_channel"
        const val CHANNEL_ID_FOREGROUND = "downloads_foreground_channel"
        fun createNotificationChannel(context: Context, channelId: String) {
            val name = when (channelId) {
                CHANNEL_ID_DOWNLOADS -> "Downloads"
                CHANNEL_ID_FOREGROUND -> "Lost Music Service"
                else -> "Downloads"
            }
            val descriptionText = when (channelId) {
                CHANNEL_ID_DOWNLOADS -> "Notifications for file download progress and completion."
                CHANNEL_ID_FOREGROUND -> "Persistent notification for background download service."
                else -> "File download notifications"
            }
            val importance = when (channelId) {
                CHANNEL_ID_DOWNLOADS -> NotificationManager.IMPORTANCE_DEFAULT
                CHANNEL_ID_FOREGROUND -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}