package code.name.monkey.lost.helper.menu

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import code.name.monkey.lost.R
import code.name.monkey.lost.dialogs.AddToPlaylistDialog
import code.name.monkey.lost.dialogs.DeleteSongsDialog
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.RealRepository
import code.name.monkey.lost.util.MusicUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

object SongsMenuHelper : KoinComponent {
    fun handleMenuClick(
        activity: FragmentActivity,
        songs: List<Song>,
        menuItemId: Int
    ): Boolean {
        when (menuItemId) {
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(songs)
                return true
            }
            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(songs)
                return true
            }
            R.id.action_add_to_playlist -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val playlists = get<RealRepository>().fetchPlaylists()
                    withContext(Dispatchers.Main) {
                        AddToPlaylistDialog.create(playlists, songs)
                            .show(activity.supportFragmentManager, "ADD_PLAYLIST")
                    }
                }
                return true
            }
            R.id.action_share -> {
                activity.startActivity(
                    Intent.createChooser(
                        MusicUtil.createShareMultipleSongIntent(activity, songs),
                        null
                    )
                )
                return true
            }
            R.id.action_delete_from_device -> {
                DeleteSongsDialog.create(songs)
                    .show(activity.supportFragmentManager, "DELETE_SONGS")
                return true
            }
        }
        return false
    }
}
