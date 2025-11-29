package code.name.monkey.lost.fragments.other

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import code.name.monkey.appthemehelper.util.ATHUtil
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.TintHelper
import code.name.monkey.lost.R
import code.name.monkey.lost.databinding.FragmentMiniPlayerBinding
import code.name.monkey.lost.extensions.accentColor
import code.name.monkey.lost.extensions.show
import code.name.monkey.lost.extensions.textColorPrimary
import code.name.monkey.lost.extensions.textColorSecondary
import code.name.monkey.lost.fragments.base.AbsMusicServiceFragment
import code.name.monkey.lost.glide.LostGlideExtension
import code.name.monkey.lost.glide.LostGlideExtension.asBitmapPalette
import code.name.monkey.lost.glide.LostGlideExtension.songCoverOptions
import code.name.monkey.lost.glide.palette.BitmapPaletteWrapper
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.helper.MusicProgressViewUpdateHelper
import code.name.monkey.lost.helper.PlayPauseButtonOnClickHandler
import code.name.monkey.lost.util.LostUtil
import code.name.monkey.lost.util.PreferenceUtil
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlin.math.abs

open class MiniPlayerFragment : AbsMusicServiceFragment(R.layout.fragment_mini_player),
    MusicProgressViewUpdateHelper.Callback, View.OnClickListener {

    private var _binding: FragmentMiniPlayerBinding? = null
    private val binding get() = _binding!!
    private lateinit var progressViewUpdateHelper: MusicProgressViewUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.actionNext -> MusicPlayerRemote.playNextSong()
            R.id.actionPrevious -> MusicPlayerRemote.back()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMiniPlayerBinding.bind(view)
        view.setOnTouchListener(FlingPlayBackController(requireContext()))
        setUpMiniPlayer()
        setUpButtons()
    }

    fun setUpButtons() {
        if (LostUtil.isTablet) {
            binding.actionNext.show()
            binding.actionPrevious.show()
        } else {
            binding.actionNext.isVisible = PreferenceUtil.isExtraControls
            binding.actionPrevious.isVisible = PreferenceUtil.isExtraControls
        }
        binding.actionNext.setOnClickListener(this)
        binding.actionPrevious.setOnClickListener(this)
    }

    private fun setUpMiniPlayer() {
        setUpPlayPauseButton()
        binding.progressBar.accentColor()
    }

    private fun setUpPlayPauseButton() {
        binding.miniPlayerPlayPauseButton.setOnClickListener(PlayPauseButtonOnClickHandler())
    }

    private fun updateSongTitle(primaryColor: Int? = null, secondaryColor: Int? = null) {
        val song = MusicPlayerRemote.currentSong
        val builder = SpannableStringBuilder()

        val title = song.title.toSpannable()
        title.setSpan(ForegroundColorSpan(primaryColor ?: textColorPrimary()), 0, title.length, 0)

        val text = song.artistName.toSpannable()
        text.setSpan(ForegroundColorSpan(secondaryColor ?: textColorSecondary()), 0, text.length, 0)

        builder.append(title).append(" • ").append(text)

        binding.miniPlayerTitle.isSelected = true
        binding.miniPlayerTitle.text = builder
    }

    private fun updateSongCover() {
        val song = MusicPlayerRemote.currentSong
        Glide.with(requireContext())
            .asBitmapPalette()
            .songCoverOptions(song)
            .load(LostGlideExtension.getSongModel(song))
            .into(object : CustomTarget<BitmapPaletteWrapper>() {
                override fun onResourceReady(
                    resource: BitmapPaletteWrapper,
                    transition: Transition<in BitmapPaletteWrapper>?
                ) {
                    binding.image.setImageBitmap(resource.bitmap)
                    val processor = MediaNotificationProcessor(requireContext(), resource.bitmap)
                    updateColors(processor)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    binding.image.setImageDrawable(errorDrawable)
                    resetColors()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Not implemented
                }
            })
    }

    private fun updateColors(processor: MediaNotificationProcessor) {
        val hsv = FloatArray(3)
        Color.colorToHSV(processor.backgroundColor, hsv)
        hsv[1] = 0.25f
        val desaturatedColor = Color.HSVToColor(hsv)

        binding.root.setBackgroundColor(desaturatedColor)

        val isLight = ColorUtil.isColorLight(desaturatedColor)
        val primaryColor = if (isLight) Color.BLACK else Color.WHITE
        val secondaryColor = if (isLight) ColorUtil.adjustAlpha(Color.BLACK, 0.7f) else ColorUtil.adjustAlpha(Color.WHITE, 0.7f)

        updateSongTitle(primaryColor, secondaryColor)

        binding.actionNext.setColorFilter(primaryColor)
        binding.actionPrevious.setColorFilter(primaryColor)
        binding.miniPlayerPlayPauseButton.setColorFilter(primaryColor)
        binding.progressBar.setIndicatorColor(primaryColor)
    }

    private fun resetColors() {
        val defaultBackgroundColor = ATHUtil.resolveColor(requireContext(), com.google.android.material.R.attr.colorSurface)
        binding.root.setBackgroundColor(defaultBackgroundColor)
        updateSongTitle()
        
        val defaultIconColor = ATHUtil.resolveColor(requireContext(), androidx.appcompat.R.attr.colorControlNormal)
        binding.actionNext.setColorFilter(defaultIconColor)
        binding.actionPrevious.setColorFilter(defaultIconColor)
        binding.miniPlayerPlayPauseButton.setColorFilter(defaultIconColor)
        binding.progressBar.accentColor()
    }

    override fun onServiceConnected() {
        updateSongTitle()
        updateSongCover()
        updatePlayPauseDrawableState()
    }

    override fun onPlayingMetaChanged() {
        updateSongTitle()
        updateSongCover()
    }

    override fun onPlayStateChanged() {
        updatePlayPauseDrawableState()
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.progressBar.max = total
        binding.progressBar.progress = progress
    }

    override fun onResume() {
        super.onResume()
        progressViewUpdateHelper.start()
    }

    override fun onPause() {
        super.onPause()
        progressViewUpdateHelper.stop()
    }

    protected fun updatePlayPauseDrawableState() {
        if (MusicPlayerRemote.isPlaying) {
            binding.miniPlayerPlayPauseButton.setImageResource(R.drawable.ic_pause)
        } else {
            binding.miniPlayerPlayPauseButton.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    class FlingPlayBackController(context: Context) : View.OnTouchListener {

        private var flingPlayBackController = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (abs(velocityX) > abs(velocityY)) {
                        if (velocityX < 0) {
                            MusicPlayerRemote.playNextSong()
                            return true
                        } else if (velocityX > 0) {
                            MusicPlayerRemote.playPreviousSong()
                            return true
                        }
                    }
                    return false
                }
            })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return flingPlayBackController.onTouchEvent(event)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
