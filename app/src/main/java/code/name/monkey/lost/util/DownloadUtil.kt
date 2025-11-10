package code.name.monkey.lost.util

// Import the service from its specified path in the user's request
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import code.name.monkey.lost.contants.AudioQualityKey
import code.name.monkey.lost.db.MusicDatabase
import code.name.monkey.lost.db.entities.FormatEntity
import code.name.monkey.lost.db.entities.SongEntity
import code.name.monkey.lost.service.ExoDownloadService
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.Executor

sealed class DownloadResult {
    abstract val videoId: String

    data class Success(override val videoId: String, val uri: Uri) : DownloadResult()
    data class Failure(override val videoId: String, val reason: String) : DownloadResult()
}

@UnstableApi
class DownloadUtil(
    context: Context,
    private val database: MusicDatabase,
    databaseProvider: DatabaseProvider,
    downloadCache: SimpleCache,
    private val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val songUrlCache = HashMap<String, Pair<String, Long>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Using enumPreference from the original code (stubbed above)
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val _downloadResult = MutableSharedFlow<DownloadResult>()
    val downloadResult: Flow<DownloadResult> = _downloadResult.asSharedFlow()

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        OkHttpClient.Builder()
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                Timber.tag("SpotifyPlaylist").d("Cache hit for $mediaId")
                return@Factory dataSpec
            }
            Timber.tag("SpotifyPlaylist").d("Cache miss for $mediaId")

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                Timber.tag("SpotifyPlaylist").d("Song URL cache hit for $mediaId")
                return@Factory dataSpec.withUri(it.first.toUri())
            }
            Timber.tag("SpotifyPlaylist").d("Song URL cache miss for $mediaId")

            val playbackData = runBlocking(scope.coroutineContext) {
                // Removed connectivityManager and audioQuality from getPlaybackData signature
                // to match the simpler user-provided signature.
                YTPlayerUtils.getPlaybackData(
                    mediaId
                )
            }.getOrThrow()
            val format = playbackData.format
            Timber.tag("SpotifyPlaylist").d("Got playback data for $mediaId")

            scope.launch {
                database.query {
                    val mimeTypeParts = format.mimeType.split(";")
                    val codecs = mimeTypeParts.find { it.trim().startsWith("codecs=") }
                        ?.substringAfter("=")
                        ?.removeSurrounding("\"") ?: ""
                    Timber.tag("SpotifyPlaylist").d("Upserting format and song info for $mediaId")
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = codecs,
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength!!,
                            loudnessDb = playbackData.audioConfig?.loudnessDb,
                            playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                        ),
                    )

                    val now = LocalDateTime.now()
                    val existing = getSongByIdBlocking(mediaId)?.song

                    val updatedSong: SongEntity = (if (existing != null) {
                        if (existing.dateDownload == null) {
                            existing.copy(dateDownload = now)
                        } else {
                            existing
                        }
                    } else {
                        SongEntity(
                            id = mediaId,
                            title = playbackData.videoDetails?.title ?: "Unknown",
                            duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                            thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                            dateDownload = now,
                            isDownloaded = false
                        )
                    }) as SongEntity

                    upsert(updatedSong)
                }
            }

            val streamUrl = playbackData.streamUrl.let {
                "${it}&range=0-${format.contentLength ?: 10000000}"
            }

            Timber.tag("SpotifyPlaylist").d("Got stream url for $mediaId")
            songUrlCache[mediaId] = streamUrl to (System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L))
            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        // Using the service import path provided in the user's source code
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        Timber.tag("SpotifyPlaylist").d("Download changed: ${download.request.id}, state: ${download.state}, exception: $finalException")
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    Timber.tag("SpotifyPlaylist").d("Download completed: ${download.request.id}")
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    _downloadResult.emit(
                                        DownloadResult.Success(
                                            videoId = download.request.id,
                                            uri = download.request.uri
                                        )
                                    )
                                }
                                Download.STATE_FAILED -> {
                                    Timber.tag("SpotifyPlaylist").e(finalException, "Download failed: ${download.request.id}")
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                    _downloadResult.emit(
                                        DownloadResult.Failure(
                                            videoId = download.request.id,
                                            reason = finalException?.message ?: "Unknown error"
                                        )
                                    )
                                }
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    Timber.tag("SpotifyPlaylist").d("Download stopped or removing: ${download.request.id}")
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                    Timber.tag("SpotifyPlaylist").d("Download state not handled: ${download.state}")
                                    // Other states are not handled
                                }
                            }
                        }
                    }
                }
            )
        }

    init {
        scope.launch {
            initializeDownloads()
        }
    }

    private fun initializeDownloads() {
        Timber.tag("SpotifyPlaylist").d("Initializing downloads")
        val result = mutableMapOf<String, Download>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
        }
        downloads.value = result
        Timber.tag("SpotifyPlaylist").d("Initialized ${result.size} downloads")
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }
}
