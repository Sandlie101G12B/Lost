package code.name.monkey.lost.fragments.playlists

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import code.name.monkey.lost.db.PlaylistWithSongs
import code.name.monkey.lost.db.SongEntity
import code.name.monkey.lost.repository.RealRepository

class PlaylistDetailsViewModel(
    private val realRepository: RealRepository,
    private var playlistId: Long
) : ViewModel() {
    fun getSongs(): LiveData<List<SongEntity>> =
        realRepository.playlistSongs(playlistId)

    fun playlistExists(): LiveData<Boolean> =
        realRepository.checkPlaylistExists(playlistId)

    fun getPlaylist(): LiveData<PlaylistWithSongs> = realRepository.getPlaylist(playlistId)
}
