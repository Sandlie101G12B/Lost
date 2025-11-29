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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.collections.emptyList
import kotlin.let

class RealSearchRepository(
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
    private val roomRepository: RoomRepository,
    private val genreRepository: GenreRepository,
) {
    fun searchAll(context: Context, query: String?, filter: Filter): Flow<List<Any>> = channelFlow {
        val results = mutableListOf<Any>()
        if (query.isNullOrEmpty()) {
            send(results)
            return@channelFlow
        }
        val searchString = query

        /** Songs **/
        val songs: List<Song> = if (filter == Filter.SONGS || filter == Filter.NO_FILTER) {
            songRepository.songs(searchString)
        } else {
            emptyList()
        }

        /** Artists **/
        val artists: List<Artist> =
            if (filter == Filter.ARTISTS || filter == Filter.NO_FILTER) {
                artistRepository.artists(searchString)
            } else {
                emptyList()
            }

        /** Albums **/
        val albums: List<Album> = if (filter == Filter.ALBUMS || filter == Filter.NO_FILTER) {
            albumRepository.albums(searchString)
        } else {
            emptyList()
        }

        /** Genres **/
        val genres: List<Genre> = if (filter == Filter.GENRES || filter == Filter.NO_FILTER) {
            genreRepository.genres(query)
        } else {
            emptyList()
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

        if (songs.isNotEmpty()) {
            results.add(context.resources.getString(R.string.songs))
            results.addAll(songs)
        }

        if (artists.isNotEmpty()) {
            results.add(context.resources.getString(R.string.artists))
            results.addAll(artists)
        }

        if (albums.isNotEmpty()) {
            results.add(context.resources.getString(R.string.albums))
            results.addAll(albums)
        }

        if (genres.isNotEmpty()) {
            results.add(context.resources.getString(R.string.genres))
            results.addAll(genres)
        }

        if (playlist.isNotEmpty()) {
            results.add(context.getString(R.string.playlists))
            results.addAll(playlist)
        }

        send(results.toList())

        if ((filter == Filter.SONGS || filter == Filter.NO_FILTER) && InternetConnection.hasInternetConnection(MetaData.getContext())) {
            delay(1000)
            searchVideos(query)
                .onSuccess { searchResults ->
                    val onlineSearch = searchResults.mapNotNull { item ->
                        item.videoId?.let { videoId ->
                            Song(
                                id = videoId.hashCode().toLong(),
                                title = item.title ?: "Unknown Title",
                                trackNumber = 0,
                                year = 0,
                                duration = 0L,
                                data = "https://www.youtube.com/watch?v=$videoId",
                                dateModified = System.currentTimeMillis(),
                                albumId = 0L,
                                albumName = "Online Songs",
                                artistId = (item.author ?: "Unknown Artist").hashCode().toLong(),
                                artistName = item.author ?: "Unknown Artist",
                                composer = null,
                                albumArtist = null,
                                bpm = null,
                                ytID = videoId,
                                isYTSong = true
                            )
                        }
                    }

                    if (onlineSearch.isNotEmpty()) {
                        val newResults = results.toMutableList()
                        if (songs.isNotEmpty()) {
                            val songsHeaderIndex = newResults.indexOf(context.resources.getString(R.string.songs))
                            if (songsHeaderIndex != -1) {
                                newResults.addAll(songsHeaderIndex + 1 + songs.size, onlineSearch)
                            }
                        } else {
                            newResults.add(0, context.resources.getString(R.string.songs))
                            newResults.addAll(1, onlineSearch)
                        }
                        send(newResults)
                    }
                }
        }
    }
}
