package code.name.monkey.lost.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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
import java.util.Collections
import java.util.concurrent.TimeUnit
import code.name.monkey.lost.model.Song

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

data class YouTubeSearchItem(
    val videoId: String?,
    val title: String?,
    val author: String?, 
    val duration: String?, 
    val thumbnailUrl: String?
)

data class OfflineVideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val downloadTimestamp: Long,
    val filePath: String
)

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private lateinit var appContext: Context
    private val offlineVideos: MutableMap<String, OfflineVideoInfo> = Collections.synchronizedMap(mutableMapOf())
    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()
    
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_NO_AUTH,
        MOBILE,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
        WEB,
        WEB_CREATOR
    )

    fun giveContext(context:Context){
        appContext = context.applicationContext 
    }
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    suspend fun getPlaybackData(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality = AudioQuality.HIGH
    ): Result<PlaybackData> = withContext(Dispatchers.IO) {

        runCatching {
            Timber.tag(logTag).d("Fetching playback data for videoId: $videoId, playlistId: $playlistId")

            if (!::appContext.isInitialized) {
                throw IllegalStateException("AppContext not initialized in YTPlayerUtils. Call giveContext() first.")
            }

            val signatureTimestamp = getSignatureTimestampOrNull(videoId)
            val isLoggedIn = YouTube.cookie != null
            val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
            Timber.tag(logTag).d("Session auth: ${if (isLoggedIn) "Logged in ($sessionId)" else "Not logged in ($sessionId)"}")

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
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) 
            }
        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }
        return format
    }

    private fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder().head().url(url)
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val isSuccessful = response.isSuccessful
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            }
        return false
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).d("Signature timestamp obtained: $it") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to get signature timestamp") }
            .getOrNull()
    }

    private fun findUrlOrNull(format: PlayerResponse.StreamingData.Format, videoId: String): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onSuccess { Timber.tag(logTag).d("Stream URL obtained successfully") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to get stream URL") }
            .getOrNull()
    }

    fun extractYouTubeVideoId(youtubeUrl: String): String? {
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.)?(?:youtube\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\.be/)([^"&?/ ]{11})"""),
        )
        for (pattern in patterns) {
            val matcher = pattern.find(youtubeUrl)
            if (matcher != null && matcher.groupValues.size > 1) {
                return matcher.groupValues[1]
            }
        }
        return null
    }

    // @RequiresApi(Build.VERSION_CODES.O) // REMOVED - minSdk is 26+
    suspend fun initiateVideoDownload(
        song: Song,
        audioQuality: AudioQuality = AudioQuality.HIGH
    ) = withContext(Dispatchers.IO) {
        try {
            if (!::appContext.isInitialized) {
                Timber.tag(logTag).e("AppContext not initialized. Cannot start download.")
                // Consider returning a failure Result or throwing an exception here
                // return@withContext Result.failure(IllegalStateException("AppContext not initialized"))
            }

            val videoId = extractYouTubeVideoId(song.data)?.takeIf { it.isNotBlank() }
            if (videoId == null) {
                Timber.tag(logTag).e("Failed to extract valid videoId from song data: ${song.data}")
                // Consider returning a failure Result or throwing an exception here
                // return@withContext Result.failure(IllegalArgumentException("Invalid videoId"))
            }

            if (offlineVideos.containsKey(videoId)) {
                Timber.tag(logTag).i("Video $videoId is already in offlineVideos map.")
                // Ensure the return type matches what the caller expects if you return early
                 return@withContext // Assuming the function is intended to return Unit implicitly on success here
            }

            Timber.tag(logTag).d("Requesting download for videoId=$videoId, song=${song.title}")

            val intent = Intent(appContext, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START_DOWNLOAD
                putExtra(DownloadService.EXTRA_SONG, song)
            }
            appContext.startService(intent) // MODIFIED: Always use startService

        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Failed to initiate download for ${song.title}")
            // Consider returning a failure Result or re-throwing if the caller should handle it
            // return@withContext Result.failure(e)
        }
        // Ensure a Unit is returned if the function is expected to return Unit
        // If all paths are expected to lead to a Result, make sure they do.
        // Based on the original structure, it seems an implicit Unit return on success or after catch was intended.
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
        innertubeResult.map { } 
    }

    suspend fun getSimilarContent(videoId: String): Result<List<SongItem>> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.tag(logTag).d("Getting similar content for videoId: $videoId")
            val endpoint = WatchEndpoint(videoId = videoId, playlistId = null)
            val nextResult = YouTube.next(endpoint).getOrThrow()
            val similarItems: List<SongItem> = nextResult.items.map { it } 
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
        val videoId: String? 
        val title: String = ytItem.title 
        var author: String?
        var displayDurationOrType: String?
        val thumbnailUrl: String? = ytItem.thumbnail

        when (ytItem) {
            is SongItem -> {
                videoId = ytItem.id
                author = ytItem.artists.joinToString(", ") { it.name }
                displayDurationOrType = formatDuration(ytItem.duration)
            }
            is AlbumItem -> {
                videoId = null 
                author = ytItem.artists?.joinToString(", ") { it.name }
                displayDurationOrType = "Album" + (ytItem.year?.let { " ($it)" } ?: "")
            }
            is ArtistItem -> {
                videoId = null 
                author = ytItem.title 
                displayDurationOrType = "Artist"
            }
            is PlaylistItem -> {
                videoId = null 
                author = ytItem.author?.name
                displayDurationOrType = "Playlist" + (ytItem.songCountText?.let { " ($it)" } ?: "")
            }
        }
        if (title.isEmpty() && videoId == null) return null
        return YouTubeSearchItem(videoId, title, author, displayDurationOrType, thumbnailUrl)
    }

    suspend fun searchVideos(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG
    ): Result<List<YouTubeSearchItem>> = withContext(Dispatchers.IO) {
        Timber.tag(logTag).d("Searching videos for query: \"$query\" with filter: ${filter.value}")
        runCatching {
            val searchResult = YouTube.search(query, filter = filter).getOrThrow()
            Timber.tag(logTag).i("Successfully received search result for \"$query\". Found ${searchResult.items.size} items.")
            val mappedItems = searchResult.items.mapNotNull { mapYTItemToYouTubeSearchItem(it) }
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
