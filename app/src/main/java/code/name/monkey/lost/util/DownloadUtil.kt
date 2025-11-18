package code.name.monkey.lost.util

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
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Executor

sealed class DownloadResult {
    abstract val videoId: String

    data class Success(override val videoId: String, val uri: Uri) : DownloadResult()
    data class Failure(override val videoId: String, val reason: String) : DownloadResult()
}

/**
 * A small holder for playback data we cache in-memory.
 */
private data class PlaybackCacheEntry(
    val streamUrl: String,
    val format: FormatInfo,
    val expiresAtMs: Long
)

/**
 * Minimal format info extracted from the playback data to persist to DB later.
 * Adjust fields to match your YTPlayerUtils playbackData format.
 */
private data class FormatInfo(
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int?,
    val sampleRate: Int?,
    val contentLength: Long?
)

@UnstableApi
class DownloadUtil(
    context: Context,
    private val database: MusicDatabase,
    private val databaseProvider: DatabaseProvider,
    private val downloadCache: SimpleCache,
    private val playerCache: SimpleCache
) {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // thread-safe in-memory cache for playback URLs & metadata
    private val playbackCache = ConcurrentHashMap<String, PlaybackCacheEntry>()

    // Simple in-memory "songUrl" cache alternative (kept for compatibility)
    // but it delegates to playbackCache now.
    // private val songUrlCache = HashMap<String, Pair<String, Long>>()

    // Executor for DownloadManager - use a proper thread pool
    private val downloadExecutor: Executor = Executors.newFixedThreadPool(4)

    // Using enumPreference from the original code
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val _downloadResult = MutableSharedFlow<DownloadResult>()
    val downloadResult: Flow<DownloadResult> = _downloadResult.asSharedFlow()

    /**
     * DataSource factory used both by player and by DownloadManager (as upstream for downloads).
     * The resolving factory checks the in-memory playbackCache for an available stream URL.
     * If not present it will trigger an async fetch (non-blocking) and return the original DataSpec.
     * For best behaviour, pre-fetch playback data using [preparePlaybackData] before playback/download.
     */
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
            val mediaId = dataSpec.key ?: run {
                Timber.tag("SpotifyPlaylist").w("No media id in DataSpec")
                return@Factory dataSpec
            }

            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            try {
                // If this range is already cached in playerCache, let CacheDataSource handle it.
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    Timber.tag("SpotifyPlaylist").d("Cache hit for $mediaId at pos ${dataSpec.position}")
                    return@Factory dataSpec
                }

                // Check in-memory playbackCache for a prepared streamUrl that is still valid
                playbackCache[mediaId]?.let { entry ->
                    if (entry.expiresAtMs > System.currentTimeMillis()) {
                        Timber.tag("SpotifyPlaylist").d("Playback cache hit for $mediaId")
                        return@Factory dataSpec.withUri(entry.streamUrl.toUri())
                    } else {
                        // expired -> remove
                        playbackCache.remove(mediaId)
                    }
                }

                // No cached playback URL available. Trigger asynchronous fetch (non-blocking)
                // so future resolution attempts will find the URL.
                scope.launch {
                    // only fetch once concurrently for a given mediaId
                    fetchAndCachePlaybackData(mediaId)
                }

                Timber.tag("SpotifyPlaylist").d("Playback cache miss for $mediaId - triggered async fetch")
            } catch (e: Exception) {
                Timber.tag("SpotifyPlaylist").w(e, "Exception in resolver for $mediaId")
                // Fall through: return original dataSpec (will try upstream)
            }

            dataSpec
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            downloadExecutor
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
                        // Update the downloads map
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
                                    // 2. CRUCIAL: Mark as inLibrary so it appears in the main songs list
                                    database.inLibrary(download.request.id, LocalDateTime.now())
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
                                    // Other states not handled explicitly
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

    /**
     * Public API: call this before starting playback or requesting a download to ensure the
     * playback URL and metadata are ready. This avoids potential first-request fallbacks.
     */
    fun preparePlaybackData(mediaId: String) {
        scope.launch {
            fetchAndCachePlaybackData(mediaId)
        }
    }

    /**
     * Public API: prepare many ids (useful before batch downloads)
     */
    fun preparePlaybackDataForDownloads(ids: Collection<String>) {
        scope.launch {
            ids.forEach { id ->
                fetchAndCachePlaybackData(id)
            }
        }
    }

    /**
     * Attempt to fetch playback data from YTPlayerUtils and cache it. This performs
     * DB upserts for metadata but does it off the resolver thread.
     */
    private suspend fun fetchAndCachePlaybackData(mediaId: String) {
        // If another coroutine already fetched it successfully, skip
        val existing = playbackCache[mediaId]
        if (existing != null && existing.expiresAtMs > System.currentTimeMillis()) return

        try {
            Timber.tag("SpotifyPlaylist").d("Fetching playback data for $mediaId")
            // getPlaybackData is a suspend function in this variant; adapt if yours is not.
            val playbackDataResult = YTPlayerUtils.getPlaybackData(mediaId) // suspend
            val playbackData = playbackDataResult.getOrThrow()
            val format = playbackData.format

            val codecs = format.mimeType.split(";").find { it.trim().startsWith("codecs=") }
                ?.substringAfter("=")
                ?.removeSurrounding("\"") ?: ""

            val contentLength = format.contentLength
            val expiresAt = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)

            val formatInfo = FormatInfo(
                itag = format.itag,
                mimeType = format.mimeType.split(";")[0],
                codecs = codecs,
                bitrate = format.bitrate,
                sampleRate = format.audioSampleRate,
                contentLength = contentLength
            )

            val streamUrl = playbackData.streamUrl // do NOT append range manually; let HTTP range headers handle requests

            // cache in-memory for quick access by resolver
            playbackCache[mediaId] = PlaybackCacheEntry(
                streamUrl = streamUrl,
                format = formatInfo,
                expiresAtMs = expiresAt
            )

            // persist metadata to DB asynchronously (not on resolver thread)
            Timber.tag("SpotifyPlaylist").d("Upserting format and song info for $mediaId into DB")
            database.query {
                val now = LocalDateTime.now()

                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = formatInfo.itag,
                        mimeType = formatInfo.mimeType,
                        codecs = formatInfo.codecs,
                        bitrate = formatInfo.bitrate ?: 0,
                        sampleRate = formatInfo.sampleRate,
                        contentLength = formatInfo.contentLength ?: 0L,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )

                val existingSong = getSongByIdBlocking(mediaId)?.song
                val updatedSong: SongEntity = if (existingSong != null) {
                    if (existingSong.dateDownload == null) {
                        existingSong.copy(dateDownload = now)
                    } else existingSong
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                        dateDownload = now,
                        isDownloaded = false
                    )
                }

                upsert(updatedSong)
            }

        } catch (e: Exception) {
            Timber.tag("SpotifyPlaylist").e(e, "Failed to fetch playback data for $mediaId")
            // don't throw — resolver will fall back to upstream
        }
    }

    /**
     * Convenience to get the currently cached stream URI (if available and not expired)
     */
    fun getCachedStreamUri(mediaId: String): Uri? {
        val entry = playbackCache[mediaId]
        return if (entry != null && entry.expiresAtMs > System.currentTimeMillis()) {
            entry.streamUrl.toUri()
        } else {
            playbackCache.remove(mediaId)
            null
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }
}
