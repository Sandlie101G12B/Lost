package code.name.monkey.lost.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import code.name.monkey.lost.R
import code.name.monkey.lost.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

class DownloadService : LifecycleService(), KoinComponent {
    private val downloadManager: DownloadManager by inject()

     // Keep if minSdk < N, otherwise can be removed if minSdk is N+
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
                song?.let {
                    downloadManager.enqueue(it)
                } ?: Timber.tag(TAG).e("Song extra was null in onStartCommand")
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
                downloadManager.cancel(videoId)
            }
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_START_DOWNLOAD = "DownloadService.START"
        const val ACTION_CANCEL_DOWNLOAD = "DownloadService.CANCEL"
        const val EXTRA_SONG = "DownloadService.EXTRA_SONG"
        const val EXTRA_VIDEO_ID = "DownloadService.EXTRA_VIDEO_ID"
    }
}

class DownloadManager constructor(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloading = MutableStateFlow<Map<String, Int>>(emptyMap()) // videoId → progress
    val completed = MutableSharedFlow<Uri>(extraBufferCapacity = 10)

    private val jobs = mutableMapOf<String, Job>()

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun enqueue(song: Song) {
        val videoId = YTPlayerUtils.extractYouTubeVideoId(song.data)
        if (videoId == null) {
            Timber.tag(TAG).e("Failed to extract videoId for ${song.title}, data: ${song.data}")
            postFailureNotification(song, "Invalid video URL")
            return
        }
        if (jobs.containsKey(videoId)) {
            Timber.tag(TAG).i("Download already in progress for $videoId")
            return
        }

        val job = scope.launch {
            var tempAudioFile: File? = null
            try {
                postInitialNotification(song, videoId)
                // Create a temporary file in cache directory
                val tempFileName = "${videoId}_${System.currentTimeMillis()}.tmp"
                tempAudioFile = File(context.cacheDir, tempFileName)

                val resultUri = downloadSong(song, videoId, tempAudioFile)
                completed.emit(resultUri)
                postCompleteNotification(song, videoId.hashCode(), resultUri)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download failed for ${song.title} (videoId: $videoId)")
                postFailureNotification(song, e.message ?: "Unknown error", videoId)
            } finally {
                downloading.update { it - videoId }
                jobs.remove(videoId)
                tempAudioFile?.delete() // Ensure temporary file is deleted
            }
        }
        jobs[videoId] = job
    }

    fun cancel(videoId: String) {
        jobs[videoId]?.cancel()
        jobs.remove(videoId)
        downloading.update { it - videoId }
        val notificationId = videoId.hashCode()
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
            Timber.tag(TAG).i("Cancelled download and notification for $videoId")
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(
                e,
                "Failed to cancel notification $notificationId. POST_NOTIFICATIONS permission missing?")
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun downloadSong(song: Song, videoId: String, tempAudioFile: File): Uri {
        val playbackDataResult = YTPlayerUtils.getPlaybackData(
            videoId = videoId,
            audioQuality = AudioQuality.AUTO
        )

        return playbackDataResult.fold(
            onSuccess = { playbackData ->
                val streamUrl = playbackData.streamUrl
                if (streamUrl.isBlank()) {
                    throw HttpDataSource.HttpDataSourceException(
                        java.net.MalformedURLException("Stream URL from PlaybackData is blank"),
                        DataSpec(Uri.EMPTY),
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN
                    )
                }

                // Download audio to temporary file
                val httpDataSource: HttpDataSource = DefaultHttpDataSource.Factory().createDataSource()
                val dataSpec = DataSpec(streamUrl.toUri())
                val contentLength = httpDataSource.open(dataSpec)
                val notificationId = videoId.hashCode()

                FileOutputStream(tempAudioFile).use { outputStream ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgress = 0
                    val coroutineCtx = currentCoroutineContext()
                    while (coroutineCtx.isActive) {
                        bytesRead = httpDataSource.read(buffer, 0, buffer.size)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        val progress = if (contentLength > 0) {
                            ((totalBytesRead * 100) / contentLength).toInt()
                        } else -1 // Indeterminate progress

                        if (progress != lastProgress) {
                            lastProgress = progress
                            downloading.update { it + (videoId to progress) }
                            if (canPostNotifications()) updateProgressNotification(notificationId, song.title, progress)
                        }
                    }
                    if (!coroutineCtx.isActive) {
                        throw kotlinx.coroutines.CancellationException("Download cancelled (audio phase) for $videoId")
                    }
                }
                httpDataSource.close()

                try {
                    val audioFile = AudioFileIO.read(tempAudioFile)
                    val tag = audioFile.tagOrCreateDefault

                    // Set metadata using FieldKey
                    tag.setField(FieldKey.TITLE, song.title)
                    tag.setField(FieldKey.ARTIST, song.artistName)
                    tag.setField(FieldKey.ALBUM, song.albumName)
                    if (song.year > 0) {
                        tag.setField(FieldKey.YEAR, song.year.toString())
                    }
                    if (song.trackNumber > 0) {
                        tag.setField(FieldKey.TRACK, song.trackNumber.toString())
                    }
                    song.albumArtist?.takeIf { it.isNotBlank() }?.let {
                        tag.setField(FieldKey.ALBUM_ARTIST, it)
                    }
                    song.composer?.takeIf { it.isNotBlank() }?.let {
                        tag.setField(FieldKey.COMPOSER, it)
                    }
                    // Genre might not be in the current Song model, add if available
                    // tag.setField(FieldKey.GENRE, song.genreName) 

                    // Thumbnail URL from YouTube
                    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
                    Timber.tag(TAG).d("Using thumbnail URL: $thumbnailUrl")

                    // Save the image to a temporary file
                    val tempImageFile = File(context.cacheDir, "${videoId}_thumb.jpg")
                    withContext(Dispatchers.IO) {
                        URL(thumbnailUrl).openStream().use { input ->
                            FileOutputStream(tempImageFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    // Create artwork from the temp image file
                    val artwork = ArtworkFactory.createArtworkFromFile(tempImageFile)
                    tag.deleteArtworkField() // Clear existing artwork first
                    tag.setField(artwork)
                    audioFile.commit()
                    Timber.tag(TAG).d("Artwork and metadata embedded successfully for $videoId")

                    // Clean up temp image file
                    tempImageFile.delete()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to download or embed artwork/metadata for $videoId")
                }

                val fileExtension = "mp3" 
                val mediaStoreMimeType = "audio/mp3"


                val finalFileName = "${sanitize(song.title)}_${sanitize(song.artistName)}.$fileExtension"

                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(song.title))
                    put(MediaStore.MediaColumns.MIME_TYPE, mediaStoreMimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lost")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    put(MediaStore.Audio.Media.ARTIST, song.artistName)
                    put(MediaStore.Audio.Media.ALBUM, song.albumName)
                    put(MediaStore.Audio.Media.TITLE, song.title)
                    if (song.year > 0) put(MediaStore.Audio.Media.YEAR, song.year)
                    if (song.duration > 0) put(MediaStore.Audio.Media.DURATION, song.duration)
                    song.albumArtist?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ALBUM_ARTIST, it) }
                    song.composer?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.COMPOSER, it) }
                    if (song.trackNumber > 0) put(MediaStore.Audio.Media.TRACK, song.trackNumber)
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val itemUri = resolver.insert(collection, values)
                    ?: throw Exception("Failed to insert MediaStore item for $finalFileName. MIME used: $mediaStoreMimeType")

                resolver.openOutputStream(itemUri)?.use { mediaStoreOutput ->
                    FileInputStream(tempAudioFile).use { fileInput ->
                        fileInput.copyTo(mediaStoreOutput)
                    }
                } ?: throw Exception("Failed to open output stream for MediaStore item $itemUri")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(itemUri, finishValues, null, null)
                }
                itemUri
            },
            onFailure = { error ->
                Timber.tag(TAG).e(error, "Failed to get playback data for ${song.title} (videoId: $videoId)")
                throw error
            }
        )
    }

    private fun postInitialNotification(song: Song, videoId: String) {
        if (!canPostNotifications()) return
        val notificationId = videoId.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Starting download: ${song.title}")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, 0, true)
            .setOngoing(true)

        val cancelIntent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
        }
        val pendingCancelIntentFlags =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingCancelIntent = PendingIntent.getService(context, notificationId, cancelIntent, pendingCancelIntentFlags)
        builder.addAction(R.drawable.ic_close, "Cancel", pendingCancelIntent)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post initial notification $notificationId")
        }
    }

    private fun updateProgressNotification(notificationId: Int, title: String, progress: Int) {
        if (!canPostNotifications()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading: $title")
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true) // Indeterminate
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post progress notification $notificationId")
        }
    }

    private fun postCompleteNotification(song: Song, notificationId: Int, resultUri: Uri) {
        if (!canPostNotifications()) return

        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resultUri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Ensure a unique request code for PendingIntent if multiple songs can complete
             putExtra("notification_id", notificationId) // Optional: for debugging or specific handling
        }
        val pendingPlayIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingPlayIntent = PendingIntent.getActivity(
            context,
            notificationId, // Use notificationId for a unique request code
            playIntent,
            pendingPlayIntentFlags
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText(song.title)
            .setSmallIcon(R.drawable.ic_download_done)
            .setContentIntent(pendingPlayIntent)
            .setAutoCancel(true) // Dismiss notification on tap
            .setOngoing(false)
            .setProgress(0,0,false)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post complete notification $notificationId")
        }
    }

    private fun postFailureNotification(song: Song, errorMsg: String, videoId: String? = null) {
        if (!canPostNotifications()) return
        val notificationId = videoId?.hashCode() ?: (song.ytID ?: song.title).hashCode()

        val retryIntent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_SONG, song)
            // Ensure a unique request code for PendingIntent if multiple songs can fail
            data = Uri.parse("lost://retry/${song.ytID ?: song.id}") // Unique data URI
        }
        val pendingRetryIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingRetryIntent = PendingIntent.getService(
            context,
            notificationId, // Use notificationId for a unique request code
            retryIntent,
            pendingRetryIntentFlags
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download failed: ${song.title}")
            .setContentText(errorMsg)
            .setSmallIcon(R.drawable.ic_error)
            .setContentIntent(pendingRetryIntent)
            .setAutoCancel(true) // Dismiss notification on tap
            .setOngoing(false)
            .setProgress(0,0,false)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post failure notification $notificationId")
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|.]"), "_")

    companion object {
        private const val TAG = "DownloadManager"
        const val CHANNEL_ID = "downloads_channel"
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Channel creation is API 26+
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Timber.tag(TAG)
                        .w("POST_NOTIFICATIONS permission not granted. Cannot create notification channel.")
                    return
                }
                try {
                    val name = context.getString(R.string.download_notification_channel_name)
                    val descriptionText = context.getString(R.string.download_notification_channel_description)
                    val importance = NotificationManager.IMPORTANCE_LOW
                    val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                        description = descriptionText
                    }
                    val notificationManager: NotificationManager? =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.createNotificationChannel(channel)
                    Timber.tag(TAG).i("Notification channel '$CHANNEL_ID' created.")
                } catch (e: SecurityException) {
                    Timber.tag(TAG).e(e, "SecurityException creating notification channel.")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to create notification channel '$CHANNEL_ID'")
                }
            }
        }
    }
}
