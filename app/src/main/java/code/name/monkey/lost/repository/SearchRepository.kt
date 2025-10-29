package code.name.monkey.lost.repository

import android.content.Context
import code.name.monkey.lost.R
import code.name.monkey.lost.db.PlaylistWithSongs
import code.name.monkey.lost.fragments.search.Filter
import code.name.monkey.lost.helper.MetaData
import code.name.monkey.lost.model.Album
import code.name.monkey.lost.model.Artist
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.network.InternetConnection
import code.name.monkey.lost.util.YTPlayerUtils.searchVideos
import code.name.monkey.lost.util.YouTubeSearchItem
import kotlinx.coroutines.runBlocking
import kotlin.collections.emptyList
import kotlin.let

class RealSearchRepository(
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
    private val roomRepository: RoomRepository,
    private val genreRepository: GenreRepository,
) {
    suspend fun searchAll(context: Context, query: String?, filter: Filter): MutableList<Any> {
        val results = mutableListOf<Any>()
        if (query.isNullOrEmpty()) return results
        query.let { searchString ->

            /** Songs **/
            val songs: List<Song> = if (filter == Filter.SONGS || filter == Filter.NO_FILTER) {
                songRepository.songs(searchString)
            } else {
                emptyList()
            }

            val onlineSearch: List<Song> = if ((filter == Filter.SONGS || filter == Filter.NO_FILTER) && InternetConnection.hasInternetConnection(MetaData.getContext())) {
                runBlocking {
                    searchVideos(query)
                        .fold(
                            onSuccess = { searchResults: List<YouTubeSearchItem> -> // searchResults is List<YouTubeSearchItem>
                                searchResults.mapNotNull { item ->
                                    item.videoId?.let { videoId ->
                                        Song(
                                            id = videoId.hashCode().toLong(), // Using videoId's hashcode as a placeholder ID
                                            title = item.title ?: "Unknown Title",
                                            trackNumber = 0, // Default value
                                            year = 0, // Default value
                                            duration = 0L, // TODO: Parse item.duration (String) to Long (milliseconds) correctly
                                            data = "https://www.youtube.com/watch?v=$videoId", // YouTube URL as data
                                            dateModified = System.currentTimeMillis(), // Current time for dateModified
                                            albumId = 0L, // Default value
                                            albumName = "Online Songs", // Default album name for online searches
                                            artistId = (item.author ?: "Unknown Artist").hashCode().toLong(), // Placeholder artist ID
                                            artistName = item.author ?: "Unknown Artist",
                                            composer = null, // No composer info from YouTubeSearchItem
                                            albumArtist = null, // No album artist info
                                            bpm = null, // No BPM info
                                            ytID = videoId,
                                            isYTSong = true
                                        )
                                    }
                                }
                            },
                            onFailure = { _ -> // Can log exception if needed
                                emptyList()
                            }
                        )
                }
            } else {
                emptyList()
            }
            if (songs.isNotEmpty() || onlineSearch.isNotEmpty()) {
                results.add(context.resources.getString(R.string.songs))
                if (songs.isNotEmpty()) {
                    results.addAll(songs)
                }
                if (onlineSearch.isNotEmpty()) {
                    results.addAll(onlineSearch) // Corrected: add onlineSearch results
                }
            }
            
            /** Artists **/
            val artists: List<Artist> =
                if (filter == Filter.ARTISTS || filter == Filter.NO_FILTER) {
                    artistRepository.artists(searchString)
                } else {
                    emptyList()
                }
            if (artists.isNotEmpty()) {
                results.add(context.resources.getString(R.string.artists))
                results.addAll(artists)
            }

            /** Albums **/
            val albums: List<Album> = if (filter == Filter.ALBUMS || filter == Filter.NO_FILTER) {
                albumRepository.albums(searchString)
            } else {
                emptyList()
            }
            if (albums.isNotEmpty()) {
                results.add(context.resources.getString(R.string.albums))
                results.addAll(albums)
            }

            /** Genres **/
            val genres: List<Genre> = if (filter == Filter.GENRES || filter == Filter.NO_FILTER) {
                genreRepository.genres(query)
            } else {
                emptyList()
            }
            if (genres.isNotEmpty()) {
                results.add(context.resources.getString(R.string.genres))
                results.addAll(genres)
            }

            /** Playlists **/
            val playlist: List<PlaylistWithSongs> =
                if (filter == Filter.PLAYLISTS || filter == Filter.NO_FILTER) {
                    roomRepository.playlistWithSongs().filter { playlist ->
                        playlist.playlistEntity.playlistName.lowercase().contains(searchString.lowercase())
                    }
                } else {
                    emptyList()
                }

            if (playlist.isNotEmpty()) {
                results.add(context.getString(R.string.playlists))
                results.addAll(playlist)
            }
        }
        return results
    }
}
