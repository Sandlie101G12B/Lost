package code.name.monkey.lost.fragments.player.color

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.lost.R
import code.name.monkey.lost.databinding.FragmentColorPlayerBinding
import code.name.monkey.lost.extensions.colorControlNormal
import code.name.monkey.lost.extensions.drawAboveSystemBars
import code.name.monkey.lost.extensions.whichFragment
import code.name.monkey.lost.fragments.base.AbsPlayerFragment
import code.name.monkey.lost.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.lost.helper.ListStringTypeAdapter
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.helper.SongDataManager
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class ColorFragment : AbsPlayerFragment(R.layout.fragment_color_player), TextureView.SurfaceTextureListener {
    private var lastColor: Int = 0
    private var navigationColor: Int = 0
    private lateinit var playbackControlsFragment: ColorPlaybackControlsFragment
    private var valueAnimator: ValueAnimator? = null
    private var _binding: FragmentColorPlayerBinding? = null
    private val binding get() = _binding!!
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleTimeout = 10000L // 10 seconds
    private val idleRunnable = Runnable { onIdle() }
    private var isIdle = false
    private var videoDataList: List<VideoData> = emptyList()
    private var mediaPlayer: MediaPlayer? = null
    private var currentVideoFile: File? = null

    data class VideoData(val id: String, val filename: String, val energy_level: String)

    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    override val paletteColor: Int
        get() = navigationColor

    fun findSongMetadata(song: Song): Float {
        val defaultSongsJson = SongDataManager.defaultSongsJson
        val listType = object : TypeToken<List<SongMetaData>>() {}.type
        val metadataList: List<SongMetaData>

        // Build Gson with the custom TypeAdapter for the List<String> type
        val gson = GsonBuilder()
            .registerTypeAdapter(
                object : TypeToken<List<String>>() {}.type,
                ListStringTypeAdapter()
            )
            .create()

        try {
            // Use the customized gson instance
            metadataList = gson.fromJson(defaultSongsJson, listType)
        } catch (e: JsonSyntaxException) {
            // 🚨 We caught the error! Log it to Logcat and dump the JSON to the file.
            throw e
        }

        val map = metadataList.filter { it.file.isNotBlank() }.associateBy { File(it.file).nameWithoutExtension.lowercase() }
        
        val key = File(song.data).nameWithoutExtension.lowercase()
        return map[key]?.energy?.toFloat() ?: 0.5f
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        Timber.tag("ColorFragment").d("onColorChanged: backgroundColor=${color.backgroundColor}")
        libraryViewModel.updateColor(color.backgroundColor)
        lastColor = color.secondaryTextColor
        playbackControlsFragment.setColor(color)
        navigationColor = color.backgroundColor

        binding.colorGradientBackground.setBackgroundColor(color.backgroundColor)
        
        val startColor = ColorUtil.withAlpha(color.backgroundColor, 0f)
        val endColor = ColorUtil.withAlpha(color.backgroundColor, 1f)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(startColor, ColorUtil.withAlpha(color.backgroundColor, 0.6f), ColorUtil.withAlpha(color.backgroundColor, 0.6f), ColorUtil.withAlpha(color.backgroundColor, 0.6f), ColorUtil.withAlpha(color.backgroundColor, 0.6f), endColor)
        )
        binding.videoGradientOverlay?.background = gradient

        val animator =
            playbackControlsFragment.createRevealAnimator(binding.colorGradientBackground)
        animator.doOnEnd {
            _binding?.root?.setBackgroundColor(color.backgroundColor)
        }
        animator.start()
        binding.playerToolbar.post {
            ToolbarContentTintHelper.colorizeToolbar(
                binding.playerToolbar,
                color.secondaryTextColor,
                requireActivity()
            )
        }
    }

    override fun onFavoriteToggled() {
        Timber.tag("ColorFragment").d("onFavoriteToggled")
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    override fun onShow() {
        Timber.tag("ColorFragment").d("onShow")
        playbackControlsFragment.show()
    }

    override fun onHide() {
        Timber.tag("ColorFragment").d("onHide")
        playbackControlsFragment.hide()
    }

    override fun toolbarIconColor(): Int {
        return lastColor
    }

    override fun toggleFavorite(song: Song) {
        super.toggleFavorite(song)
        if (song.id == MusicPlayerRemote.currentSong.id) {
            updateIsFavorite()
        }
    }

    override fun onDestroyView() {
        Timber.tag("ColorFragment").d("onDestroyView")
        super.onDestroyView()
        idleHandler.removeCallbacks(idleRunnable)
        releaseMediaPlayer()
        if (valueAnimator != null) {
            valueAnimator!!.cancel()
            valueAnimator = null
        }
        _binding = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Timber.tag("ColorFragment").d("onViewCreated")
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentColorPlayerBinding.bind(view)
        setUpSubFragments()
        setUpPlayerToolbar()
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment =
            whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setCallbacks(this)
        playerToolbar().drawAboveSystemBars()

        binding.idleVideoView?.surfaceTextureListener = this

        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                Timber.tag("ColorFragment").d("Touch detected")
                resetIdleTimer()
            }
            false
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.tag("ColorFragment").d("onResume: isPlaying=${MusicPlayerRemote.isPlaying}")
        if (MusicPlayerRemote.isPlaying) {
            resetIdleTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.tag("ColorFragment").d("onPause")
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun onPlayStateChanged() {
        super.onPlayStateChanged()
        Timber.tag("ColorFragment").d("onPlayStateChanged: isPlaying=${MusicPlayerRemote.isPlaying}, isIdle=$isIdle")
        if (!MusicPlayerRemote.isPlaying && isIdle) {
            Timber.tag("ColorFragment").d("onPlayStateChanged: Paused and Idle -> onActive")
            onActive()
        } else if (MusicPlayerRemote.isPlaying && !isIdle) {
            Timber.tag("ColorFragment").d("onPlayStateChanged: Playing and Active -> resetIdleTimer")
            resetIdleTimer()
        } else {
            Timber.tag("ColorFragment").d("onPlayStateChanged: No action taken")
        }
    }

    private fun resetIdleTimer() {
        Timber.tag("ColorFragment").d("resetIdleTimer: isIdle=$isIdle")
        if (isIdle) {
            onActive()
        }
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, idleTimeout)
    }

    private fun onIdle() {
        Timber.tag("ColorFragment").d("onIdle: Checking conditions. isIdle=$isIdle, isAdded=$isAdded, isResumed=$isResumed, isPlaying=${MusicPlayerRemote.isPlaying}")
        if (isIdle || !isAdded || !isResumed || !MusicPlayerRemote.isPlaying) {
            Timber.tag("ColorFragment").d("onIdle: Conditions NOT met. Aborting.")
            return
        }
        isIdle = true
        Timber.tag("ColorFragment").d("onIdle: Conditions met. Starting idle sequence.")

        lifecycleScope.launch {
            if (videoDataList.isEmpty()) {
                Timber.tag("ColorFragment").d("onIdle: Loading video data")
                loadVideoData()
            }
            if (videoDataList.isNotEmpty()) {
                val song = MusicPlayerRemote.currentSong
                val energy = withContext(Dispatchers.IO) {
                    try {
                        findSongMetadata(song)
                    } catch (e: Exception) {
                        Timber.tag("ColorFragment").e(e, "onIdle: Failed to find metadata")
                        0.5f
                    }
                }
                val energyLevel = if (energy > 0.5f) "high" else "low"
                Timber.tag("ColorFragment").d("onIdle: Song energy=$energy, level=$energyLevel")

                val filteredVideos = videoDataList.filter { it.energy_level.equals(energyLevel, ignoreCase = true) }
                val videosToChooseFrom = if (filteredVideos.isNotEmpty()) filteredVideos else videoDataList
                
                val video = videosToChooseFrom.random()
                Timber.tag("ColorFragment").d("onIdle: Selected video: ${video.filename} (energy: ${video.energy_level})")
                
                currentVideoFile = copyAssetToCache(video.filename)
                if (currentVideoFile != null) {
                    Timber.tag("ColorFragment").d("onIdle: Video file prepared: ${currentVideoFile!!.absolutePath}")
                    
                    // Make TextureView visible to trigger onSurfaceTextureAvailable if needed
                    binding.idleVideoView?.alpha = 0f
                    binding.idleVideoView?.visibility = View.VISIBLE
                    
                    if (binding.idleVideoView?.isAvailable == true) {
                        startMediaPlayer(Surface(binding.idleVideoView!!.surfaceTexture))
                    }
                } else {
                    Timber.tag("ColorFragment").e("onIdle: Failed to copy video file")
                }
            } else {
                Timber.tag("ColorFragment").w("onIdle: No video data available")
            }
        }
    }

    private fun startMediaPlayer(surface: Surface) {
        try {
            releaseMediaPlayer()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(currentVideoFile!!.absolutePath)
            mediaPlayer?.setSurface(surface)
            mediaPlayer?.isLooping = true
            mediaPlayer?.setVolume(0f, 0f)
            
            mediaPlayer?.setOnVideoSizeChangedListener { _, width, height ->
                adjustAspectRatio(width, height)
            }

            mediaPlayer?.setOnPreparedListener { mp ->
                Timber.tag("ColorFragment").d("startMediaPlayer: Prepared")
                adjustAspectRatio(mp.videoWidth, mp.videoHeight)
                mp.start()
                fadeInVideo()
            }
            mediaPlayer?.setOnErrorListener { _, what, extra ->
                Timber.tag("ColorFragment").e("startMediaPlayer: Error what=$what, extra=$extra")
                onActive()
                true
            }
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            Timber.tag("ColorFragment").e(e, "startMediaPlayer: Exception")
            onActive()
        }
    }

    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        val textureView = binding.idleVideoView ?: return
        if (videoWidth == 0 || videoHeight == 0) return
        if (textureView.width == 0 || textureView.height == 0) {
            textureView.post { adjustAspectRatio(videoWidth, videoHeight) }
            return
        }

        val viewWidth = textureView.width
        val viewHeight = textureView.height
        val viewRatio = viewWidth.toDouble() / viewHeight
        val videoRatio = videoWidth.toDouble() / videoHeight

        var scaleX = 1.0
        var scaleY = 1.0

        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
        } else {
            scaleY = viewRatio / videoRatio
        }

        val matrix = Matrix()
        matrix.setScale(scaleX.toFloat(), scaleY.toFloat(), viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
        Timber.tag("ColorFragment").d("adjustAspectRatio: scaled to $scaleX, $scaleY")
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun onActive() {
        Timber.tag("ColorFragment").d("onActive")
        isIdle = false
        idleHandler.removeCallbacks(idleRunnable)
        if (MusicPlayerRemote.isPlaying) {
            idleHandler.postDelayed(idleRunnable, idleTimeout) // Restart timer only if playing
        }

        if (binding.idleVideoView?.isVisible ?: false) {
            Timber.tag("ColorFragment").d("onActive: Stopping video and fading out")
            fadeOutVideo()
        }
    }

    private fun fadeInVideo() {
        Timber.tag("ColorFragment").d("fadeInVideo")
        binding.idleVideoView?.animate()
            ?.alpha(1f)
            ?.setDuration(500)
            ?.start()

        binding.videoGradientOverlay?.alpha = 0f
        binding.videoGradientOverlay?.visibility = View.VISIBLE
        binding.videoGradientOverlay?.animate()
            ?.alpha(1f)
            ?.setDuration(500)
            ?.start()

        binding.playerAlbumCoverFragment.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { binding.playerAlbumCoverFragment.visibility = View.INVISIBLE }
            .start()
            
        binding.colorGradientBackground.animate()
            .alpha(0f)
            .setDuration(500)
            .start()
    }

    private fun fadeOutVideo() {
        Timber.tag("ColorFragment").d("fadeOutVideo")
        binding.idleVideoView?.animate()
            ?.alpha(0f)
            ?.setDuration(500)
            ?.withEndAction {
                binding.idleVideoView!!.visibility = View.GONE
                releaseMediaPlayer()
            }
            ?.start()

        binding.videoGradientOverlay?.animate()
            ?.alpha(0f)
            ?.setDuration(500)
            ?.withEndAction {
                binding.videoGradientOverlay!!.visibility = View.GONE
            }
            ?.start()

        binding.playerAlbumCoverFragment.visibility = View.VISIBLE
        binding.playerAlbumCoverFragment.animate()
            .alpha(1f)
            .setDuration(500)
            .start()
            
        binding.colorGradientBackground.animate()
            .alpha(1f)
            .setDuration(500)
            .start()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Timber.tag("ColorFragment").d("onSurfaceTextureAvailable")
        if (isIdle && currentVideoFile != null) {
            startMediaPlayer(Surface(surface))
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (mediaPlayer != null) {
            adjustAspectRatio(mediaPlayer!!.videoWidth, mediaPlayer!!.videoHeight)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Timber.tag("ColorFragment").d("onSurfaceTextureDestroyed")
        releaseMediaPlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    private suspend fun loadVideoData() {
        Timber.tag("ColorFragment").d("loadVideoData: Loading from assets")
        withContext(Dispatchers.IO) {
            try {
                val json = requireContext().assets.open("video_data.json").bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<VideoData>>() {}.type
                videoDataList = Gson().fromJson(json, type)
                Timber.tag("ColorFragment").d("loadVideoData: Loaded ${videoDataList.size} items")
            } catch (e: Exception) {
                Timber.tag("ColorFragment").e(e, "loadVideoData: Failed to load video data")
                e.printStackTrace()
            }
        }
    }

    private suspend fun copyAssetToCache(filename: String): File? {
        Timber.tag("ColorFragment").d("copyAssetToCache: Copying $filename")
        return withContext(Dispatchers.IO) {
            try {
                val cacheDir = requireContext().cacheDir
                val outFile = File(cacheDir, filename)
                if (outFile.exists()) {
                    Timber.tag("ColorFragment").d("copyAssetToCache: File already exists in cache: $filename")
                    return@withContext outFile // Already cached
                }

                val inputStream = requireContext().assets.open("videos/$filename")
                val outputStream = FileOutputStream(outFile)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                Timber.tag("ColorFragment").d("copyAssetToCache: Successfully copied $filename")
                outFile
            } catch (e: Exception) {
                Timber.tag("ColorFragment").e(e, "copyAssetToCache: Failed to copy $filename")
                e.printStackTrace()
                null
            }
        }
    }

    private fun setUpSubFragments() {
        playbackControlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    private fun setUpPlayerToolbar() {
        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_player)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            setOnMenuItemClickListener(this@ColorFragment)
            ToolbarContentTintHelper.colorizeToolbar(
                this,
                colorControlNormal(),
                requireActivity()
            )
        }
    }

    companion object {
        fun newInstance(): ColorFragment {
            return ColorFragment()
        }
    }
}
