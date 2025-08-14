package code.name.monkey.lost.fragments.player.card

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.lost.R
import code.name.monkey.lost.databinding.FragmentCardPlayerBinding
import code.name.monkey.lost.extensions.drawAboveSystemBars
import code.name.monkey.lost.extensions.whichFragment
import code.name.monkey.lost.fragments.base.AbsPlayerFragment
import code.name.monkey.lost.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.util.color.MediaNotificationProcessor

class CardFragment : AbsPlayerFragment(R.layout.fragment_card_player) {
    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    private var lastColor: Int = 0
    override val paletteColor: Int
        get() = lastColor

    private lateinit var playbackControlsFragment: CardPlaybackControlsFragment
    private var _binding: FragmentCardPlayerBinding? = null
    private val binding get() = _binding!!


    override fun onShow() {
        playbackControlsFragment.show()
    }

    override fun onHide() {
        playbackControlsFragment.hide()
    }

    override fun toolbarIconColor(): Int {
        return Color.WHITE
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        playbackControlsFragment.setColor(color)
        lastColor = color.primaryTextColor
        libraryViewModel.updateColor(color.primaryTextColor)
        ToolbarContentTintHelper.colorizeToolbar(binding.playerToolbar, Color.WHITE, activity)
    }

    override fun toggleFavorite(song: Song) {
        super.toggleFavorite(song)
        if (song.id == MusicPlayerRemote.currentSong.id) {
            updateIsFavorite()
        }
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCardPlayerBinding.bind(view)
        setUpSubFragments()
        setUpPlayerToolbar()
        (binding.playbackControlsFragment.parent as View).drawAboveSystemBars()
    }

    private fun setUpSubFragments() {
        playbackControlsFragment = whichFragment(R.id.playbackControlsFragment)
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment =
            whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setCallbacks(this)
        playerAlbumCoverFragment.removeSlideEffect()
    }

    private fun setUpPlayerToolbar() {
        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_player)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            setOnMenuItemClickListener(this@CardFragment)

            ToolbarContentTintHelper.colorizeToolbar(this, Color.WHITE, activity)
        }
    }

    override fun onServiceConnected() {
        updateIsFavorite()
    }

    override fun onPlayingMetaChanged() {
        updateIsFavorite()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
