package code.name.monkey.lost.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import code.name.monkey.lost.R
import code.name.monkey.lost.extensions.showToast
import code.name.monkey.lost.extensions.uri
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.service.playback.Playback.PlaybackCallbacks
import code.name.monkey.lost.util.PreferenceUtil.playbackPitch
import code.name.monkey.lost.util.PreferenceUtil.playbackSpeed
import code.name.monkey.lost.util.YTPlayerUtils
import code.name.monkey.lost.util.AudioQuality
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import timber.log.Timber

class LostExoPlayer @OptIn(UnstableApi::class) constructor
    (context: Context) : AudioManagerPlayback(context), Player.Listener, KoinComponent {
    
    private var player: ExoPlayer
    override var callbacks: PlaybackCallbacks? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadCache: SimpleCache by inject(named("downloadCache"))

    override var isInitialized = false
        private set

    init {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTubeClient.USER_AGENT_WEB)

        // Configure CacheDataSource to use the download cache
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mainDataSourceFactory = DefaultDataSource.Factory(context, cacheDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(mainDataSourceFactory)

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(this)
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

    private fun isUrlExpired(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val expire = uri.getQueryParameter("expire")?.toLongOrNull()
            if (expire != null) {
                // If expiration time is in the past (or very soon), consider it expired.
                // expire is in seconds.
                // Buffer of 5 minutes just to be safe
                return System.currentTimeMillis() / 1000 > expire - 300
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        isInitialized = false
        coroutineScope.launch {
            var currentStreamUrl = song.streamUrl
            
            // Check expiration of the existing URL
            if (!currentStreamUrl.isNullOrEmpty() && isUrlExpired(currentStreamUrl!!)) {
                Timber.tag(TAG).d("Stream URL expired, clearing to force refresh: $currentStreamUrl")
                currentStreamUrl = null
                song.streamUrl = null
            }

            if(!currentStreamUrl.isNullOrEmpty()){
                // Use existing stream URL, likely from DB or previous fetch
                // Try to set cache key if we have an ID
                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(currentStreamUrl)

                val cacheKey = song.ytID ?: extractYouTubeVideoId(song.data)
                if (!cacheKey.isNullOrEmpty()) {
                    Timber.tag(TAG).d("---- ==== Setting custom cache key: $cacheKey ==== ----")
                    mediaItemBuilder.setCustomCacheKey(cacheKey)
                }
                
                preparePlayer(mediaItemBuilder.build(), completion)
            } else if (song.isYTSong || song.data.startsWith("https") || !song.ytID.isNullOrEmpty()) {

                val determinedSongId: String? =
                    if (song.ytID.isNullOrEmpty()) {
                        extractYouTubeVideoId(song.data)
                    } else {
                        song.ytID
                    }

                if (determinedSongId.isNullOrBlank()) {
                    Timber.tag(TAG)
                        .e("Could not determine a valid YouTube song ID for song: ${song.title}, data: ${song.data}, ytID: ${song.ytID}")
                    withContext(Dispatchers.Main) {
                        context.showToast(context.getString(R.string.unable_to_play_song_no_id))
                        completion(false)
                    }
                    return@launch
                }
                
                Timber.tag(TAG).d("Fetching stream URL for YouTube song ID: $determinedSongId")
                
                val playbackDataResult = YTPlayerUtils.getPlaybackData(
                    videoId = determinedSongId,
                    audioQuality = AudioQuality.AUTO
                )

                withContext(Dispatchers.Main) {
                    playbackDataResult.fold(
                        onSuccess = { playbackData ->
                            song.streamUrl = playbackData.streamUrl
                            Timber.tag(TAG)
                                .d("Successfully fetched stream URL: ${playbackData.streamUrl}")
                            
                            // Set custom cache key to match DownloadManager's key (videoId)
                            val mediaItem = MediaItem.Builder()
                                .setUri(playbackData.streamUrl)
                                .setCustomCacheKey(determinedSongId)
                                .build()
                                
                            preparePlayer(mediaItem, completion)
                        },
                        onFailure = {
                            Timber.tag(TAG).e(it, "Failed to get stream URL for $determinedSongId")
                            context.showToast(context.getString(R.string.unable_to_stream_youtube_song))
                            completion(false)
                        }
                    )
                }

            } else {
                Timber.tag(TAG).d("Setting data source for local song: ${song.data}")
                val mediaItem = MediaItem.fromUri(song.uri)
                preparePlayer(mediaItem, completion)
            }
        }
    }

    private fun preparePlayer(mediaItem: MediaItem, completion: (success: Boolean) -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post {
                player.setMediaItem(mediaItem)
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false
                )
                player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)

                val readyListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            player.removeListener(this)
                            isInitialized = true
                            Timber.tag(TAG).d("Player is ready.")
                            completion(true)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        player.removeListener(this)
                        Timber.tag(TAG).e(error, "Player error during specific preparation: ")
                        completion(false)
                    }
                }
                player.addListener(readyListener)
                player.prepare()
                Timber.tag(TAG).d("Player preparation started.")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception in preparePlayer outer try-catch")
            e.printStackTrace()
            completion(false)
        }
    }

    override fun setNextDataSource(path: Uri?) {}

    override fun start(): Boolean {
        super.start()
        return try {
            player.play()
            true
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "Error starting player")
            e.printStackTrace()
            false
        }
    }

    override fun stop() {
        super.stop()
        player.stop()
        isInitialized = false
    }

    override fun release() {
        stop()
        player.removeListener(this)
        player.release()
        coroutineScope.cancel()
    }

    override fun pause(): Boolean {
        super.pause()
        return try {
            player.pause()
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error pausing player", e)
            false
        }
    }

    override val isPlaying: Boolean
        get() = isInitialized && player.isPlaying

    override fun duration(): Int {
        return if (!this.isInitialized || player.duration == C.TIME_UNSET) {
            -1
        } else try {
            player.duration.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting duration", e)
            -1
        }
    }

    override fun position(): Int {
        return if (!this.isInitialized || player.currentPosition == C.TIME_UNSET) {
            -1
        } else try {
            player.currentPosition.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting position", e)
            -1
        }
    }

    override fun seek(whereto: Int, force: Boolean): Int {
        return try {
            player.seekTo(whereto.toLong())
            whereto
        } catch (e: Exception) {
            Log.w(TAG, "Error seeking", e)
            -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        return try {
            player.volume = vol
            true
        } catch (e: Exception) {
            false
        }
    }

    @OptIn(UnstableApi::class)
    override fun setAudioSessionId(sessionId: Int): Boolean {
        return try {
            player.audioSessionId = sessionId
            true
        } catch (e: Exception) {
            false
        }
    }

    @OptIn(UnstableApi::class)
    override val audioSessionId: Int
        @OptIn(UnstableApi::class)
        get() = player.audioSessionId

    override fun onPlaybackStateChanged(state: Int) {
        Timber.tag(TAG).d("Global Listener: onPlaybackStateChanged: $state, isInitialized: $isInitialized, current song: ${player.currentMediaItem?.mediaId}")
        if (state == Player.STATE_ENDED) {
            callbacks?.onTrackEnded()
        } else {
            callbacks?.onPlayStateChanged()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.tag(TAG).e(error, "Global Listener: onPlayerError: ")
        isInitialized = false
        context.showToast(R.string.unplayable_file)
        callbacks?.onPlayStateChanged()
        callbacks?.onTrackEnded()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.tag(TAG).d("Global Listener: onMediaItemTransition - New MediaItem: ${mediaItem?.mediaId}, Reason: $reason")
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            callbacks?.onTrackWentToNext()
        }
    }

    override fun setCrossFadeDuration(duration: Int) {}

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    companion object {
        val TAG: String = LostExoPlayer::class.java.simpleName
    }
}
