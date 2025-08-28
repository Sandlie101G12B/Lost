package code.name.monkey.lost.helper.menu

import android.view.MenuItem
import androidx.fragment.app.FragmentActivity
import code.name.monkey.lost.R
import code.name.monkey.lost.dialogs.AddToPlaylistDialog
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.GenreRepository
import code.name.monkey.lost.repository.RealRepository // Assuming RealRepository is a Koin component
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

object GenreMenuHelper : KoinComponent {
    private val genreRepository by inject<GenreRepository>()

    // MODIFIED: getGenreSongs is now a suspend function
    private suspend fun getGenreSongs(genre: Genre): List<Song> {
        // Calls the suspend function from the repository directly
        return genreRepository.songs(genre.id)
    }

    fun handleMenuClick(activity: FragmentActivity, genre: Genre, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_play -> {
                // MUST launch a coroutine to call a suspend function
                CoroutineScope(Dispatchers.IO).launch {
                    val songs = getGenreSongs(genre) // Call suspend fun
                    // Assuming MusicPlayerRemote.openQueue is safe from IO thread
                    // or handles its own threading for UI updates if needed.
                    MusicPlayerRemote.openQueue(songs, 0, true)
                }
                return true
            }
            R.id.action_play_next -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val songs = getGenreSongs(genre) // Call suspend fun
                    MusicPlayerRemote.playNext(songs)
                }
                return true
            }
            R.id.action_add_to_playlist -> {
                CoroutineScope(Dispatchers.IO).launch {
                    // Assuming get<RealRepository>().fetchPlaylists() is suspend
                    // or handles its own background work correctly.
                    val playlists = get<RealRepository>().fetchPlaylists()
                    val songs = getGenreSongs(genre) // Call suspend fun
                    withContext(Dispatchers.Main) { // Switch to Main thread for UI Dialog
                        AddToPlaylistDialog.create(playlists, songs)
                            .show(activity.supportFragmentManager, "ADD_PLAYLIST")
                    }
                }
                return true
            }
            R.id.action_add_to_current_playing -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val songs = getGenreSongs(genre) // Call suspend fun
                    MusicPlayerRemote.enqueue(songs)
                }
                return true
            }
        }
        return false
    }
}
