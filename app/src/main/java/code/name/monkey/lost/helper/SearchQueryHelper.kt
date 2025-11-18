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

        val songs = LinkedHashSet<Song>()

        if (artistName != null && albumName != null && titleName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    "$ARTIST_SELECTION$AND$ALBUM_SELECTION$AND$TITLE_SELECTION",
                    arrayOf(
                        "%${artistName.lowercase()}%",
                        "%${albumName.lowercase()}%",
                        "%${titleName.lowercase()}%"
                    )
                )
            ))
            emit(songs.toList())
        }

        if (artistName != null && titleName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    "$ARTIST_SELECTION$AND$TITLE_SELECTION",
                    arrayOf(
                        "%${artistName.lowercase()}%",
                        "%${titleName.lowercase()}%"
                    )
                )
            ))
            emit(songs.toList())
        }
        if (albumName != null && titleName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    "$ALBUM_SELECTION$AND$TITLE_SELECTION",
                    arrayOf(
                        "%${albumName.lowercase()}%",
                        "%${titleName.lowercase()}%"
                    )
                )
            ))
            emit(songs.toList())
        }
        if (artistName != null && albumName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    "$ARTIST_SELECTION$AND$ALBUM_SELECTION",
                    arrayOf(
                        "%${artistName.lowercase()}%",
                        "%${albumName.lowercase()}%"
                    )
                )
            ))
            emit(songs.toList())
        }

        if (artistName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    ARTIST_SELECTION,
                    arrayOf("%${artistName.lowercase()}%")
                )
            ))
            emit(songs.toList())
        }
        if (albumName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    ALBUM_SELECTION,
                    arrayOf("%${albumName.lowercase()}%")
                )
            ))
            emit(songs.toList())
        }
        if (titleName != null) {
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    TITLE_SELECTION,
                    arrayOf("%${titleName.lowercase()}%")
                )
            ))
            emit(songs.toList())
        }

        if (query != null) {
            val q = "%${query.lowercase()}%"
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    ARTIST_SELECTION,
                    arrayOf(q)
                )
            ))
            emit(songs.toList())
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    ALBUM_SELECTION,
                    arrayOf(q)
                )
            ))
            emit(songs.toList())
            songs.addAll(songRepository.songs(
                songRepository.makeSongCursor(
                    TITLE_SELECTION,
                    arrayOf(q)
                )
            ))
            emit(songs.toList())
        }
    }
}