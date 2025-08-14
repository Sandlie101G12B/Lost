package code.name.monkey.lost.interfaces

import android.view.View
import code.name.monkey.lost.db.PlaylistWithSongs

interface IPlaylistClickListener {
    fun onPlaylistClick(playlistWithSongs: PlaylistWithSongs, view: View)
}