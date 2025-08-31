package code.name.monkey.lost.model

import android.content.Context
import code.name.monkey.lost.util.MusicUtil
import kotlinx.parcelize.Parcelize

@Parcelize
class AutomaticPlaylist(
    val aName: String, // 'name' is already a val in the parent 'Playlist'
    private val songsList: List<Song>
) : Playlist(nameToId(aName), aName) { // Use aName for both id and name

    companion object {
        fun nameToId(name: String): Long {
            // Using hashCode for ID. Ensure this is acceptable for your use case.
            // It might lead to collisions if names aren't unique enough or if
            // these IDs are mixed with MediaStore IDs that might have similar values.
            // Consider a more robust ID generation if needed, e.g., prefixing.
            return name.hashCode().toLong()
        }
    }

    override fun getSongs(): List<Song> {
        return songsList
    }

    override fun getInfoString(context: Context): String {
        val songCount = songsList.size // Use songsList directly
        val songCountString = MusicUtil.getSongCountString(context, songCount)
        return MusicUtil.buildInfoString(
            songCountString,
            ""
        )
    }
}