package code.name.monkey.lost.helper

import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.RealSongRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object SearchQueryHelper : KoinComponent {
    private const val TITLE_SELECTION = "lower(" + MediaStore.Audio.AudioColumns.TITLE + ") LIKE ?"
    private const val ALBUM_SELECTION = "lower(" + MediaStore.Audio.AudioColumns.ALBUM + ") LIKE ?"
    private const val ARTIST_SELECTION = "lower(" + MediaStore.Audio.AudioColumns.ARTIST + ") LIKE ?"
    private const val AND = " AND "
    private val songRepository by inject<RealSongRepository>()

    @JvmStatic
    fun getSongs(extras: Bundle): LiveData<List<Song>> = liveData {
        val query = extras.getString(SearchManager.QUERY, null)
        val artistName = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST, null)
        val albumName = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM, null)
        val titleName = extras.getString(MediaStore.EXTRA_MEDIA_TITLE, null)
        val databaseSongs = songRepository.databaseSongs

        val songs = LinkedHashSet<Song>()

        if (artistName != null && albumName != null && titleName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    "$ARTIST_SELECTION$AND$ALBUM_SELECTION$AND$TITLE_SELECTION",
                    arrayOf(
                        "%${artistName.lowercase()}%",
                        "%${albumName.lowercase()}%",
                        "%${titleName.lowercase()}%"
                    )
                )
            )
            songs.addAll(
                getCombinedSongs(
                    local,
                    databaseSongs,
                    artist = artistName,
                    album = albumName,
                    title = titleName
                )
            )
            emit(songs.toList())
        }

        if (artistName != null && titleName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    "$ARTIST_SELECTION$AND$TITLE_SELECTION",
                    arrayOf(
                        "%${artistName.lowercase()}%",
                        "%${titleName.lowercase()}%"
                    )
                )
            )
            songs.addAll(
                getCombinedSongs(
                    local,
                    databaseSongs,
                    artist = artistName,
                    title = titleName
                )
            )
            emit(songs.toList())
        }
        if (albumName != null && titleName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    "$ALBUM_SELECTION$AND$TITLE_SELECTION",
                    arrayOf(
                        "%${albumName.lowercase()}%",
                        "%${titleName.lowercase()}%"
                    )
                )
            )
            songs.addAll(
                getCombinedSongs(
                    local,
                    databaseSongs,
                    album = albumName,
                    title = titleName
                )
            )
            emit(songs.toList())
        }
        if (artistName != null && albumName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    "$ARTIST_SELECTION$AND$ALBUM_SELECTION",
                    arrayOf(
                        "%${artistName.lowercase()}%",
                        "%${albumName.lowercase()}%"
                    )
                )
            )
            songs.addAll(
                getCombinedSongs(
                    local,
                    databaseSongs,
                    artist = artistName,
                    album = albumName
                )
            )
            emit(songs.toList())
        }

        if (artistName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    ARTIST_SELECTION,
                    arrayOf("%${artistName.lowercase()}%")
                )
            )
            songs.addAll(getCombinedSongs(local, databaseSongs, artist = artistName))
            emit(songs.toList())
        }
        if (albumName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    ALBUM_SELECTION,
                    arrayOf("%${albumName.lowercase()}%")
                )
            )
            songs.addAll(getCombinedSongs(local, databaseSongs, album = albumName))
            emit(songs.toList())
        }
        if (titleName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    TITLE_SELECTION,
                    arrayOf("%${titleName.lowercase()}%")
                )
            )
            songs.addAll(getCombinedSongs(local, databaseSongs, title = titleName))
            emit(songs.toList())
        }

        if (query != null) {
            val qStr = query.lowercase()
            val q = "%$qStr%"

            val localArtist = songRepository.songs(
                songRepository.makeSongCursor(
                    ARTIST_SELECTION,
                    arrayOf(q)
                )
            )
            songs.addAll(getCombinedSongs(localArtist, databaseSongs, artist = qStr))
            emit(songs.toList())

            val localAlbum = songRepository.songs(
                songRepository.makeSongCursor(
                    ALBUM_SELECTION,
                    arrayOf(q)
                )
            )
            songs.addAll(getCombinedSongs(localAlbum, databaseSongs, album = qStr))
            emit(songs.toList())

            val localTitle = songRepository.songs(
                songRepository.makeSongCursor(
                    TITLE_SELECTION,
                    arrayOf(q)
                )
            )
            songs.addAll(getCombinedSongs(localTitle, databaseSongs, title = qStr))
            emit(songs.toList())
        }
    }

    private fun getCombinedSongs(
        localSongs: List<Song>,
        databaseSongs: List<Song>,
        artist: String? = null,
        album: String? = null,
        title: String? = null
    ): List<Song> {
        val filteredDbSongs = databaseSongs.filter { song ->
            (artist == null || song.artistName.contains(artist, ignoreCase = true)) &&
                    (album == null || song.albumName.contains(album, ignoreCase = true)) &&
                    (title == null || song.title.contains(title, ignoreCase = true))
        }

        val combined = (localSongs + filteredDbSongs).distinctBy { it.id }

        return combined.sortedByDescending { song ->
            var score = 0
            if (artist != null) {
                if (song.artistName.equals(artist, ignoreCase = true)) score += 2
                if (song.artistName.startsWith(artist, ignoreCase = true)) score += 1
            }
            if (album != null) {
                if (song.albumName.equals(album, ignoreCase = true)) score += 2
                if (song.albumName.startsWith(album, ignoreCase = true)) score += 1
            }
            if (title != null) {
                if (song.title.equals(title, ignoreCase = true)) score += 2
                if (song.title.startsWith(title, ignoreCase = true)) score += 1
            }
            score
        }
    }
}
