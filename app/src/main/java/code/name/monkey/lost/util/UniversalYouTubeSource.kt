package code.name.monkey.lost.util

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File // Added for potential future use with offline storage
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class AudioQuality {
    AUTO,
    HIGH,
    LOW,
}

data class YouTubeSourceConfig(
    val userAgent: String,
    val cookie: String? = null,
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

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private lateinit var appContext: Context
    private val offlineVideos: MutableMap<String, OfflineVideoInfo> = Collections.synchronizedMap(mutableMapOf())
    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()
    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * [com.metrolist.innertube.models.YouTubeClient.WEB_REMIX] should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_NO_AUTH,
        MOBILE,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
        WEB,
        WEB_CREATOR
    )

    fun giveContext(context:Context){
        appContext = context
    }
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun getPlaybackData(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality
    ): Result<PlaybackData> = withContext(Dispatchers.IO) {

        runCatching {
            Timber.tag(logTag).d("Fetching playback data for videoId: $videoId, playlistId: $playlistId")

            val signatureTimestamp = getSignatureTimestampOrNull(videoId)
            val isLoggedIn = YouTube.cookie != null
            val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
            Timber.tag(logTag).d("Session auth: ${if (isLoggedIn) "Logged in ($sessionId)" else "Not logged in ($sessionId)"}")

            // Always start with MAIN_CLIENT
            val mainPlayerResponse =
                YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp).getOrThrow()
            val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
            val videoDetails = mainPlayerResponse.videoDetails
            val playbackTracking = mainPlayerResponse.playbackTracking

            var format: PlayerResponse.StreamingData.Format? = null
            var streamUrl: String? = null
            var streamExpiresInSeconds: Int? = null
            var streamPlayerResponse: PlayerResponse? = null

            val connManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
                // Reset per client
                format = null; streamUrl = null; streamExpiresInSeconds = null

                val client: YouTubeClient
                if (clientIndex == -1) {
                    client = MAIN_CLIENT
                    streamPlayerResponse = mainPlayerResponse
                    Timber.tag(logTag).d("Trying MAIN_CLIENT: ${client.clientName}")
                } else {
                    client = STREAM_FALLBACK_CLIENTS[clientIndex]
                    Timber.tag(logTag).d("Trying fallback client ${client.clientName}")

                    if (client.loginRequired && !isLoggedIn) {
                        Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login")
                        continue
                    }

                    streamPlayerResponse =
                        YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull()
                }

                if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                    format = findFormat(streamPlayerResponse, audioQuality, connManager)
                    if (format == null) {
                        Timber.tag(logTag).d("No suitable format for ${client.clientName}")
                        continue
                    }

                    streamUrl = findUrlOrNull(format, videoId)
                    if (streamUrl == null) {
                        Timber.tag(logTag).d("No URL for format from ${client.clientName}")
                        continue
                    }

                    streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                    if (streamExpiresInSeconds == null) {
                        Timber.tag(logTag).d("No expiration found for ${client.clientName}")
                        continue
                    }

                    if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                        Timber.tag(logTag).d("Using last fallback client without validation: ${client.clientName}")
                        break
                    }

                    if (validateStatus(streamUrl)) {
                        Timber.tag(logTag).d("Stream validated for client ${client.clientName}")
                        break
                    } else {
                        Timber.tag(logTag).d("Validation failed for client ${client.clientName}, trying next fallback")
                    }
                } else {
                    Timber.tag(logTag).d(
                        "Playability not OK for ${client.clientName}: ${streamPlayerResponse?.playabilityStatus?.status}, reason=${streamPlayerResponse?.playabilityStatus?.reason}"
                    )
                }
            }

            if (format == null) throw Exception("Could not find a suitable audio format.")
            if (streamUrl == null) throw Exception("Could not resolve a stream URL.")
            if (streamExpiresInSeconds == null) throw Exception("Missing stream expiration time.")

            Timber.tag(logTag).d("Successfully obtained playback data: ${format.mimeType}, bitrate=${format.bitrate}")
            PlaybackData(audioConfig, videoDetails, playbackTracking, format, streamUrl, streamExpiresInSeconds)
        }
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = MAIN_CLIENT)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val isSuccessful = response.isSuccessful
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            }
        return false
    }
    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).d("Signature timestamp obtained: $it") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get signature timestamp")
             }
            .getOrNull()
    }
    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onSuccess { Timber.tag(logTag).d("Stream URL obtained successfully") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get stream URL")
                }
            .getOrNull()
    }

    //--------------------------------------------------------------------------------------------------------------------------------

    suspend fun initiateVideoDownload(
        videoId: String,
        audioQuality: AudioQuality = AudioQuality.HIGH // Default to high for downloads
    ): Result<OfflineVideoInfo> = withContext(Dispatchers.IO) {

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

    suspend fun getVideoMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> = withContext(Dispatchers.IO) {

        Timber.tag(logTag).d("Fetching metadata for videoId: $videoId using MAIN_CLIENT")
        YouTube.player(videoId, playlistId, client = MAIN_CLIENT)
    }

    suspend fun addVideoToPlaylist(
        videoId: String,
        playlistId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {

        if (YouTube.cookie == null) {
            Timber.tag(logTag).w("Cannot add to playlist: User not logged in.")
            return@withContext Result.failure(IllegalStateException("User not logged in."))
        }
        Timber.tag(logTag).d("Attempting to add video $videoId to playlist $playlistId")
        val innertubeResult = YouTube.addToPlaylist(playlistId, videoId)
        innertubeResult.map { } // Maps success(HttpResponse) to success(Unit), preserves failure
    }

    suspend fun getSimilarContent(videoId: String): Result<List<SongItem>> = withContext(Dispatchers.IO) {

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


    private fun reportException(t: Throwable) {
        Timber.tag(logTag).e(t, "Reported exception (UniversalYouTubeSource)")
    }
}
