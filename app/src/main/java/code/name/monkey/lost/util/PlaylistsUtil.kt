package code.name.monkey.lost.util

import code.name.monkey.lost.db.PlaylistWithSongs
import code.name.monkey.lost.helper.M3UWriter.writeIO
import java.io.File
import java.io.IOException

object PlaylistsUtil {
    @Throws(IOException::class)
    fun savePlaylistWithSongs(playlist: PlaylistWithSongs?): File {
        return writeIO(
            File(getExternalStorageDirectory(), "Playlists"), playlist!!
        )
    }
}