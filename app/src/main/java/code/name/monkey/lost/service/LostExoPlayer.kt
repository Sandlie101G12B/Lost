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
import androidx.media3.datasource.DefaultDataSource // Added import
import androidx.media3.datasource.DefaultHttpDataSource
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
// Correct import for the USER_AGENT_WEB
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LostExoPlayer @OptIn(UnstableApi::class) constructor
    (context: Context) : AudioManagerPlayback(context), Player.Listener { // Added private val to context
    private lateinit var player: ExoPlayer // Changed to lateinit
    override var callbacks: PlaybackCallbacks? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override var isInitialized = false
        private set

    init {
        // 1. Create the custom HTTP data source factory for online streams
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTubeClient.USER_AGENT_WEB)

        // 2. Create a DefaultDataSource.Factory. This factory can handle multiple URI schemes.
        //    We pass our custom httpDataSourceFactory to its constructor.
        //    It will use httpDataSourceFactory for HTTP/HTTPS and its internal
        //    default factories for other schemes like file:/// and content:///.        
        val mainDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // 3. Create the MediaSourceFactory using this more versatile mainDataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(mainDataSourceFactory) // Use the factory that handles all schemes

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(this) // Add the main listener
    }

    fun extractYouTubeVideoId(youtubeUrl: String): String? {
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.)?(?:youtube\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\.be/)([^"&?/ ]{11})"""),
            // Add more patterns here if you encounter other YouTube URL formats
        )

        for (pattern in patterns) {
            val matcher = pattern.find(youtubeUrl)
            if (matcher != null && matcher.groupValues.size > 1) {
                return matcher.groupValues[1]
            }
        }
        return null
    }

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        isInitialized = false

        if (song.data.startsWith("https")) {
            coroutineScope.launch {
                val determinedSongId: String? = if(song.ytID.isNullOrEmpty()){ // Renamed to avoid confusion
                    extractYouTubeVideoId(song.data)
                }else{
                    song.ytID
                }

                if (determinedSongId.isNullOrBlank()) { // Check if songId is null or blank
                    Log.e(TAG, "Could not determine a valid YouTube song ID for song: ${song.title}, data: ${song.data}, ytID: ${song.ytID}")
                    withContext(Dispatchers.Main) {
                        context.showToast(context.getString(R.string.unable_to_play_song_no_id)) // You might want a more specific string resource
                        completion(false)
                    }
                    return@launch // Exit coroutine if no valid ID
                }

                Log.d(TAG, "Fetching stream URL for YouTube song ID: $determinedSongId")
                // YTPlayerUtils internally handles its own client and user-agent for this call
                val playbackDataResult = YTPlayerUtils.getPlaybackData(
                    videoId = determinedSongId, // No !! needed now due to the check above
                    audioQuality = AudioQuality.AUTO
                )

                println("new_gen Playback Data Result: $playbackDataResult")

                withContext(Dispatchers.Main) {
                    playbackDataResult.fold(
                        onSuccess = { playbackData ->
                            song.streamUrl = playbackData.streamUrl
                            Log.d(TAG, "Successfully fetched stream URL: ${playbackData.streamUrl}")
                            val mediaItem = MediaItem.fromUri(playbackData.streamUrl)
                            preparePlayer(mediaItem, completion)
                        },
                        onFailure = {
                            Log.e(TAG, "Failed to get stream URL for $determinedSongId", it)
                            context.showToast(context.getString(R.string.unable_to_stream_youtube_song))
                            completion(false)
                        }
                    )
                }
            }
        } else {

            Log.d(TAG, "Setting data source for local song: ${song.data}")
            val mediaItem = MediaItem.fromUri(song.uri)
            println("Setting data source for local song: ${mediaItem}")
            preparePlayer(mediaItem, completion)
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
                    false // Do not handle audio focus automatically, AudioManagerPlayback handles it
                )
                player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)

                // Add a one-time listener for STATE_READY or error during this preparation
                val readyListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            player.removeListener(this) // Remove this specific one-time listener
                            isInitialized = true
                            Log.d(TAG, "Player is ready.")
                            completion(true)
                        } else if (state == Player.STATE_IDLE || state == Player.STATE_BUFFERING) {
                            // Still waiting for ready or has failed
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        player.removeListener(this) // Remove this specific one-time listener
                        Log.e(TAG, "Player error during specific preparation: ", error)
                        // The global onPlayerError will also be called, but we ensure completion(false) here for this specific prep.
                        completion(false)
                    }
                }
                player.addListener(readyListener)
                player.prepare()
                Log.d(TAG, "Player preparation started.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in preparePlayer outer try-catch", e)
            e.printStackTrace()
            completion(false)
        }
    }

    override fun setNextDataSource(path: Uri?) {}

    override fun start(): Boolean {
        super.start() // Handles audio focus and noisy receiver
        return try {
            player.play()
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error starting player", e)
            e.printStackTrace()
            false
        }
    }

    override fun stop() {
        super.stop() // Handles audio focus and noisy receiver
        player.stop()
        isInitialized = false
    }

    override fun release() {
        // super.release() is not called as AudioManagerPlayback doesn't implement it.
        // super.stop() is called via this.stop() which handles AudioManagerPlayback cleanup.
        stop()
        player.removeListener(this) // Remove the main listener
        player.release()
        coroutineScope.cancel() // Cancel coroutines when player is released
    }

    override fun pause(): Boolean {
        super.pause() // Handles noisy receiver
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

    // Player.Listener methods (global listener added in init)
    override fun onPlaybackStateChanged(state: Int) {
        Log.d(TAG, "Global Listener: onPlaybackStateChanged: $state, isInitialized: $isInitialized, current song: ${player.currentMediaItem?.mediaId}")
        if (state == Player.STATE_ENDED) {
            callbacks?.onTrackEnded()
        } else {
            callbacks?.onPlayStateChanged() // General state change like buffering, ready etc.
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Global Listener: onPlayerError: ", error)
        isInitialized = false
        println("PLayer: lostexoplayer error")
        context.showToast(R.string.unplayable_file)
        callbacks?.onPlayStateChanged() // Notify about state change (e.g., to update UI to a paused/error state)
        callbacks?.onTrackEnded() // Consider if this should be called to advance queue on error
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Log.d(TAG, "Global Listener: onMediaItemTransition - New MediaItem: ${mediaItem?.mediaId}, Reason: $reason")
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
