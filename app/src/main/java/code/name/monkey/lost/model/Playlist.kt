package code.name.monkey.lost.model

import android.content.Context
import android.os.Parcelable
import code.name.monkey.lost.repository.PlaylistRepository // Changed from RealPlaylistRepository
import code.name.monkey.lost.util.MusicUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject // Added for DI

@Parcelize
open class Playlist(
    val id: Long,
    val name: String
) : Parcelable, KoinComponent {

    // Inject PlaylistRepository instead of creating RealPlaylistRepository manually
    @IgnoredOnParcel
    private val playlistRepository: PlaylistRepository by inject()

    companion object {
        val empty = Playlist(-1, "")
    }

    // this default implementation covers static playlists
    open fun getSongs(): List<Song> {
        // Use runBlocking to call the suspend function from a non-suspend context
        return runBlocking(Dispatchers.IO) {
            playlistRepository.playlistSongs(id)
        }
    }

    open fun getInfoString(context: Context): String {
        // If getSongs() is called here, it will now correctly use runBlocking
        val songCount = getSongs().size
        val songCountString = MusicUtil.getSongCountString(context, songCount)
        return MusicUtil.buildInfoString(
            songCountString,
            ""
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Playlist

        if (id != other.id) return false
        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}
