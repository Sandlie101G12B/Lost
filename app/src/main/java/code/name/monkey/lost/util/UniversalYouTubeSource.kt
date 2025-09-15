package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File // Added for potential future use with offline storage
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.Collections // Added for synchronized map

enum class AudioQuality {
    AUTO, HIGH, LOW
}

data class YouTubeSourceConfig(
    val userAgent: String,
    val cookie: String? = null,
    val proxy: Proxy? = null,
    val dataSyncId: String? = null,
    val visitorData: String? = null
)

// Data class for our app's search item representation
data class YouTubeSearchItem(
    val videoId: String?,
    val title: String?,
    val author: String?, // Combined artist/uploader name
    val duration: String?, // Formatted duration for songs/videos, or type for others (e.g., "Album")
    val thumbnailUrl: String?
)

// Data class for offline video information
data class OfflineVideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val downloadTimestamp: Long,
    val filePath: String // Path to the downloaded file
    // Potentially add: quality, format, expirationOfStream (if applicable to downloaded)
)

object UniversalYouTubeSource {
    private const val logTag = "UniversalYouTubeSource"
    private lateinit var httpClient: OkHttpClient
    private lateinit var appContext: Context
    private var isInitialized = false

    // In-memory store for offline video info. Replace with a database for a real app.
    private val offlineVideos: MutableMap<String, OfflineVideoInfo> = Collections.synchronizedMap(mutableMapOf())

