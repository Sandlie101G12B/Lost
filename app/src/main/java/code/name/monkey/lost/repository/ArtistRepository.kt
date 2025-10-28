package code.name.monkey.lost.repository

import android.provider.MediaStore.Audio.AudioColumns
import code.name.monkey.lost.helper.SortOrder
import code.name.monkey.lost.model.Album
import code.name.monkey.lost.model.Artist
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.util.PreferenceUtil
import java.text.Collator

interface ArtistRepository {
    fun artists(): List<Artist>
    fun albumArtists(): List<Artist>
    fun albumArtists(query: String): List<Artist>
    fun artists(query: String): List<Artist>
    fun artist(artistId: Long): Artist
    fun albumArtist(artistName: String): Artist
    // Added new overloaded function
   fun splitIntoArtists(songs: List<Song>, isAlbumArtists: Boolean = true): List<Artist>
}

class RealArtistRepository(
    private val songRepository: RealSongRepository
) : ArtistRepository {

    private fun getSongLoaderSortOrder(): String {
        return PreferenceUtil.artistSortOrder + ", " +
                PreferenceUtil.artistAlbumSortOrder + ", " +
                PreferenceUtil.artistSongSortOrder
    }

    override fun splitIntoArtists(
        songs: List<Song>,
        isAlbumArtists: Boolean
    ): List<Artist> {
        val artistSongsMap = mutableMapOf<String, MutableList<Song>>()

        songs.forEach { song ->
            val namesFromSong: List<String> = song.artistNames.filter { it.isNotBlank() }

            if (namesFromSong.isNotEmpty()) {
                namesFromSong.forEach { artistName ->
                    artistSongsMap.getOrPut(artistName.trim()) { mutableListOf() }.add(song)
                }
            } else {
                val artistNameString: String? = song.artistName // Use the original artistName
                if (!artistNameString.isNullOrBlank()) {
                    // Split by ",", "/", or "&", then trim each part
                    val individualArtistNames = artistNameString.split(*charArrayOf(',', '/', '&'))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                    if (individualArtistNames.isNotEmpty()) {
                        individualArtistNames.forEach { parsedName ->
                            artistSongsMap.getOrPut(parsedName) { mutableListOf() }.add(song)
                        }
                    }
                }
            }
        }

        return artistSongsMap.mapNotNull { (artistName, songsForArtist) ->
            if (songsForArtist.isNotEmpty()) {
                if (artistName.equals(Artist.VARIOUS_ARTISTS_DISPLAY_NAME, ignoreCase = true)) {
                    Artist(
                        Artist.VARIOUS_ARTISTS_ID,
                        Artist.VARIOUS_ARTISTS_DISPLAY_NAME, // Use the canonical display name
                        songsForArtist,
                        isAlbumArtists
                    )
                } else {
                    // Assumes Artist constructor derives ID from name, e.g., name.hashCode().toLong()
                    // And has a signature like: Artist(name: String, songs: List<Song>, isAlbumArtist: Boolean)
                    Artist(artistName, songsForArtist, isAlbumArtists)
                }
            } else {
                null // Should not happen if map is populated correctly
            }
        }
    }

    override fun artist(artistId: Long): Artist {
        val allSongs = songRepository.songs(
            songRepository.makeSongCursor(null, null, getSongLoaderSortOrder())
        )

        if (artistId == Artist.VARIOUS_ARTISTS_ID) {
            val variousArtistSongs = allSongs.filter { song ->
                song.artistNames.any { it.equals(Artist.VARIOUS_ARTISTS_DISPLAY_NAME, ignoreCase = true) } ||
                        song.artistName.equals(Artist.VARIOUS_ARTISTS_DISPLAY_NAME, ignoreCase = true)
            }
            return Artist(
                Artist.VARIOUS_ARTISTS_ID,
                Artist.VARIOUS_ARTISTS_DISPLAY_NAME,
                variousArtistSongs,
                false
            )
        }

        // Assumes artistId is name.hashCode().toLong() for non-Various Artists
        val artistsList = splitIntoArtists(allSongs, false)
        return artistsList.find { it.id == artistId } ?: Artist.empty
    }

    override fun albumArtist(artistName: String): Artist {
        val allSongs = songRepository.songs(
            songRepository.makeSongCursor(null, null, getSongLoaderSortOrder())
        )

        if (artistName.equals(Artist.VARIOUS_ARTISTS_DISPLAY_NAME, ignoreCase = true)) {
            val variousArtistSongs = allSongs.filter { song ->
                song.artistNames.any { it.equals(Artist.VARIOUS_ARTISTS_DISPLAY_NAME, ignoreCase = true) } ||
                        song.artistName.equals(Artist.VARIOUS_ARTISTS_DISPLAY_NAME, ignoreCase = true)
            }
            return Artist(
                Artist.VARIOUS_ARTISTS_ID,
                Artist.VARIOUS_ARTISTS_DISPLAY_NAME,
                variousArtistSongs,
                true
            )
        }

        val albumArtistsList = splitIntoArtists(allSongs, true)
        return albumArtistsList.find { it.name.equals(artistName, ignoreCase = true) } ?: Artist.empty
    }

    fun splitAlbumsIntoArtists(
        albums: List<Album>,
        isAlbumArtists: Boolean = false
    ): List<Artist> {
        val songsFromAlbums = albums.flatMap { it.songs }
        return splitIntoArtists(songsFromAlbums, isAlbumArtists)
    }

    override fun artists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, getSongLoaderSortOrder())
        )
        val artists = splitIntoArtists(songs, false)
        return sortArtists(artists)
    }

    override fun albumArtists(): List<Artist> {
        // Fetch all songs. The concept of "album artist" is now derived from song.artistNames
        // and the isAlbumArtist flag, not a specific database field for initial song selection.
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, getSongLoaderSortOrder())
        )
        val artists = splitIntoArtists(songs, true)
        return sortArtists(artists) // Sort based on the artist names derived
    }

    override fun albumArtists(query: String): List<Artist> {
        // Get all album artists and then filter in Kotlin.
        // This avoids relying on a potentially corrupt "album_artist" field for DB querying.
        val allAlbumArtists = albumArtists() // This already calls splitIntoArtists with isAlbumArtists = true
        return allAlbumArtists.filter {
            it.name.contains(query, ignoreCase = true)
        }
    }

    override fun artists(query: String): List<Artist> {
        // Option 1: Continue using AudioColumns.ARTIST for initial DB filtering if it's somewhat reliable.
        // The actual artist objects and names will still be derived from song.artistNames.
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                AudioColumns.ARTIST + " LIKE ?",
                arrayOf("%$query%"),
                getSongLoaderSortOrder()
            )
        )
        val artists = splitIntoArtists(songs, false)
        return sortArtists(artists)

        // Option 2: If AudioColumns.ARTIST is also unreliable, fetch all and filter in Kotlin (more robust, potentially slower)
        // val allArtists = artists() // This calls splitIntoArtists with isAlbumArtists = false
        // return allArtists.filter {
        //    it.name.contains(query, ignoreCase = true)
        // }
    }

    private fun sortArtists(artists: List<Artist>): List<Artist> {
        val collator = Collator.getInstance()
        return when (PreferenceUtil.artistSortOrder) {
            SortOrder.ArtistSortOrder.ARTIST_A_Z -> {
                artists.sortedWith { a1, a2 -> collator.compare(a1.name, a2.name) }
            }
            SortOrder.ArtistSortOrder.ARTIST_Z_A -> {
                artists.sortedWith { a1, a2 -> collator.compare(a2.name, a1.name) }
            }
            else -> artists // Includes SortOrder.ArtistSortOrder.NONE or any other unhandled cases
        }
    }
}
