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
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope // Required for coroutine handling in LifecycleService
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    override fun onCreate() {
        super.onCreate()
        // 1. Create both notification channels
        DownloadManager.createNotificationChannel(this, DownloadManager.CHANNEL_ID_DOWNLOADS)
        DownloadManager.createNotificationChannel(this, DownloadManager.CHANNEL_ID_FOREGROUND)

        // 2. Build and start the service as foreground
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

        // 3. Listen to the download queue to stop the service when all jobs are done
        downloadManager.downloading
            .onEach { inProgress ->
                if (inProgress.isEmpty()) {
                    Timber.tag(TAG).i("All downloads complete, stopping service.")
                    stopSelf()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun buildForegroundNotification(): Notification {
        // A simple, low-priority notification to keep the service running
        return NotificationCompat.Builder(this, DownloadManager.CHANNEL_ID_FOREGROUND)
            // Note: R.string.download_service_running and R.string.download_service_background_message are assumed to be defined
            .setContentTitle(getString(R.string.download_service_running))
            .setContentText(getString(R.string.download_service_background_message))
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

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
        private const val FOREGROUND_NOTIFICATION_ID = 1001 // Unique ID for the persistent foreground notification
        const val ACTION_START_DOWNLOAD = "DownloadService.START"
        const val ACTION_CANCEL_DOWNLOAD = "DownloadService.CANCEL"
        const val EXTRA_SONG = "DownloadService.EXTRA_SONG"
        const val EXTRA_VIDEO_ID = "DownloadService.EXTRA_VIDEO_ID"
    }
}

class DownloadManager (
    private val context: Context
) {
    // Note: Dispatchers.IO is good for file operations and network calls
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloading = MutableStateFlow<Map<String, Int>>(emptyMap()) // videoId → progress
    val completed = MutableSharedFlow<Uri>(extraBufferCapacity = 10)

    private val jobs = mutableMapOf<String, Job>()

    private fun canPostNotifications(): Boolean {
        // Only needs to check for POST_NOTIFICATIONS on Android 13 (Tiramisu) and above
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true // On older versions, we assume we can post notifications
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
                // Post the initial notification which includes the cancel button
                postInitialNotification(song, videoId)

                // Create a temporary file in cache directory
                val tempFileName = "${videoId}_${System.currentTimeMillis()}.tmp"
                tempAudioFile = File(context.cacheDir, tempFileName)

                val resultUri = downloadSong(song, videoId, tempAudioFile)
                completed.emit(resultUri)
                postCompleteNotification(song, videoId.hashCode(), resultUri)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download failed for ${song.title} (videoId: $videoId)")
                // Pass videoId to failure notification for better unique ID generation
                postFailureNotification(song, e.message ?: "Unknown error", videoId)
            } finally {
                // Remove from active downloads and jobs map
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
            // Cancel the individual download notification
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
        // Assume YTPlayerUtils and AudioQuality exist and work as intended
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
                    // Increased buffer size from 16KB to 128KB to reduce I/O overhead and increase download speed.
                    val buffer = ByteArray(128 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgress = -1 // Start at -1 to ensure first progress is always posted
                    val coroutineCtx = currentCoroutineContext()
                    while (coroutineCtx.isActive) {
                        bytesRead = httpDataSource.read(buffer, 0, buffer.size)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        val progress = if (contentLength > 0) {
                            ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100) // Ensure progress is between 0 and 100
                        } else -1 // Indeterminate progress

                        // Update progress only if it changes significantly or if it's indeterminate
                        if (progress != lastProgress && (progress % 5 == 0 || progress == -1 || lastProgress == -1 || progress == 100)) {
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

                // --- Start Metadata Tagging (writes to tempAudioFile) ---
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
                    audioFile.commit() // Tags and artwork are committed to tempAudioFile
                    Timber.tag(TAG).d("Artwork and metadata embedded successfully for $videoId")

                    // Clean up temp image file
                    tempImageFile.delete()
                } catch (e: Exception) {
                    // Log the failure but continue to save the audio file without tags
                    Timber.tag(TAG).e(e, "Failed to download or embed artwork/metadata for $videoId. Proceeding without tags.")
                }
                // --- End Metadata Tagging ---

                // CRITICAL FIX: Calculate the file size after all data (audio + tags) has been written.
                val fileSize = tempAudioFile.length()

                // --- FIX: Change to M4A/AAC format, which is the likely actual format and is natively supported ---
                val fileExtension = "m4a" // Changed from "mp3"
                val mediaStoreMimeType = "audio/mp4" // Changed from "audio/mp3" for M4A/AAC files

                // MediaStore file creation: We create the ContentValues now that the file size is known
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${sanitize(song.title)} - ${sanitize(song.artistName)}.$fileExtension")
                    put(MediaStore.MediaColumns.MIME_TYPE, mediaStoreMimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lost")

                    // ADDED: Include the calculated file size in MediaStore columns
                    put(MediaStore.MediaColumns.SIZE, fileSize)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1) // Mark as pending while writing
                    }
                    // Add all available metadata to MediaStore
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
                    // Use primary external volume for API 29+
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    // Use external content URI for older APIs
                    @Suppress("DEPRECATION")
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                // Insert the new file record into MediaStore
                val itemUri = resolver.insert(collection, values)
                    ?: throw Exception("Failed to insert MediaStore item for ${song.title}. MIME used: $mediaStoreMimeType")

                // Copy data from the temporary file (which now has tags) to the MediaStore URI
                resolver.openOutputStream(itemUri)?.use { mediaStoreOutput ->
                    FileInputStream(tempAudioFile).use { fileInput ->
                        fileInput.copyTo(mediaStoreOutput)
                    }
                } ?: throw Exception("Failed to open output stream for MediaStore item $itemUri")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // CRITICAL FIX: Reuse 'values' object to re-pass all metadata AND clear pending status.
                    // This forces MediaStore to update its database columns for the file, which is more reliable
                    // than relying on it to re-read the embedded tags instantly.
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                } else {
                    // ADDED: For older APIs (pre-Q), explicitly update to ensure metadata and size are registered.
                    resolver.update(itemUri, values, null, null)
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
        // Use the dedicated downloads channel, not the foreground channel
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Starting download: ${song.title}")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, 0, true) // Indeterminate progress initially
            .setOngoing(true)

        val cancelIntent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
        }
        // Use FLAG_IMMUTABLE as required for API 23+
        val pendingCancelIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Downloading: $title")
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)

        // Indeterminate or determinate progress bar
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
        }
        // Use FLAG_IMMUTABLE as required for API 23+
        val pendingPlayIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingPlayIntent = PendingIntent.getActivity(
            context,
            notificationId, // Use notificationId for a unique request code
            playIntent,
            pendingPlayIntentFlags
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
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

        // Create a retry intent that attempts to re-enqueue the download
        val retryIntent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_SONG, song)
            // Unique data URI prevents the system from reusing the PendingIntent
            data = "lost://retry/${song.ytID ?: song.id}".toUri()
        }

        // Use FLAG_IMMUTABLE as required for API 23+
        val pendingRetryIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingRetryIntent = PendingIntent.getService(
            context,
            notificationId, // Use notificationId for a unique request code
            retryIntent,
            pendingRetryIntentFlags
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
            .setContentTitle("Download failed: ${song.title}")
            .setContentText(errorMsg)
            .setSmallIcon(R.drawable.ic_error)
            .setContentIntent(pendingRetryIntent) // Tapping can retry the download
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0,0,false)
            .addAction(R.drawable.ic_refresh, "Retry", pendingRetryIntent)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Failed to post failure notification $notificationId")
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|.]"), "_")

    companion object {
        private const val TAG = "DownloadManager"
        // Renamed existing channel ID to be explicit about individual downloads
        const val CHANNEL_ID_DOWNLOADS = "downloads_channel"
        // New channel ID for the persistent foreground service notification
        const val CHANNEL_ID_FOREGROUND = "foreground_downloads_channel"

        /**
         * Creates a specific notification channel if it doesn't already exist and if POST_NOTIFICATIONS
         * permission is granted on API 33+.
         */
        fun createNotificationChannel(context: Context, channelId: String) {
            // Channel creation is API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Timber.tag(TAG).w("POST_NOTIFICATIONS permission not granted. Cannot create notification channel.")
                return
            }

            try {
                val (nameRes, descriptionRes, importance) = when (channelId) {
                    CHANNEL_ID_FOREGROUND -> Triple(
                        R.string.foreground_notification_channel_name,
                        R.string.foreground_notification_channel_description,
                        NotificationManager.IMPORTANCE_LOW // Low priority for foreground service
                    )
                    CHANNEL_ID_DOWNLOADS -> Triple(
                        R.string.download_notification_channel_name,
                        R.string.download_notification_channel_description,
                        NotificationManager.IMPORTANCE_DEFAULT // Default priority for progress updates
                    )
                    else -> return // Should not happen
                }

                val name = context.getString(nameRes)
                val descriptionText = context.getString(descriptionRes)

                val channel = NotificationChannel(channelId, name, importance).apply {
                    description = descriptionText
                }
                val notificationManager: NotificationManager? =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.createNotificationChannel(channel)
                Timber.tag(TAG).i("Notification channel '$channelId' created.")
            } catch (e: SecurityException) {
                Timber.tag(TAG).e(e, "SecurityException creating notification channel.")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to create notification channel '$channelId'")
            }
        }
    }
}
