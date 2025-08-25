package code.name.monkey.lost.model

import code.name.monkey.lost.helper.SortOrder
import code.name.monkey.lost.util.MusicUtil
import code.name.monkey.lost.util.PreferenceUtil
import java.text.Collator

data class Artist(
    val id: Long,
    val name: String, // Name is now a direct constructor parameter
    val songs: List<Song>,
    val isAlbumArtist: Boolean = false
) {
    constructor(
        name: String,
        songs: List<Song>,
        isAlbumArtist: Boolean = false
    ) : this(
        // ID generation: song's artistId -> song's id -> hash of artist name
        id = name.hashCode().toLong(),
        name = name,
        songs = songs,
        isAlbumArtist = isAlbumArtist
    )

    // The 'name' property is now directly from the constructor, no complex getter needed here.
    // The responsibility to determine the correct name (e.g., "Various Artists")
    // is now on the code that creates Artist instances.

    val songCount: Int
        get() = songs.size

    val albums: List<Album>
        get() {
            if (songs.isEmpty()) return emptyList()
            return songs.groupBy { it.albumId }
                .map { (albumId, songsInAlbum) ->
                    // val representativeSong = songsInAlbum.first() // No longer needed for title, year etc here
                    Album(
                        id = albumId,
                        songs = songsInAlbum
                    )
                }
        }

    val albumCount: Int
        get() = albums.size


    val sortedSongs: List<Song>
        get() {
            val collator = Collator.getInstance()
            return songs.sortedWith(
                when (PreferenceUtil.artistDetailSongSortOrder) {
                    SortOrder.ArtistSongSortOrder.SONG_A_Z -> { o1, o2 ->
                        collator.compare(o1.title, o2.title)
                    }

                    SortOrder.ArtistSongSortOrder.SONG_Z_A -> { o1, o2 ->
                        collator.compare(o2.title, o1.title)
                    }

                    SortOrder.ArtistSongSortOrder.SONG_ALBUM -> { o1, o2 ->
                        collator.compare(o1.albumName, o2.albumName)
                    }

                    SortOrder.ArtistSongSortOrder.SONG_YEAR -> { o1, o2 ->
                        o2.year.compareTo(o1.year)
                    }

                    SortOrder.ArtistSongSortOrder.SONG_DURATION -> { o1, o2 ->
                        o1.duration.compareTo(o2.duration)
                    }

                    else -> {
                        throw IllegalArgumentException("invalid ${PreferenceUtil.artistDetailSongSortOrder}")
                    }
                })
        }

    val sortedAlbums: List<Album>
        get() {
            val collator = Collator.getInstance()
            return albums.sortedWith( // Uses the derived albums property
                when (PreferenceUtil.artistAlbumSortOrder) {
                    SortOrder.ArtistAlbumSortOrder.ALBUM_A_Z -> { o1, o2 ->
                        collator.compare(o1.title, o2.title)
                    }

                    SortOrder.ArtistAlbumSortOrder.ALBUM_Z_A -> { o1, o2 ->
                        collator.compare(o2.title, o1.title)
                    }

                    SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR_ASC -> { o1, o2 ->
                        o1.year.compareTo(o2.year)
                    }

                    SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR -> { o1, o2 ->
                        o2.year.compareTo(o1.year)
                    }

                    else -> {
                        throw IllegalArgumentException("invalid ${PreferenceUtil.artistAlbumSortOrder}")
                    }
                })
        }

    fun safeGetFirstAlbum(): Album {
        return albums.firstOrNull() ?: Album.empty // Uses the derived albums property
    }

    companion object {
        const val UNKNOWN_ARTIST_DISPLAY_NAME = "Unknown Artist"
        const val VARIOUS_ARTISTS_DISPLAY_NAME = "Various Artists"
        // VARIOUS_ARTISTS_ID might still be useful for code that *creates* Artist instances,
        // to assign a consistent ID to "Various Artists" Artist objects.
        const val VARIOUS_ARTISTS_ID: Long = -2
        val empty = Artist(id = -1L, name = UNKNOWN_ARTIST_DISPLAY_NAME, songs = emptyList(), isAlbumArtist = false)
    }
}
