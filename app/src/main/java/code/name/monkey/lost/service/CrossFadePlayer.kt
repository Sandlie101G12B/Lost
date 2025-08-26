package code.name.monkey.lost.service

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.net.toUri
import code.name.monkey.appthemehelper.util.VersionUtils.hasMarshmallow
import code.name.monkey.lost.R
import code.name.monkey.lost.extensions.showToast
import code.name.monkey.lost.extensions.uri
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.helper.MetaDataManagerHelper // Added import
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.service.AudioFader.Companion.createFadeAnimator
import code.name.monkey.lost.service.playback.Playback.PlaybackCallbacks
import code.name.monkey.lost.util.PreferenceUtil
import code.name.monkey.lost.util.PreferenceUtil.playbackPitch // Direct import
import code.name.monkey.lost.util.PreferenceUtil.playbackSpeed // Direct import
import code.name.monkey.lost.util.logE
import kotlinx.coroutines.*

/*
* To make Crossfade work we need two MediaPlayer's
* Basically, we switch back and forth between those two mp's
* e.g. When song is about to end (Reaches Crossfade duration) we let current mediaplayer
* play but with decreasing volume and start the player with the next song with increasing volume
* and vice versa for upcoming song and so on.
*/
class CrossFadePlayer(context: Context) : AudioManagerPlayback(context), MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private var currentPlayer: CurrentPlayer = CurrentPlayer.NOT_SET
    private var player1 = MediaPlayer()
    private var player2 = MediaPlayer()
    private val crossFadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) // Defined crossFadeScope
    private var durationListener = DurationListener()
    private var mIsInitialized = false
    private var hasDataSource: Boolean = false /* Whether first player has DataSource */
    private var nextDataSource:String? = null
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
        // Assuming song.uri.toString() is the key used in metadata's 'file' field.
        // If Song.kt has a 'data' field that is the canonical path, use song.data instead.
        val songFilePath = song.uri.toString()
        if (songFilePath.isEmpty()) return null

        val metaDataList = MetaDataManagerHelper.getSongMetaDataList()
        val songMetaData = metaDataList.find { it.file == songFilePath }
        // Ensure BPM is finite and positive, otherwise it can cause issues.
        return songMetaData?.bpm?.takeIf { it.isFinite() && it > 0 }
    }

    override fun start(): Boolean {
        super.start()
        durationListener.start()
        resumeFade()
        return try {
            getCurrentPlayer()?.start()
            if (isCrossFading) {
                getNextPlayer()?.start()
            }
            true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    override fun release() {
        stop()
        cancelFade()
        crossFadeScope.cancel() // Cancel the scope
        getCurrentPlayer()?.release()
        getNextPlayer()?.release()
        durationListener.cancel() // DurationListener also needs its own job cancelled if it maintains one separately
    }

    override fun stop() {
        super.stop()
        getCurrentPlayer()?.reset()
        mIsInitialized = false
    }

    override fun pause(): Boolean {
        super.pause()
        durationListener.stop()
        pauseFade()
        getCurrentPlayer()?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        getNextPlayer()?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        return true
    }

    override fun seek(whereto: Int, force: Boolean): Int {
        if (force) {
            endFade()
        }
        getNextPlayer()?.stop()
        return try {
            getCurrentPlayer()?.seekTo(whereto)
            whereto
        } catch (e: java.lang.IllegalStateException) {
            e.printStackTrace()
            -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        cancelFade()
        return try {
            getCurrentPlayer()?.setVolume(vol, vol)
            true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    override val isInitialized: Boolean
        get() = mIsInitialized

    override val isPlaying: Boolean
        get() = mIsInitialized && getCurrentPlayer()?.isPlaying == true

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        if (force) hasDataSource = false
        mIsInitialized = false
        /* We've already set DataSource if initialized is true in setNextDataSource */
        if (!hasDataSource) {
            getCurrentPlayer()?.let {
                setDataSourceImpl(it, song.uri.toString()) { success ->
                    mIsInitialized = success
                    completion(success)
                }
            }
            hasDataSource = true
        } else {
            completion(true)
            mIsInitialized = true
        }
    }

    override fun setNextDataSource(path: Uri?) {
        nextDataSource = path.toString()
    }

    override fun setAudioSessionId(sessionId: Int): Boolean {
        return try {
            getCurrentPlayer()?.audioSessionId = sessionId
            true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    override val audioSessionId: Int
        get() = getCurrentPlayer()?.audioSessionId!!

    override fun duration(): Int {
        return if (!mIsInitialized) {
            -1
        } else try {
            getCurrentPlayer()?.duration!!
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            -1
        }
    }

    override fun position(): Int {
        return if (!mIsInitialized) {
            -1
        } else try {
            getCurrentPlayer()?.currentPosition!!
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            -1
        }
    }

    override fun onCompletion(mp: MediaPlayer?) {
        if (mp == getCurrentPlayer()) {
            callbacks?.onTrackEnded()
        }
    }

    private fun getCurrentPlayer(): MediaPlayer? {
        return when (currentPlayer) {
            CurrentPlayer.PLAYER_ONE -> player1
            CurrentPlayer.PLAYER_TWO -> player2
            CurrentPlayer.NOT_SET -> null
        }
    }

    private fun getNextPlayer(): MediaPlayer? {
        return when (currentPlayer) {
            CurrentPlayer.PLAYER_ONE -> player2
            CurrentPlayer.PLAYER_TWO -> player1
            CurrentPlayer.NOT_SET -> null
        }
    }

    private fun crossFade(fadeInMp: MediaPlayer, fadeOutMp: MediaPlayer) {
        isCrossFading = true
        crossFadeAnimator = createFadeAnimator(context, fadeInMp, fadeOutMp) {
            crossFadeAnimator = null
            durationListener.start() // Restart duration listener on the main scope after fade
            isCrossFading = false
        }
        crossFadeAnimator?.start()
    }

    private fun endFade() {
        crossFadeAnimator?.end()
        crossFadeAnimator = null
    }

    private fun cancelFade() {
        crossFadeAnimator?.cancel()
        crossFadeAnimator = null
    }

    private fun pauseFade() {
        crossFadeAnimator?.pause()
    }

    private fun resumeFade() {
        if (crossFadeAnimator?.isPaused == true) {
            crossFadeAnimator?.resume()
        }
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        mIsInitialized = false
        mp?.release()
        player1 = MediaPlayer()
        player2 = MediaPlayer()
        mIsInitialized = true
        mp?.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        context.showToast(R.string.unplayable_file)
        logE(what.toString() + extra)
        return false
    }

    enum class CurrentPlayer {
        PLAYER_ONE,
        PLAYER_TWO,
        NOT_SET
    }

    inner class DurationListener : CoroutineScope by crossFadeScope { // Corrected delegation
        private var job: Job? = null
        fun start() {
            job?.cancel() // Cancel previous job if any
            job = launch {
                while (isActive) {
                    delay(250)
                    onDurationUpdated(position(), duration())
                }
            }
        }
        fun stop() { 
            job?.cancel() 
        }
        // Added cancel to be called from CrossFadePlayer.release
        fun cancel() {
            job?.cancel()
            // No need to cancel the crossFadeScope here, it's done by the outer class
        }
    }

    fun onDurationUpdated(progress: Int, total: Int) {
        if (total > 0 && (total - progress).div(1000) == crossFadeDuration) {
            getNextPlayer()?.let { player ->
                val songFadingOut = MusicPlayerRemote.currentSong // Song that will fade out
                val songFadingIn = MusicPlayerRemote.nextSong // Song that will fade in

                val fadingOutBpm = getBpmFromMetaData(songFadingOut) ?: 0f
                val fadingInBpm = getBpmFromMetaData(songFadingIn) ?: 0f
                
                val userSpeedPref = playbackSpeed // User's global speed preference
                var initialSpeedForFadingInSong = userSpeedPref

                if (fadingOutBpm > 0f && fadingInBpm > 0f && fadingOutBpm != fadingInBpm) {
                    initialSpeedForFadingInSong = (fadingOutBpm * userSpeedPref) / fadingInBpm
                }
                // Ensure initial speed is positive
                initialSpeedForFadingInSong = initialSpeedForFadingInSong.takeIf { it.isFinite() && it > 0 } ?: userSpeedPref


                if (songFadingIn != null && songFadingIn != Song.emptySong) {
                    nextDataSource = null // Clear as we are using MusicPlayerRemote.nextSong
                    setDataSourceImpl(player, songFadingIn.uri.toString()) { success ->
                        if (success) {
                            player.setPlaybackSpeedPitch(initialSpeedForFadingInSong, playbackPitch)
                            switchPlayer()
                        }
                    }
                } else if (!nextDataSource.isNullOrEmpty()) {
                    val pathForNextSong = nextDataSource!!
                    // Try to get Song object for pathForNextSong to get its BPM
                    // This is a placeholder: You might need a way to get Song from path if MusicPlayerRemote.nextSong was null
                    // songFadingIn = MusicPlayerRemote.findSongByPath(pathForNextSong) 
                    // if (songFadingIn != null) { fadingInBpm = getBpmFromMetaData(songFadingIn) ?: 0f }
                    // Recalculate initialSpeedForFadingInSong if songFadingIn was found and BPM is valid
                    // For simplicity, if songFadingIn is null here, fadingInBpm remains 0f or its previous value,
                    // and initialSpeedForFadingInSong might default to userSpeedPref if BPMs don't allow sync.

                    if (fadingOutBpm > 0f && fadingInBpm > 0f && fadingOutBpm != fadingInBpm) { // Re-check with potentially updated fadingInBpm
                         initialSpeedForFadingInSong = (fadingOutBpm * userSpeedPref) / fadingInBpm
                    }
                    initialSpeedForFadingInSong = initialSpeedForFadingInSong.takeIf { it.isFinite() && it > 0 } ?: userSpeedPref

                    setDataSourceImpl(player, pathForNextSong) { success ->
                        if (success) {
                            player.setPlaybackSpeedPitch(initialSpeedForFadingInSong, playbackPitch)
                            switchPlayer()
                        }
                        // nextDataSource = null // Clear it after use - moved to switchPlayer or if loading fails
                    }
                }
            }
        }
    }

    private fun switchPlayer() {
        val fadingInMediaPlayer = getNextPlayer() ?: return
        val fadingOutMediaPlayer = getCurrentPlayer() ?: return

        fadingInMediaPlayer.start()
        crossFade(fadingInMediaPlayer, fadingOutMediaPlayer)
        nextDataSource = null // Clear nextDataSource as we've switched

        val songThatFadedOut = MusicPlayerRemote.currentSong // This is the one that just started fading out
        val songThatIsFadingIn = MusicPlayerRemote.nextSong // This is the new primary song

        val userTargetSpeed = playbackSpeed // User's global speed preference
        val userPitch = playbackPitch

        val fadingOutBpm = getBpmFromMetaData(songThatFadedOut) ?: 0f
        val fadingInBpm = getBpmFromMetaData(songThatIsFadingIn) ?: 0f

        var speedToAnimateFrom = userTargetSpeed // Default start for animation is user's preference
        
        if (fadingOutBpm > 0f && fadingInBpm > 0f && fadingOutBpm != fadingInBpm) {
            // This was the target speed for the incoming player to match the outgoing one
            val calculatedInitialSpeed = (fadingOutBpm * userTargetSpeed) / fadingInBpm
            speedToAnimateFrom = calculatedInitialSpeed.takeIf { it.isFinite() && it > 0 } ?: userTargetSpeed
        }
        
        // Try to get the *actual* current speed of the player that just started fading in
        if (hasMarshmallow()) {
            fadingInMediaPlayer.playbackParams.speed.let { currentActualSpeed ->
                if (currentActualSpeed.isFinite() && currentActualSpeed > 0) {
                    speedToAnimateFrom = currentActualSpeed
                }
            }
        }
        // Ensure speedToAnimateFrom is positive before animating
        speedToAnimateFrom = speedToAnimateFrom.takeIf { it.isFinite() && it > 0 } ?: userTargetSpeed

        if (speedToAnimateFrom != userTargetSpeed) {
            ValueAnimator.ofFloat(speedToAnimateFrom, userTargetSpeed).apply {
                duration = 3000 // Consider making this duration configurable
                addUpdateListener { anim ->
                    val animatedSpeedValue = anim.animatedValue as Float
                    fadingInMediaPlayer.setPlaybackSpeedPitch(animatedSpeedValue, userPitch)
                }
                start()
            }
        } else {
            // If speeds are already the same, or no BPM sync was done, ensure it's at userTargetSpeed
            fadingInMediaPlayer.setPlaybackSpeedPitch(userTargetSpeed, userPitch)
        }
        
        currentPlayer = if (currentPlayer == CurrentPlayer.PLAYER_ONE || currentPlayer == CurrentPlayer.NOT_SET) {
            CurrentPlayer.PLAYER_TWO
        } else {
            CurrentPlayer.PLAYER_ONE
        }
        callbacks?.onTrackEndedWithCrossfade()
    }

    override fun setCrossFadeDuration(duration: Int) {
        crossFadeDuration = duration
    }

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        val safePitch = pitch.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        
        getCurrentPlayer()?.setPlaybackSpeedPitch(safeSpeed, safePitch)
        // Only set on next player if it's currently also playing (i.e. during crossfade)
        if (isCrossFading) {
             getNextPlayer()?.setPlaybackSpeedPitch(safeSpeed, safePitch)
        }
    }

    private fun setDataSourceImpl(
        player: MediaPlayer,
        path: String,
        completion: (success: Boolean) -> Unit,
    ) {
        player.reset()
        try {
            if (path.startsWith("content://")) {
                player.setDataSource(context, path.toUri())
            } else {
                player.setDataSource(path)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Initial speed/pitch is set in onDurationUpdated before switchPlayer,
            // or by global setPlaybackSpeedPitch. No need to set default here
            // as it might override BPM-specific logic.

            player.setOnPreparedListener {
                player.setOnPreparedListener(null) // Avoid multiple calls
                completion(true)
            }
            player.setOnErrorListener { _, _, _ ->
                logE("MediaPlayer error during prepare for path: $path")
                completion(false)
                true // Indicate error has been handled
            }
            player.prepareAsync()
        } catch (e: Exception) {
            logE("Exception setting data source for path: $path, error: $e")
            completion(false)
        }
    }
}

// Extension for MediaPlayer to safely set playback speed and pitch
fun MediaPlayer.setPlaybackSpeedPitch(speed: Float, pitch: Float) {
    if (hasMarshmallow()) {
        try {
            // Ensure speed and pitch are positive and finite.
            val safeSpeed = speed.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
            val safePitch = pitch.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f

            val params = this.playbackParams // Get existing or new
            this.playbackParams = params.setSpeed(safeSpeed).setPitch(safePitch)
        } catch (e: Exception) {
            Log.e("setPlaybackSpeedPitch", "Error setting playback speed and pitch", e)
        }
    }
}