    private val MAIN_CLIENT: YouTubeClient = YouTubeClient.WEB_REMIX
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.MOBILE,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.IOS,
        YouTubeClient.WEB,
        YouTubeClient.WEB_CREATOR
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    fun initialize(context: Context, config: YouTubeSourceConfig) {
        this.appContext = context.applicationContext
        YouTube.cookie = config.cookie
        YouTube.proxy = config.proxy
        YouTube.dataSyncId = config.dataSyncId
        YouTube.visitorData = config.visitorData
        this.httpClient = OkHttpClient.Builder().proxy(YouTube.proxy).build()
        isInitialized = true
        Timber.tag(logTag).i("UniversalYouTubeSource initialized.")
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("UniversalYouTubeSource has not been initialized. Call initialize() first.")
        }
    }

    suspend fun initiateVideoDownload(
        videoId: String,
        audioQuality: AudioQuality = AudioQuality.HIGH // Default to high for downloads
    ): Result<OfflineVideoInfo> = withContext(Dispatchers.IO) {
        checkInitialized()
        if (offlineVideos.containsKey(videoId)) {
            Timber.tag(logTag).i("Video $videoId is already downloaded.")
            return@withContext Result.success(offlineVideos[videoId]!!)
        }

        Timber.tag(logTag).d("Initiating download for videoId: $videoId")

        // 1. Get PlaybackData (contains stream URL and metadata)
        val playbackDataResult = getPlaybackData(videoId, audioQuality = audioQuality)

        playbackDataResult.fold(
            onSuccess = { data ->
                val videoDetails = data.videoDetails
                if (videoDetails == null) {
                    Timber.tag(logTag).e("Failed to get video details for $videoId during download initiation.")
                    return@withContext Result.failure(Exception("Missing video details for download initiation."))
                }

                // TODO: Implement actual file download logic here
                // For now, we simulate success and create an OfflineVideoInfo entry
                val simulatedFilePath = File(appContext.filesDir, "$videoId.mp4").absolutePath // Example path
                Timber.tag(logTag).d("Simulating download of $videoId to $simulatedFilePath with format: ${data.format.mimeType}")

                val offlineInfo = OfflineVideoInfo(
                    videoId = videoDetails.videoId,
                    title = videoDetails.title ?: "Unknown Title",
                    author = videoDetails.author ?: "Unknown Author",
                    durationSeconds = videoDetails.lengthSeconds?.toInt() ?: 0,
                    thumbnailUrl = videoDetails.thumbnail?.thumbnails?.lastOrNull()?.url,
                    downloadTimestamp = System.currentTimeMillis(),
                    filePath = simulatedFilePath // This will be the actual path once download is implemented
                )
                offlineVideos[videoId] = offlineInfo
                Timber.tag(logTag).i("Successfully initiated (simulated) download for $videoId. Stored info: $offlineInfo")
                Result.success(offlineInfo)
            },
            onFailure = { exception ->
                Timber.tag(logTag).e(exception, "Failed to get playback data for $videoId to initiate download.")
                Result.failure(exception)
            }
        )
    }

    suspend fun getPlaybackData(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality
    ): Result<PlaybackData> = withContext(Dispatchers.IO) {
        checkInitialized()
        runCatching {
            Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
            val signatureTimestamp = getSignatureTimestampOrNull(videoId)
            val isLoggedIn = YouTube.cookie != null
            val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
            Timber.tag(logTag).d("Session auth status: ${if (isLoggedIn) "Logged in ($sessionId)" else "Not logged in ($sessionId)"}")

            val mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp).getOrThrow()
            val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
            val videoDetails = mainPlayerResponse.videoDetails
            val playbackTracking = mainPlayerResponse.playbackTracking
            var format: PlayerResponse.StreamingData.Format? = null
            var streamUrl: String? = null
            var streamExpiresInSeconds: Int? = null
            var streamPlayerResponse: PlayerResponse? = null

            for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
                format = null; streamUrl = null; streamExpiresInSeconds = null
                val client = if (clientIndex == -1) {
                    streamPlayerResponse = mainPlayerResponse
                    MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }
                Timber.tag(logTag).d("Trying client ${client.clientName}")

                if (clientIndex != -1) { // Not MAIN_CLIENT, so fetch player response
                    if (client.loginRequired && !isLoggedIn) {
                        Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login")
                        continue
                    }
                    streamPlayerResponse = YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull()
                }

                if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                    val connManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    format = findFormat(streamPlayerResponse, audioQuality, connManager) ?: continue
                    streamUrl = findUrlOrNull(format, videoId) ?: continue
                    streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds ?: continue

                    if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || validateStatus(streamUrl)) {
                        Timber.tag(logTag).d("Stream validated or last fallback for ${client.clientName}")
                        break
                    }
                } else {
                    Timber.tag(logTag).d("Player status not OK for ${client.clientName}: ${streamPlayerResponse?.playabilityStatus?.status}")
                }
            }

            if (format == null || streamUrl == null || streamExpiresInSeconds == null) {
                throw Exception("Could not secure a valid stream.")
            }

            PlaybackData(audioConfig, videoDetails, playbackTracking, format, streamUrl, streamExpiresInSeconds)
        }
    }

    suspend fun getVideoMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> = withContext(Dispatchers.IO) {
        checkInitialized()
        Timber.tag(logTag).d("Fetching metadata for videoId: $videoId using MAIN_CLIENT")
        YouTube.player(videoId, playlistId, client = MAIN_CLIENT)
    }

    suspend fun addVideoToPlaylist(
        videoId: String,
        playlistId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        checkInitialized()
        if (YouTube.cookie == null) {
            Timber.tag(logTag).w("Cannot add to playlist: User not logged in.")
            return@withContext Result.failure(IllegalStateException("User not logged in."))
        }
        Timber.tag(logTag).d("Attempting to add video $videoId to playlist $playlistId")
        val innertubeResult = YouTube.addToPlaylist(playlistId, videoId)
        innertubeResult.map { } // Maps success(HttpResponse) to success(Unit), preserves failure
    }

    suspend fun getSimilarContent(videoId: String): Result<List<SongItem>> = withContext(Dispatchers.IO) {
        checkInitialized()
        runCatching {
            Timber.tag(logTag).d("Getting similar content for videoId: $videoId")

            val endpoint = WatchEndpoint(videoId = videoId, playlistId = null)
            val nextResult = YouTube.next(endpoint).getOrThrow()

            val similarItems: List<SongItem> = nextResult.items
                .map { it } // In case NextPage.fromPlaylistPanelVideoRenderer returned null

            Timber.tag(logTag).d("Found ${similarItems.size} similar items for videoId: $videoId")

            similarItems
        }.onFailure {
            Timber.tag(logTag).e(it, "Failed to get similar content for videoId: $videoId")
        }
    }

    private fun formatDuration(seconds: Int?): String? {
        return seconds?.let {
            val minutes = TimeUnit.SECONDS.toMinutes(it.toLong())
            val remainingSeconds = it - TimeUnit.MINUTES.toSeconds(minutes)
            String.format("%02d:%02d", minutes, remainingSeconds)
        }
    }

    private fun mapYTItemToYouTubeSearchItem(ytItem: YTItem): YouTubeSearchItem? {
        val videoId: String? // videoId for songs/videos, null for others if not applicable
        val title: String = ytItem.title // All YTItems have a title
        var author: String?
        var displayDurationOrType: String?
        val thumbnailUrl: String? = ytItem.thumbnail // All YTItems have a nullable thumbnail

        when (ytItem) {
            is SongItem -> {
                videoId = ytItem.id
                author = ytItem.artists.joinToString(", ") { it.name }
                displayDurationOrType = formatDuration(ytItem.duration)
            }
            is AlbumItem -> {
                videoId = null // Albums don't have a single videoId in this context
                author = ytItem.artists?.joinToString(", ") { it.name }
                displayDurationOrType = "Album" + (ytItem.year?.let { " ($it)" } ?: "")
            }
            is ArtistItem -> {
                videoId = null // Artists don't have a single videoId
                author = ytItem.title // Artist name is the primary title, so author can be null or also title
                displayDurationOrType = "Artist"
            }
            is PlaylistItem -> {
                videoId = null // Playlists don't have a single videoId
                author = ytItem.author?.name
                displayDurationOrType = "Playlist" + (ytItem.songCountText?.let { " ($it)" } ?: "")
            }
        }

        // If we couldn't even get a title (which should always be there for YTItem), then skip.
        if (title.isEmpty() && videoId == null) return null

        return YouTubeSearchItem(videoId, title, author, displayDurationOrType, thumbnailUrl)
    }

    suspend fun searchVideos(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG // Default to songs, can be parameterized
    ): Result<List<YouTubeSearchItem>> = withContext(Dispatchers.IO) {
        checkInitialized()
        Timber.tag(logTag).d("Searching videos for query: \"$query\" with filter: ${filter.value}")

        runCatching {
            val searchResult = YouTube.search(query, filter = filter).getOrThrow()

            Timber.tag(logTag).i("Successfully received search result for \"$query\". Found ${searchResult.items.size} items.")

            val mappedItems = searchResult.items.mapNotNull {
                mapYTItemToYouTubeSearchItem(it)
            }

            Timber.tag(logTag).i("Mapped ${mappedItems.size} search results for query: \"$query\"")
            mappedItems
        }.onFailure {
            Timber.tag(logTag).e(it, "Failed to search for videos with query: \"$query\"")
            Result.failure<List<YouTubeSearchItem>>(it)
        }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        return playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
            }.also {
                Timber.tag(logTag).d(if (it != null) "Selected format: ${it.mimeType}, bitrate: ${it.bitrate}" else "No suitable format")
            }
    }

    private fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL: $url")
        try {
            val request = Request.Builder().head().url(url).build()
            httpClient.newCall(request).execute().use { response -> // use ensure close
                Timber.tag(logTag).d("Validation for $url: ${response.code} ${if(response.isSuccessful) "OK" else "FAIL"}")
                return response.isSuccessful
            }
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Validation failed for $url")
            reportException(e)
        }
        return false
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure { reportException(it) }
            .getOrNull()
    }

    private fun findUrlOrNull(format: PlayerResponse.StreamingData.Format, videoId: String): String? {
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onFailure { reportException(it) }
            .getOrNull()
    }

    private fun reportException(t: Throwable) {
        Timber.tag(logTag).e(t, "Reported exception (UniversalYouTubeSource)")
    }
}
