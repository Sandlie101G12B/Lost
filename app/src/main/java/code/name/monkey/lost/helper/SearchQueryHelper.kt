package code.name.monkey.lost.helper

import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import code.name.monkey.lost.db.LostDatabase
import code.name.monkey.lost.db.toSong
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.RealSongRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

object SearchQueryHelper : KoinComponent {
    private const val TITLE_SELECTION = "lower(" + MediaStore.Audio.AudioColumns.TITLE + ") LIKE ?"
    private const val ALBUM_SELECTION = "lower(" + MediaStore.Audio.AudioColumns.ALBUM + ") LIKE ?"
    private const val ARTIST_SELECTION = "lower(" + MediaStore.Audio.AudioColumns.ARTIST + ") LIKE ?"
    private const val AND = " AND "
    private val songRepository by inject<RealSongRepository>()
    private val database by inject<LostDatabase>()

    @JvmStatic
    fun getSongs(extras: Bundle): LiveData<List<Song>> = liveData {
        val query = extras.getString(SearchManager.QUERY, null)
        val artistName = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST, null)
        val albumName = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM, null)
        val titleName = extras.getString(MediaStore.EXTRA_MEDIA_TITLE, null)
        
//        val databaseSongs = runBlocking {
//            database.songsDao().searchSongs(query).first().map {
//                it.toSong()
//            }
//        }

        val databaseSongs = emptyList<Song>()

        Timber.tag("YTPlayerUtils").d("getSongs: query=$query, artistName=$artistName, albumName=$albumName, titleName=$titleName")
        Timber.tag("YTPlayerUtils").d("getSongs: databaseSongs=$databaseSongs")

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
        }

        if (artistName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    ARTIST_SELECTION,
                    arrayOf("%${artistName.lowercase()}%")
                )
            )
            songs.addAll(getCombinedSongs(local, databaseSongs, artist = artistName))
        }
        if (albumName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    ALBUM_SELECTION,
                    arrayOf("%${albumName.lowercase()}%")
                )
            )
            songs.addAll(getCombinedSongs(local, databaseSongs, album = albumName))
        }
        if (titleName != null) {
            val local = songRepository.songs(
                songRepository.makeSongCursor(
                    TITLE_SELECTION,
                    arrayOf("%${titleName.lowercase()}%")
                )
            )
            songs.addAll(getCombinedSongs(local, databaseSongs, title = titleName))
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
            val localAlbum = songRepository.songs(
                songRepository.makeSongCursor(
                    ALBUM_SELECTION,
                    arrayOf(q)
                )
            )
            songs.addAll(getCombinedSongs(localAlbum, databaseSongs, album = qStr))
            val localTitle = songRepository.songs(
                songRepository.makeSongCursor(
                    TITLE_SELECTION,
                    arrayOf(q)
                )
            )
            songs.addAll(getCombinedSongs(localTitle, databaseSongs, title = qStr))
        }
        emit(songs.toList())
    }

    private fun getCombinedSongs(
        localSongs: List<Song>,
        databaseSongs: List<Song>,
        artist: String? = null,
        album: String? = null,
        title: String? = null
    ): List<Song> {
        val combined = (localSongs + databaseSongs).distinctBy { it.id }
        return combined.sortedByDescending { song ->
            var score = 0
            if (artist != null) {
                if (song.artistName.equals(artist, ignoreCase = true)) score += 2
                else if (song.artistName.startsWith(artist, ignoreCase = true)) score += 1
            }
            if (album != null) {
                if (song.albumName.equals(album, ignoreCase = true)) score += 2
                else if (song.albumName.startsWith(album, ignoreCase = true)) score += 1
            }
            if (title != null) {
                if (song.title.equals(title, ignoreCase = true)) score += 2
                else if (song.title.startsWith(title, ignoreCase = true)) score += 1
            }
            score
        }
    }
}
