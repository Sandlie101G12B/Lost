package code.name.monkey.lost.service

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import androidx.core.net.toUri
import code.name.monkey.appthemehelper.util.VersionUtils.hasMarshmallow
import code.name.monkey.lost.R
import code.name.monkey.lost.extensions.showToast
import code.name.monkey.lost.helper.MetaData
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.service.AudioFader.Companion.createFadeAnimator
import code.name.monkey.lost.service.playback.Playback.PlaybackCallbacks
import code.name.monkey.lost.util.AudioQuality
import code.name.monkey.lost.util.PreferenceUtil
import code.name.monkey.lost.util.PreferenceUtil.playbackPitch
import code.name.monkey.lost.util.PreferenceUtil.playbackSpeed
import code.name.monkey.lost.util.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class CrossFadePlayer(context: Context) : AudioManagerPlayback(context), MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {
    private val tag = "CrossFadePlayer"

    private var currentPlayer: CurrentPlayer = CurrentPlayer.NOT_SET
    private var player1 = MediaPlayer()
    private var player2 = MediaPlayer()

    // Scope for UI-related coroutines and tasks that need to be on Main.immediate
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Scope for I/O bound tasks like fetching stream URLs
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var durationListener = DurationListener()
    private var mIsInitialized = false
    private var hasDataSourceForCurrentPlayer: Boolean = false

    private var crossFadeAnimator: Animator? = null
    override var callbacks: PlaybackCallbacks? = null
    private var crossFadeDuration = PreferenceUtil.crossFadeDuration
    var isCrossFading = false

    init {
        player1.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        player2.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        currentPlayer = CurrentPlayer.PLAYER_ONE
    }

    private fun getBpmFromMetaData(song: Song?): Float? {
        if (song == null) return null
        val songFilePath = if (song.isYTSong) song.ytID else song.data // Use ytID or local data path
        if (songFilePath.isNullOrEmpty()) return null

        val metaDataList = MetaData.getSongMetaDataList()
        // Adjust find condition based on what's stored in metaDataList.file (might be ytID for online)
        val songMetaData = metaDataList.find { it.file == songFilePath || (song.isYTSong && it.ytID == song.ytID) }
        return songMetaData?.bpm?.takeIf { it.isFinite() && it > 0 }
    }

    override fun start(): Boolean {
        super.start() // AudioManagerPlayback handles focus and noisy receiver
        durationListener.start()
        resumeFade()
        return try {
            getCurrentPlayer()?.start()
            if (isCrossFading) {
                getNextPlayer()?.start()
            }
            true
        } catch (e: IllegalStateException) {
            Timber.tag(tag).e(e, "Error starting player(s)")
            false
        }
    }

    override fun release() {
        // DO NOT call super.release() as AudioManagerPlayback does not implement it.
        // Cleanup from AudioManagerPlayback is handled by super.stop() which is called via this.stop()
        stop()
        cancelFade()
        mainScope.cancel() 
        ioScope.cancel()
        player1.release()
        player2.release()
        durationListener.cancel()
    }

    override fun stop() {
        super.stop() // AudioManagerPlayback handles focus and noisy receiver
        getCurrentPlayer()?.reset()
        mIsInitialized = false
        hasDataSourceForCurrentPlayer = false
    }

    override fun pause(): Boolean {
        super.pause() // AudioManagerPlayback handles noisy receiver
        durationListener.stop()
        pauseFade()
        try {
            getCurrentPlayer()?.let { if (it.isPlaying) it.pause() }
            getNextPlayer()?.let { if (it.isPlaying) it.pause() }
        } catch (e: IllegalStateException) {
            Timber.tag(tag).e(e, "Error pausing player(s)")
            return false
        }
        return true
    }

    override fun seek(whereto: Int, force: Boolean): Int {
        if (force) {
            endFade()
        }
        try {
            getNextPlayer()?.stop() // Stop and reset the next player
            getNextPlayer()?.reset()
            getCurrentPlayer()?.seekTo(whereto)
            return whereto
        } catch (e: IllegalStateException) {
            Timber.tag(tag).e(e, "Error seeking")
            return -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        cancelFade()
        return try {
            getCurrentPlayer()?.setVolume(vol, vol)
            true
        } catch (e: IllegalStateException) {
            Timber.tag(tag).e(e, "Error setting volume")
            false
        }
    }

    override val isInitialized: Boolean get() = mIsInitialized
    override val isPlaying: Boolean get() = mIsInitialized && getCurrentPlayer()?.isPlaying == true

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        Timber.tag(tag).d("setDataSource called for song: ${song.title}, isYTSong: ${song.isYTSong}, force: $force")
        if (force) hasDataSourceForCurrentPlayer = false
        mIsInitialized = false

        if (!hasDataSourceForCurrentPlayer) {
            val player = getCurrentPlayer()
            if (player == null) {
                completion(false)
                return
            }

            if(!song.streamUrl.isNullOrEmpty()){
                Timber.tag(tag).d("Setting data source for local current song: ${song.streamUrl}")
                setDataSourceImpl(player, song.streamUrl!!, false) { success -> // Use song.data for local files
                    mIsInitialized = success
                    if(success) hasDataSourceForCurrentPlayer = true
                    completion(success)
                }
            } else if (song.data.startsWith("https")) {
                ioScope.launch {
                    Timber.tag(tag).d("Fetching stream URL for current YT song: ${song.ytID}")
                    val result = YTPlayerUtils.getPlaybackData(song.ytID!!, audioQuality = AudioQuality.AUTO)
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = {
                                Timber.tag(tag).d("Stream URL for current: ${it.streamUrl}")
                                setDataSourceImpl(player, it.streamUrl, true) { success ->
                                    mIsInitialized = success
                                    if(success) hasDataSourceForCurrentPlayer = true
                                    completion(success)
                                }
                            },
                            onFailure = {
                                Timber.tag(tag).e(it, "Failed to get stream for current YT song ${song.ytID}")
                                context.showToast(R.string.unable_to_stream_youtube_song)
                                completion(false)
                            }
                        )
                    }
                }
            } else if (!song.isYTSong) {
                Timber.tag(tag).d("Setting data source for local current song: ${song.data}")
                setDataSourceImpl(player, song.data, false) { success -> // Use song.data for local files
                    mIsInitialized = success
                    if(success) hasDataSourceForCurrentPlayer = true
                    completion(success)
                }
            } else {
                Timber.tag(tag).w("YTSong with no ytID or invalid local song data: ${song.title}")
                completion(false)
            }
        } else {
            Timber.tag(tag)
                .d("DataSource already set for current player, mIsInitialized: $mIsInitialized")
            completion(mIsInitialized) // If datasource was already set, rely on current init state
        }
    }

    override fun setNextDataSource(path: Uri?) {
        Timber.tag(tag)
            .d("setNextDataSource(Uri) called with: $path. This is NOT used for YT Song preparation if MusicPlayerRemote.nextSong is available.")
    }


    override fun setAudioSessionId(sessionId: Int): Boolean {
        return try {
            getCurrentPlayer()?.audioSessionId = sessionId
            true
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Error setting audio session ID")
            false
        }
    }

    override val audioSessionId: Int get() = getCurrentPlayer()?.audioSessionId ?: 0

    override fun duration(): Int {
        return if (!mIsInitialized) -1 else try {
            getCurrentPlayer()?.duration ?: -1
        } catch (e: IllegalStateException) {
            Timber.tag(tag).w(e, "Error getting duration"); -1
        }
    }

    override fun position(): Int {
        return if (!mIsInitialized) -1 else try {
            getCurrentPlayer()?.currentPosition ?: -1
        } catch (e: IllegalStateException) {
            Timber.tag(tag).w(e, "Error getting position"); -1
        }
    }

    override fun onCompletion(mp: MediaPlayer?) {
        Timber.tag(tag)
            .d("onCompletion for player: ${if (mp == player1) "P1" else if (mp == player2) "P2" else "Unknown"}")
        if (mp == getCurrentPlayer() && !isCrossFading) { // Only call onTrackEnded if not in middle of crossfade
            callbacks?.onTrackEnded()
        }
    }

    private fun getCurrentPlayer(): MediaPlayer? = when (currentPlayer) {
        CurrentPlayer.PLAYER_ONE -> player1
        CurrentPlayer.PLAYER_TWO -> player2
        CurrentPlayer.NOT_SET -> null
    }

    private fun getNextPlayer(): MediaPlayer? = when (currentPlayer) {
        CurrentPlayer.PLAYER_ONE -> player2
        CurrentPlayer.PLAYER_TWO -> player1
        CurrentPlayer.NOT_SET -> null
    }

    private fun crossFade(fadeInMp: MediaPlayer, fadeOutMp: MediaPlayer) {
        Timber.tag(tag).d("Starting crossfade.")
        isCrossFading = true
        crossFadeAnimator?.cancel()
        crossFadeAnimator = createFadeAnimator(context, fadeInMp, fadeOutMp) {
            Timber.tag(tag).d("Crossfade animation ended.")
            crossFadeAnimator = null
            mainScope.launch { 
                 durationListener.start() 
            }
            isCrossFading = false
            val playerThatFadedOut = if (getCurrentPlayer() == player1) player2 else player1
            playerThatFadedOut.stop() 
            playerThatFadedOut.reset() 
        }
        crossFadeAnimator?.start()
    }

    private fun endFade() { crossFadeAnimator?.end(); crossFadeAnimator = null; isCrossFading = false }
    private fun cancelFade() { crossFadeAnimator?.cancel(); crossFadeAnimator = null; isCrossFading = false }
    private fun pauseFade() { crossFadeAnimator?.pause() }
    private fun resumeFade() { if (crossFadeAnimator?.isPaused == true) crossFadeAnimator?.resume() }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Timber.tag(tag)
            .e("MediaPlayer Error - what: $what, extra: $extra on player: ${if (mp == player1) "P1" else if (mp == player2) "P2" else "Unknown"}")
        mIsInitialized = false 
        hasDataSourceForCurrentPlayer = false 

        mp?.reset()
        println("PLayer: crossfade error")
        context.showToast(R.string.unplayable_file)
        callbacks?.onTrackEnded() 
        return true 
    }

    enum class CurrentPlayer { PLAYER_ONE, PLAYER_TWO, NOT_SET }

    inner class DurationListener : CoroutineScope by mainScope { 
        private var job: Job? = null
        fun start() {
            job?.cancel()
            job = launch {
                while (isActive) {
                    delay(250)
                    if (mIsInitialized && !isCrossFading) { 
                         onDurationUpdated(position(), duration()) 
                    }
                }
            }
        }
        fun stop() { job?.cancel() }
        fun cancel() { job?.cancel() }
    }

    fun onDurationUpdated(progress: Int, total: Int) {
        if (!mIsInitialized || total <= 0 || crossFadeDuration <= 0) return
        val timeLeftSeconds = (total - progress) / 1000

        if (timeLeftSeconds == crossFadeDuration && !isCrossFading) {
            Timber.tag(tag).d("Crossfade triggered by onDurationUpdated")
            val nextMediaPlayer = getNextPlayer()
            if (nextMediaPlayer == null) {
                Timber.tag(tag).w("Next player is null, cannot crossfade.")
                return
            }

            val songToFadeIn = MusicPlayerRemote.nextSong
            if (songToFadeIn == null || songToFadeIn == Song.emptySong) {
                Timber.tag(tag).d("No next song available from MusicPlayerRemote to crossfade to.")
                return
            }
            Timber.tag(tag)
                .d("Preparing next song for crossfade: ${songToFadeIn.title}, isYT: ${songToFadeIn.isYTSong}")

            if(!songToFadeIn.streamUrl.isNullOrEmpty()){
                Timber.tag(tag).d("Preparing next local song: ${songToFadeIn.streamUrl}")
                setDataSourceImpl(nextMediaPlayer, songToFadeIn.streamUrl!!, false) { success ->
                    if (success) {
                        prepareAndSwitchPlayer(nextMediaPlayer, songToFadeIn)
                    } else {
                        Timber.tag(tag)
                            .e("Failed to setDataSourceImpl for next local song ${songToFadeIn.streamUrl}")
                    }
                }
            } else if (songToFadeIn.data.startsWith("https")) {
                ioScope.launch {
                    Timber.tag(tag).d("Fetching stream URL for next YT song: ${songToFadeIn.ytID}")
                    val result = YTPlayerUtils.getPlaybackData(songToFadeIn.ytID!!, audioQuality = AudioQuality.AUTO)
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = {
                                Timber.tag(tag).d("Stream URL for next: ${it.streamUrl}")
                                setDataSourceImpl(nextMediaPlayer, it.streamUrl, true) { success ->
                                    if (success) {
                                        prepareAndSwitchPlayer(nextMediaPlayer, songToFadeIn)
                                    } else {
                                        Timber.tag(tag)
                                            .e("Failed to setDataSourceImpl for next YT song ${songToFadeIn.ytID}")
                                    }
                                }
                            },
                            onFailure = {
                                Timber.tag(tag).e(
                                    it,
                                    "Failed to get stream for next YT song ${songToFadeIn.ytID}"
                                )
                            }
                        )
                    }
                }
            } else if (!songToFadeIn.isYTSong) {
                Timber.tag(tag).d("Preparing next local song: ${songToFadeIn.data}")
                setDataSourceImpl(nextMediaPlayer, songToFadeIn.data, false) { success -> 
                    if (success) {
                        prepareAndSwitchPlayer(nextMediaPlayer, songToFadeIn)
                    } else {
                        Timber.tag(tag)
                            .e("Failed to setDataSourceImpl for next local song ${songToFadeIn.data}")
                    }
                }
            } else {
                Timber.tag(tag).w("Next YTSong with no ytID or invalid local song: ${songToFadeIn.title}")
            }
        }
    }
    
    private fun prepareAndSwitchPlayer(playerToStart: MediaPlayer, songFadingIn: Song) {
        val songFadingOut = MusicPlayerRemote.currentSong
        val fadingOutBpm = getBpmFromMetaData(songFadingOut) ?: 0f
        val fadingInBpm = getBpmFromMetaData(songFadingIn) ?: 0f
        val userSpeedPref = playbackSpeed
        var initialSpeedForFadingInSong = userSpeedPref

        if (fadingOutBpm > 0f && fadingInBpm > 0f && fadingOutBpm != fadingInBpm) {
            initialSpeedForFadingInSong = (fadingOutBpm * userSpeedPref) / fadingInBpm
        }
        initialSpeedForFadingInSong = initialSpeedForFadingInSong.takeIf { it.isFinite() && it > 0 } ?: userSpeedPref
        
        playerToStart.setPlaybackSpeedPitch(initialSpeedForFadingInSong, playbackPitch)
        switchPlayer(initialSpeedForFadingInSong)
    }

    private fun switchPlayer(initialSpeedForFadingInSong: Float) {
        Timber.tag(tag).d("Executing switchPlayer.")
        val fadeInMediaPlayer = getNextPlayer() ?: return
        val fadeOutMediaPlayer = getCurrentPlayer() ?: return

        fadeInMediaPlayer.start() 
        crossFade(fadeInMediaPlayer, fadeOutMediaPlayer)

        val userTargetSpeed = playbackSpeed
        val userPitch = playbackPitch

        if (initialSpeedForFadingInSong.takeIf { it.isFinite() && it > 0 } != userTargetSpeed) {
            ValueAnimator.ofFloat(initialSpeedForFadingInSong, userTargetSpeed).apply {
                duration = 2000 
                addUpdateListener { anim ->
                    val animatedSpeedValue = anim.animatedValue as Float
                    fadeInMediaPlayer.setPlaybackSpeedPitch(animatedSpeedValue, userPitch)
                }
                start()
            }
        } else {
            fadeInMediaPlayer.setPlaybackSpeedPitch(userTargetSpeed, userPitch)
        }
        
        currentPlayer = if (currentPlayer == CurrentPlayer.PLAYER_ONE) CurrentPlayer.PLAYER_TWO else CurrentPlayer.PLAYER_ONE
        hasDataSourceForCurrentPlayer = true 
        mIsInitialized = true 

        callbacks?.onTrackEndedWithCrossfade()
    }

    override fun setCrossFadeDuration(duration: Int) {
        crossFadeDuration = duration
    }

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        val safePitch = pitch.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        try {
            getCurrentPlayer()?.setPlaybackSpeedPitch(safeSpeed, safePitch)
            if (isCrossFading) {
                 getNextPlayer()?.setPlaybackSpeedPitch(safeSpeed, safePitch) 
            }
        } catch (e: IllegalStateException) {
            Timber.tag(tag).e(e, "Error in setPlaybackSpeedPitch")
        }
    }

    private fun setDataSourceImpl(
        player: MediaPlayer,
        pathOrUrl: String,
        isUrl: Boolean, 
        completion: (success: Boolean) -> Unit,
    ) {
        player.reset()
        try {
            Timber.tag(tag)
                .d("setDataSourceImpl for player: ${if (player == player1) "P1" else "P2"}, pathOrUrl: $pathOrUrl, isUrl: $isUrl")
            if (!isUrl && pathOrUrl.startsWith("content://")) {
                player.setDataSource(context, pathOrUrl.toUri())
            } else {
                player.setDataSource(pathOrUrl) 
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setOnPreparedListener { mp ->
                Timber.tag(tag).d("MediaPlayer prepared for $pathOrUrl")
                mp.setOnPreparedListener(null) 
                completion(true)
            }
            player.setOnErrorListener { _, what, extra ->
                Timber.tag(tag)
                    .e("MediaPlayer error in setDataSourceImpl for $pathOrUrl - what: $what, extra: $extra")
                completion(false)
                true 
            }
            player.setOnCompletionListener(this) 
            player.prepareAsync()
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Exception in setDataSourceImpl for $pathOrUrl")
            completion(false)
        }
    }
}

fun MediaPlayer.setPlaybackSpeedPitch(speed: Float, pitch: Float) {
    if (hasMarshmallow()) {
        try {
            val safeSpeed = speed.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
            val safePitch = pitch.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
            if (this.isPlaying || this.isLooping) { 
                 val params = this.playbackParams
                 this.playbackParams = params.setSpeed(safeSpeed).setPitch(safePitch)
            } else {
                 val params = android.media.PlaybackParams()
                 this.playbackParams = params.setSpeed(safeSpeed).setPitch(safePitch)
            }
        } catch (e: IllegalStateException) {
            Timber.tag("setPlaybackSpeedPitch")
                .w(e, "Failed to set speed/pitch, player likely not in valid state")
        } catch (e: Exception) {
            Timber.tag("setPlaybackSpeedPitch")
                .e(e, "Generic error setting playback speed and pitch")
        }
    }
}
