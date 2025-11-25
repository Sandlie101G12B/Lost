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
import androidx.media3.exoplayer.offline.DownloadRequest
import code.name.monkey.lost.contants.AudioQualityKey
import code.name.monkey.lost.db.LostDatabase
import code.name.monkey.lost.db.FormatEntity
import code.name.monkey.lost.db.DownloadedSongsEntity
import code.name.monkey.lost.db.SongEntity
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.service.ExoDownloadService
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.nio.ByteBuffer

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

// Helper data class to queue requests before metadata is even fetched
private data class PendingDownloadRequest(
    val videoId: String,
    val uri: Uri,
    val playlistId: Long?
)

@UnstableApi
class DownloadUtil(
    context: Context,
    private val database: LostDatabase,
    private val databaseProvider: DatabaseProvider,
    private val downloadCache: SimpleCache,
    private val playerCache: SimpleCache
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // thread-safe in-memory cache for playback URLs & metadata
    private val playbackCache = ConcurrentHashMap<String, PlaybackCacheEntry>()

    // Executor for DownloadManager - use a proper thread pool
    private val downloadExecutor: Executor = Executors.newFixedThreadPool(4)

    // Using enumPreference from the original code (ensure PreferenceUtil or similar exists)
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val _downloadResult = MutableSharedFlow<DownloadResult>()
    val downloadResult: Flow<DownloadResult> = _downloadResult.asSharedFlow()

    // --- Custom Queueing Logic Variables ---
    private val pendingQueue = ArrayDeque<PendingDownloadRequest>()
    private val activeBatchIds = ConcurrentHashMap.newKeySet<String>()
    private val queueLock = Any()

    // We track how many pairs (batches) have been downloaded in the current "burst"
    private var pairsDownloadedInBurst = 0
    private var isCoolingDown = false
    private var isRateLimited = false // New flag for 403 errors

    // Constants for the throttling logic
    private val BATCH_SIZE = 3
    private val PAIRS_BEFORE_COOLDOWN = 2
    private val COOLDOWN_MS = 15000L
    private val RATE_LIMIT_BACKOFF_MS = 60000L // 1 minute wait if Google blocks us

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
                val playbackData = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    // We call your existing logic, but we need the return value here.
                    // You might need to refactor fetchAndCachePlaybackData to return the PlaybackCacheEntry
                    // or call YTPlayerUtils directly here like the original code did.

                    // Simplest fix without refactoring your whole class:
                    fetchAndCachePlaybackData(mediaId) // This populates playbackCache
                    playbackCache[mediaId] // Return the entry we just cached
                }

                playbackData?.let {
                    return@Factory dataSpec.withUri(it.streamUrl.toUri())
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
            // Ensure ExoPlayer doesn't try to run more than our batch size,
            // though we throttle the inputs anyway.
            maxParallelDownloads = 2
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

                        // Check if an active batch item finished
                        if (download.state == Download.STATE_COMPLETED || download.state == Download.STATE_FAILED) {
                            val wasActive = activeBatchIds.remove(download.request.id)
                            if (wasActive) {
                                checkBatchCompletion()
                            }
                        }

                        scope.launch {
                            val song = database.songsDao().getSongById(download.request.id)
                            Timber.tag("SpotifyPlaylist").d("Download request id: ${download.request.id}")
                            Timber.tag("SpotifyPlaylist").d("-- Song: $song")
                            if (song != null) {
                                when (download.state) {
                                    Download.STATE_COMPLETED -> {
                                        Timber.tag("SpotifyPlaylist").d("Download completed: ${download.request.id}")
                                        val updatedSong = song.copy(isDownloaded = true, dateDownload = LocalDateTime.now(), inLibrary = LocalDateTime.now())
                                        database.songsDao().updateSong(updatedSong)
                                        _downloadResult.emit(
                                            DownloadResult.Success(
                                                videoId = download.request.id,
                                                uri = download.request.uri
                                            )
                                        )

                                        if (download.request.data.isNotEmpty()) {
                                            try {
                                                val buffer = ByteBuffer.wrap(download.request.data)
                                                val playlistId = buffer.long

                                                val songEntity = SongEntity(
                                                    playlistCreatorId = playlistId,
                                                    id = updatedSong.id.hashCode().toLong(),
                                                    title = updatedSong.title,
                                                    trackNumber = updatedSong.trackNumber,
                                                    year = updatedSong.year,
                                                    duration = updatedSong.duration,
                                                    data = updatedSong.data,
                                                    dateModified = updatedSong.dateModified,
                                                    albumId = updatedSong.albumId,
                                                    albumName = updatedSong.albumName,
                                                    artistId = updatedSong.artistId,
                                                    artistName = updatedSong.artistName,
                                                    composer = updatedSong.composer,
                                                    albumArtist = updatedSong.albumArtist
                                                )
                                                database.playlistDao().insertSongsToPlaylist(listOf(songEntity))
                                                Timber.tag("SpotifyPlaylist").d("Added ${updatedSong.title} to playlist $playlistId")
                                            } catch (e: Exception) {
                                                Timber.tag("SpotifyPlaylist").e(e, "Failed to add to playlist from download data")
                                            }
                                        }
                                    }
                                    Download.STATE_FAILED -> {
                                        Timber.tag("SpotifyPlaylist").e(finalException, "Download failed: ${download.request.id}")
                                        database.songsDao().updateSong(song.copy(isDownloaded = false, dateDownload = null))
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
                                        database.songsDao().updateSong(song.copy(isDownloaded = false, dateDownload = null))
                                    }
                                    else -> {
                                        // Other states not handled explicitly
                                    }
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
                // If the app restarted and we have downloads running/queued in ExoPlayer,
                // we treat them as "active" so we don't start new ones immediately.
                if(cursor.download.state != Download.STATE_COMPLETED && cursor.download.state != Download.STATE_FAILED) {
                    activeBatchIds.add(cursor.download.request.id)
                }
            }
        }
        downloads.value = result
        Timber.tag("SpotifyPlaylist").d("Initialized ${result.size} downloads")
    }

    /**
     * Internal queue manager to implement "2 pairs then wait 15s" logic.
     */
    private fun checkBatchCompletion() {
        synchronized(queueLock) {
            if (activeBatchIds.isEmpty()) {
                // The current batch (pair) is finished.
                pairsDownloadedInBurst++

                // If we are in rate limit mode, do not proceed automatically. The cooldown timer will restart the queue.
                if (isRateLimited) {
                    Timber.tag("SpotifyPlaylist").d("Batch finished but rate limit is active. Waiting for cooldown.")
                    return
                }

                if (pairsDownloadedInBurst >= PAIRS_BEFORE_COOLDOWN) {
                    // We finished 2 pairs (4 songs). Trigger cooldown.
                    if (!isCoolingDown) {
                        isCoolingDown = true
                        Timber.tag("SpotifyPlaylist").d("Burst limit reached ($PAIRS_BEFORE_COOLDOWN pairs). Cooling down for ${COOLDOWN_MS}ms.")
                        scope.launch {
                            delay(COOLDOWN_MS)
                            synchronized(queueLock) {
                                isCoolingDown = false
                                pairsDownloadedInBurst = 0 // Reset burst counter
                            }
                            processQueue()
                        }
                    }
                } else {
                    // Finished a pair, but haven't reached burst limit yet. Process next pair immediately.
                    processQueue()
                }
            }
        }
    }

    private fun processQueue() {
        synchronized(queueLock) {
            if (activeBatchIds.isNotEmpty()) return // Still processing a batch
            if (isCoolingDown || isRateLimited) return // Waiting for cooldown or error backoff
            if (pendingQueue.isEmpty()) return // Nothing to do

            Timber.tag("SpotifyPlaylist").d("Processing next batch. Queue size: ${pendingQueue.size}")

            // Take up to BATCH_SIZE (2) items
            val batch = mutableListOf<PendingDownloadRequest>()
            while (batch.size < BATCH_SIZE && pendingQueue.isNotEmpty()) {
                batch.add(pendingQueue.removeFirst())
            }

            // Mark them as active immediately so subsequent calls don't pick up more
            batch.forEach { activeBatchIds.add(it.videoId) }

            scope.launch {
                // Process sequentially with a small delay to be gentle on the API
                for ((index, request) in batch.withIndex()) {
                    if (isRateLimited) {
                        // If rate limit tripped during this batch loop, stop immediately.
                        // activeBatchIds and pendingQueue handling is done in handleRateLimit
                        break
                    }

                    if (index > 0) {
                        delay(3000) // Wait 3 seconds between items in the same pair
                    }

                    try {
                        // We do metadata fetch HERE. propagateRateLimit = true means it will throw on 403
                        Timber.tag("SpotifyPlaylist").d("Fetching metadata for ${request.videoId}")
                        fetchAndCachePlaybackData(request.videoId, propagateRateLimit = true)

                        val requestBuilder = DownloadRequest.Builder(request.videoId, request.uri)
                            .setCustomCacheKey(request.videoId)

                        if (request.playlistId != null) {
                            val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
                            buffer.putLong(request.playlistId)
                            requestBuilder.setData(buffer.array())
                        }

                        Timber.tag("SpotifyPlaylist").d("Adding ${request.videoId} to DownloadManager")
                        downloadManager.addDownload(requestBuilder.build())
                    } catch (e: Exception) {
                        if (isRateLimitError(e)) {
                            Timber.tag("SpotifyPlaylist").e("Rate limit (403) detected for ${request.videoId}. Triggering backoff.")
                            handleRateLimit(request, batch.subList(index + 1, batch.size))
                            break // Stop processing this batch
                        } else {
                            // Standard error (e.g. network timeout, video unavailable)
                            Timber.tag("SpotifyPlaylist").e(e, "Metadata fetch failed for ${request.videoId}, skipping download add")
                            // Remove from active IDs since we won't get a download completion event for it
                            activeBatchIds.remove(request.videoId)
                            // If this failure emptied the active batch, we need to ensure the queue keeps moving
                            checkBatchCompletion()
                        }
                    }
                }
            }
        }
    }

    private fun isRateLimitError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        // Check for 403 or the specific Google/YouTube error text
        return msg.contains("403") ||
                msg.contains("automated queries") ||
                msg.contains("ClientRequestException")
    }

    private fun handleRateLimit(failedRequest: PendingDownloadRequest, remainingInBatch: List<PendingDownloadRequest>) {
        synchronized(queueLock) {
            isRateLimited = true

            // Remove the failed one and remaining ones from active set so checkBatchCompletion doesn't get confused
            activeBatchIds.remove(failedRequest.videoId)
            remainingInBatch.forEach { activeBatchIds.remove(it.videoId) }

            // Re-queue remaining items at the FRONT (preserve order: failed first, then rest)
            // Reverse iteration to push them back in correct order
            for (i in remainingInBatch.indices.reversed()) {
                pendingQueue.addFirst(remainingInBatch[i])
            }
            // Add the failed one back to the very front
            pendingQueue.addFirst(failedRequest)
        }

        // Launch long cooldown
        scope.launch {
            Timber.tag("SpotifyPlaylist").w("Rate limit active. Pausing queue for ${RATE_LIMIT_BACKOFF_MS}ms")
            delay(RATE_LIMIT_BACKOFF_MS)

            synchronized(queueLock) {
                isRateLimited = false
                pairsDownloadedInBurst = 0 // Reset burst counter to be safe
            }

            Timber.tag("SpotifyPlaylist").d("Rate limit cooldown finished. Resuming queue.")
            processQueue()
        }
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

    suspend fun ensureSongMetadata(mediaId: String) {
        fetchAndCachePlaybackData(mediaId)
    }

    /**
     * Attempt to fetch playback data from YTPlayerUtils and cache it.
     * @param propagateRateLimit if true, rethrows 403 errors so the caller can handle backoff.
     */
    private suspend fun fetchAndCachePlaybackData(mediaId: String, propagateRateLimit: Boolean = false) {
        Timber.tag("SpotifyPlaylist").d("fetchAndCachePlaybackData called for $mediaId")
        // If another coroutine already fetched it successfully, skip
        val existing = playbackCache[mediaId]
        if (existing != null && existing.expiresAtMs > System.currentTimeMillis()) {
            Timber.tag("SpotifyPlaylist").d("Skipping fetch for $mediaId, found valid in-memory cache")
            return
        }

        try {
            Timber.tag("SpotifyPlaylist").d("Fetching playback data for $mediaId from YTPlayerUtils")
            // getPlaybackData is a suspend function in this variant; adapt if yours is not.
            val playbackDataResult = YTPlayerUtils.getPlaybackData(mediaId) // suspend

            if (playbackDataResult.isFailure) {
                val ex = playbackDataResult.exceptionOrNull()
                Timber.tag("SpotifyPlaylist").e("YTPlayerUtils.getPlaybackData failed for $mediaId: $ex")
                // If the result is failure and it's a rate limit, we might need to throw if required
                if (ex != null && propagateRateLimit && isRateLimitError(ex)) {
                    throw ex
                }
            }

            val playbackData = playbackDataResult.getOrThrow()
            Timber.tag("SpotifyPlaylist").d("Successfully fetched playback data for $mediaId. Video Title: ${playbackData.videoDetails?.title}")

            val format = playbackData.format
            Timber.tag("SpotifyPlaylist").d("Format info: itag=${format.itag}, mimeType=${format.mimeType}, bitrate=${format.bitrate}")

            val codecs = format.mimeType.split(";").find { it.trim().startsWith("codecs=") }
                ?.substringAfter("=")
                ?.removeSurrounding("\"") ?: ""

            val contentLength = format.contentLength
            val expiresAt = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
            Timber.tag("SpotifyPlaylist").d("Stream expires in ${playbackData.streamExpiresInSeconds} seconds. ExpiresAt timestamp: $expiresAt")

            val formatInfo = FormatInfo(
                itag = format.itag,
                mimeType = format.mimeType.split(";")[0],
                codecs = codecs,
                bitrate = format.bitrate,
                sampleRate = format.audioSampleRate,
                contentLength = contentLength
            )

            val streamUrl = playbackData.streamUrl.let {
                "${it}&range=0-${format.contentLength ?: 10000000}"
            }
            Timber.tag("SpotifyPlaylist").d("Stream URL obtained (length: ${streamUrl.length})")

            // cache in-memory for quick access by resolver
            playbackCache[mediaId] = PlaybackCacheEntry(
                streamUrl = streamUrl,
                format = formatInfo,
                expiresAtMs = expiresAt
            )
            Timber.tag("SpotifyPlaylist").d("Added $mediaId to playbackCache")

            Timber.tag("SpotifyPlaylist").d("Upserting format and song info for $mediaId into DB")
            val now = LocalDateTime.now()

            val formatEntity = FormatEntity(
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
            Timber.tag("SpotifyPlaylist").d("Inserting FormatEntity: $formatEntity")
            database.songsDao().insertFormat(formatEntity)

            val existingSong = database.songsDao().getSongById(mediaId)
            Timber.tag("SpotifyPlaylist").d("Checking existing song in DB for $mediaId: ${existingSong != null}")

            val updatedSong: DownloadedSongsEntity = if (existingSong != null) {
                if (existingSong.dateDownload == null) {
                    Timber.tag("SpotifyPlaylist").d("Existing song has no download date, updating with now")
                    existingSong.copy(dateDownload = now)
                } else {
                    Timber.tag("SpotifyPlaylist").d("Existing song already has download date, keeping it")
                    existingSong
                }
            } else {
                Timber.tag("SpotifyPlaylist").d("Creating new DownloadedSongsEntity for $mediaId")
                DownloadedSongsEntity(
                    id = mediaId, // Assuming mediaId can be converted to a Long hash
                    title = playbackData.videoDetails?.title ?: "Unknown",
                    trackNumber = 0,
                    year = 0,
                    duration = playbackData.videoDetails?.lengthSeconds?.toLongOrNull() ?: 0L,
                    data = "/",
                    dateModified = 0,
                    albumId = 0,
                    albumName = "",
                    artistId = 0,
                    artistName = playbackData.videoDetails?.author ?: "Unknown",
                    composer = null,
                    albumArtist = null,
                    dateDownload = now,
                    isDownloaded = false,
                    thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                    inLibrary = null
                )
            }

            Timber.tag("SpotifyPlaylist").d("Inserting/Updating SongEntity: $updatedSong")
            database.songsDao().insertSong(updatedSong)
            Timber.tag("SpotifyPlaylist").d("DB upsert complete for $mediaId")


        } catch (e: Exception) {
            // Check if we should rethrow for queue handling
            if (propagateRateLimit && isRateLimitError(e)) {
                throw e
            }

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

    fun addDownload(videoId: String, uri: Uri, playlistId: Long? = null) {
        // We now just add to our internal queue and trigger the processor.
        // This prevents 50 concurrent metadata fetches which would overload the server.
        synchronized(queueLock) {
            pendingQueue.add(PendingDownloadRequest(videoId, uri, playlistId))
            processQueue()
        }
    }

    fun addLocalSongToPlaylist(song: Song, playlistId: Long) {
        scope.launch {
            val songEntity = SongEntity(
                playlistCreatorId = playlistId,
                id = song.id,
                title = song.title,
                trackNumber = song.trackNumber,
                year = song.year,
                duration = song.duration,
                data = song.data,
                dateModified = song.dateModified,
                albumId = song.albumId,
                albumName = song.albumName,
                artistId = song.artistId,
                artistName = song.artistName,
                composer = song.composer,
                albumArtist = song.albumArtist
            )
            database.playlistDao().insertSongsToPlaylist(listOf(songEntity))
        }
    }
}