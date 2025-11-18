package code.name.monkey.lost.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import code.name.monkey.lost.FAVOURITES
import code.name.monkey.lost.GENRES
import code.name.monkey.lost.PLAYLISTS
import code.name.monkey.lost.R
import code.name.monkey.lost.TOP_ARTISTS
import code.name.monkey.lost.db.HistoryEntity
import code.name.monkey.lost.db.PlayCountEntity
import code.name.monkey.lost.db.PlaylistEntity
import code.name.monkey.lost.db.PlaylistWithSongs
import code.name.monkey.lost.db.SimilarSongEntity
import code.name.monkey.lost.db.SongEntity
import code.name.monkey.lost.db.fromHistoryToSongs
import code.name.monkey.lost.db.toSong
import code.name.monkey.lost.fragments.search.Filter
import code.name.monkey.lost.helper.MetaData
import code.name.monkey.lost.model.AbsCustomPlaylist
import code.name.monkey.lost.model.Album
import code.name.monkey.lost.model.Artist
import code.name.monkey.lost.model.Contributor
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.model.Home
import code.name.monkey.lost.model.Playlist
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.model.smartplaylist.NotPlayedPlaylist
import code.name.monkey.lost.network.LastFMService
import code.name.monkey.lost.network.Result
import code.name.monkey.lost.network.Result.Error
import code.name.monkey.lost.network.Result.Success
import code.name.monkey.lost.network.model.LastFmAlbum
import code.name.monkey.lost.network.model.LastFmArtist
import code.name.monkey.lost.util.logE
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import kotlin.random.Random

interface Repository {

    fun historySong(): List<HistoryEntity>
    fun favorites(): LiveData<List<SongEntity>>
    fun observableHistorySongs(): LiveData<List<Song>>
    fun albumById(albumId: Long): Album
    fun playlistSongs(playListId: Long): LiveData<List<SongEntity>>
    suspend fun fetchAlbums(): List<Album>
    suspend fun albumByIdAsync(albumId: Long): Album
    suspend fun allSongs(): List<Song>
    suspend fun fetchArtists(): List<Artist>
    suspend fun albumArtists(): List<Artist>
    suspend fun fetchLegacyPlaylist(): List<Playlist>
    suspend fun fetchGenres(): List<Genre>
    suspend fun search(query: String?, filter: Filter): MutableList<Any>
    suspend fun getPlaylistSongs(playlist: Playlist): List<Song>
    suspend fun getGenre(genreId: Long): List<Song>
    suspend fun artistInfo(name: String, lang: String?, cache: String?): Result<LastFmArtist>
    suspend fun albumInfo(artist: String, album: String): Result<LastFmAlbum>
    suspend fun artistById(artistId: Long): Artist
    suspend fun albumArtistByName(name: String): Artist
    suspend fun recentArtists(): List<Artist>
    suspend fun topArtists(): List<Artist>
    suspend fun topAlbums(): List<Album>
    suspend fun recentAlbums(): List<Album>
    suspend fun topArtistsHome(): Home
    suspend fun favoritePlaylistHome(): Home
    suspend fun suggestions(): List<Song>
    suspend fun genresHome(): Home
    suspend fun playlists(): Home
    suspend fun homeSections(): List<Home>
    suspend fun playlist(playlistId: Long): Playlist
    suspend fun fetchPlaylistWithSongs(): List<PlaylistWithSongs>
    suspend fun playlistSongs(playlistWithSongs: PlaylistWithSongs): List<Song>
    suspend fun insertSongs(songs: List<SongEntity>)
    suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity>
    suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long
    suspend fun fetchPlaylists(): List<PlaylistEntity>
    suspend fun deleteRoomPlaylist(playlists: List<PlaylistEntity>)
    suspend fun renameRoomPlaylist(playlistId: Long, name: String)
    suspend fun deleteSongsInPlaylist(songs: List<SongEntity>)
    suspend fun removeSongFromPlaylist(songEntity: SongEntity)
    suspend fun deletePlaylistSongs(playlists: List<PlaylistEntity>)
    suspend fun favoritePlaylist(): PlaylistEntity
    suspend fun isFavoriteSong(songEntity: SongEntity): List<SongEntity>
    suspend fun upsertSongInHistory(currentSong: Song)
    suspend fun favoritePlaylistSongs(): List<SongEntity>
    suspend fun recentSongs(): List<Song>
    suspend fun topPlayedSongs(): List<Song>
    suspend fun upsertSongInPlayCount(playCountEntity: PlayCountEntity)
    suspend fun deleteSongInPlayCount(playCountEntity: PlayCountEntity)
    suspend fun deleteSongInHistory(songId: Long)
    suspend fun clearSongHistory()
    suspend fun findSongExistInPlayCount(songId: Long): PlayCountEntity?
    suspend fun playCountSongs(): List<PlayCountEntity>
    suspend fun deleteSongs(songs: List<Song>)
    suspend fun contributor(): List<Contributor>
    suspend fun searchArtists(query: String): List<Artist>
    suspend fun searchSongs(query: String): List<Song>
    suspend fun searchAlbums(query: String): List<Album>
    suspend fun isSongFavorite(songId: Long): Boolean
    fun getSongByGenre(genreId: Long): Song
    fun checkPlaylistExists(playListId: Long): LiveData<Boolean>
    fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs>
    suspend fun getSongsForTaste(limit: Int): List<Song>
    fun newSongs(): List<Song>
    fun getTrendingYouTubeSongs(needed: Int): List<Song>

    // Similar Songs
    suspend fun addSimilarSong(originalSongId: Long, similarSong: Song)
    fun getSimilarSongs(originalSongId: Long): LiveData<List<Song>>
    suspend fun getSimilarSongsList(originalSongId: Long): List<Song>
    suspend fun removeSimilarSong(originalSongId: Long, similarSongId: Long)
    suspend fun clearSimilarSongsForOriginal(originalSongId: Long)
    suspend fun isSongSimilar(originalSongId: Long, potentialSimilarSongId: Long): Boolean
}

class RealRepository(
    private val context: Context,
    private val lastFMService: LastFMService,
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
    private val genreRepository: GenreRepository,
    private val lastAddedRepository: LastAddedRepository,
    private val playlistRepository: PlaylistRepository,
    private val searchRepository: RealSearchRepository,
    private val topPlayedRepository: TopPlayedRepository,
    private val roomRepository: RoomRepository,
    private val localDataRepository: LocalDataRepository,
) : Repository {

    // Helper to map SimilarSongEntity to Song (you should create a proper extension function)
    private fun SimilarSongEntity.toSong(): Song {
        return Song(
            id = this.songId,
            title = this.title,
            trackNumber = this.trackNumber,
            year = this.year,
            duration = this.duration,
            data = this.data,
            dateModified = this.dateModified,
            albumId = this.albumId,
            albumName = this.albumName,
            artistId = this.artistId,
            artistName = this.artistName,
            composer = this.composer,
            albumArtist = this.albumArtist
            // Add any other fields from Song model not in SimilarSongEntity directly (e.g., from your ESong.kt)
        )
    }

    // Helper to map Song to SimilarSongEntity (you should create a proper extension function)
    private fun Song.toSimilarSongEntity(originalId: Long): SimilarSongEntity {
        return SimilarSongEntity(
            originalSongId = originalId,
            songId = this.id,
            title = this.title,
            trackNumber = this.trackNumber,
            year = this.year,
            duration = this.duration,
            data = this.data,
            dateModified = this.dateModified,
            albumId = this.albumId,
            albumName = this.albumName,
            artistId = this.artistId,
            artistName = this.artistName,
            composer = this.composer,
            albumArtist = this.albumArtist
        )
    }

    override suspend fun deleteSongs(songs: List<Song>) = roomRepository.deleteSongs(songs)

    override suspend fun contributor(): List<Contributor> = localDataRepository.contributors()

    override suspend fun searchSongs(query: String): List<Song> = songRepository.songs(query)

    override suspend fun searchAlbums(query: String): List<Album> = albumRepository.albums(query)

    override suspend fun isSongFavorite(songId: Long): Boolean =
        roomRepository.isSongFavorite(context, songId)

    override fun getSongByGenre(genreId: Long): Song = genreRepository.song(genreId)

    override suspend fun searchArtists(query: String): List<Artist> =
        artistRepository.artists(query)

    override suspend fun fetchAlbums(): List<Album> = albumRepository.albums()

    override suspend fun albumByIdAsync(albumId: Long): Album = albumRepository.album(albumId)

    override fun albumById(albumId: Long): Album = albumRepository.album(albumId)

    override suspend fun fetchArtists(): List<Artist> = artistRepository.artists()

    override suspend fun albumArtists(): List<Artist> = artistRepository.albumArtists()

    override suspend fun artistById(artistId: Long): Artist = artistRepository.artist(artistId)

    override suspend fun albumArtistByName(name: String): Artist =
        artistRepository.albumArtist(name)

    override suspend fun recentArtists(): List<Artist> = lastAddedRepository.recentArtists()

    override suspend fun recentAlbums(): List<Album> = lastAddedRepository.recentAlbums()

    override suspend fun topArtists(): List<Artist> = topPlayedRepository.topArtists()

    override suspend fun topAlbums(): List<Album> = topPlayedRepository.topAlbums()

    override suspend fun fetchLegacyPlaylist(): List<Playlist> = playlistRepository.playlists()

    override suspend fun fetchGenres(): List<Genre> = genreRepository.genres()

    override suspend fun allSongs(): List<Song> = songRepository.songs()

    override suspend fun search(query: String?, filter: Filter): MutableList<Any> =
        searchRepository.searchAll(context, query, filter)

    override suspend fun getPlaylistSongs(playlist: Playlist): List<Song> =
        if (playlist is AbsCustomPlaylist) {
            playlist.songs()
        } else {
            PlaylistSongsLoader.getPlaylistSongList(context, playlist.id)
        }

    override suspend fun getGenre(genreId: Long): List<Song> = genreRepository.songs(genreId)

    override suspend fun artistInfo(
        name: String,
        lang: String?,
        cache: String?,
    ): Result<LastFmArtist> {
        return try {
            Success(lastFMService.artistInfo(name, lang, cache))
        } catch (e: Exception) {
            logE(e)
            Error(e)
        }
    }

    override suspend fun albumInfo(
        artist: String,
        album: String,
    ): Result<LastFmAlbum> {
        return try {
            val lastFmAlbum = lastFMService.albumInfo(artist, album)
            Success(lastFmAlbum)
        } catch (e: Exception) {
            logE(e)
            Error(e)
        }
    }

    override suspend fun homeSections(): List<Home> {
        val homeSections = mutableListOf<Home>()
        val sections: List<Home> = listOf(
            topArtistsHome(),
            favoritePlaylistHome()
        )
        for (section in sections) {
            if (section.arrayList.isNotEmpty()) {
                homeSections.add(section)
            }
        }
        return homeSections
    }


    override suspend fun playlist(playlistId: Long) =
        playlistRepository.playlist(playlistId)

    override suspend fun fetchPlaylistWithSongs(): List<PlaylistWithSongs> =
        roomRepository.playlistWithSongs()

    override fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs> = roomRepository.getPlaylist(playlistId)

    override suspend fun playlistSongs(playlistWithSongs: PlaylistWithSongs): List<Song> =
        playlistWithSongs.songs.map {
            it.toSong() // Assuming SongEntity.toSong() extension exists
        }

    override fun playlistSongs(playListId: Long): LiveData<List<SongEntity>> =
        roomRepository.getSongs(playListId)

    override suspend fun insertSongs(songs: List<SongEntity>) =
        roomRepository.insertSongs(songs)

    override suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity> =
        roomRepository.checkPlaylistExists(playlistName)

    override fun checkPlaylistExists(playListId: Long): LiveData<Boolean> =
        roomRepository.checkPlaylistExists(playListId)

    override suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long =
        roomRepository.createPlaylist(playlistEntity)

    override suspend fun fetchPlaylists(): List<PlaylistEntity> = roomRepository.playlists()

    override suspend fun deleteRoomPlaylist(playlists: List<PlaylistEntity>) =
        roomRepository.deletePlaylistEntities(playlists)

    override suspend fun renameRoomPlaylist(playlistId: Long, name: String) =
        roomRepository.renamePlaylistEntity(playlistId, name)

    override suspend fun deleteSongsInPlaylist(songs: List<SongEntity>) =
        roomRepository.deleteSongsInPlaylist(songs)

    override suspend fun removeSongFromPlaylist(songEntity: SongEntity) =
        roomRepository.removeSongFromPlaylist(songEntity)

    override suspend fun deletePlaylistSongs(playlists: List<PlaylistEntity>) =
        roomRepository.deletePlaylistSongs(playlists)

    override suspend fun favoritePlaylist(): PlaylistEntity =
        roomRepository.favoritePlaylist(context.getString(R.string.favorites))

    override suspend fun isFavoriteSong(songEntity: SongEntity): List<SongEntity> =
        roomRepository.isFavoriteSong(songEntity)

    override suspend fun upsertSongInHistory(currentSong: Song) = try{
        roomRepository.upsertSongInHistory(currentSong)
    }catch (e: Exception){
        roomRepository.upsertSongInHistory(currentSong)
    }
    override suspend fun favoritePlaylistSongs(): List<SongEntity> =
        roomRepository.favoritePlaylistSongs(context.getString(R.string.favorites))

    override suspend fun recentSongs(): List<Song> = lastAddedRepository.recentSongs()

    override suspend fun topPlayedSongs(): List<Song> = topPlayedRepository.topTracks()

    // Data class to hold our derived taste profile
    data class UserTasteProfile(
        val prominentGenres: Map<String, Int>, // Genre -> Count
        val averageEnergy: Double?,
        val averageDanceability: Double?,
        val averageTempo: Double?,
        val prominentArtistNames: List<String> // Still useful for a direct boost
    )

    // Data class to hold a song and its similarity score
    data class ScoredSong(
        val song: Song,
        val score: Double
    )

    override suspend fun getSongsForTaste(limit: Int): List<Song> = try {
        val numSeedSongsToAnalyze = 10
        val numProminentArtistsToPick = 3

        val allSongMetaDataMap = MetaData.getSongMetaDataList()
            .filter { it.file.isNotBlank() }
            .associateBy { it.file }
        println("Fetched ${allSongMetaDataMap.size} valid metadata entries.")

        val topPlayedSeedSongs = topPlayedRepository.topTracks().take(numSeedSongsToAnalyze)
        println("Fetched ${topPlayedSeedSongs.size} top played seed songs.")

        val allLibrarySongs = songRepository.songs()
        println("Fetched ${allLibrarySongs.size} songs from the library.")

        if (topPlayedSeedSongs.isEmpty()) {
            println("No seed songs. Returning random from library.")
            allLibrarySongs.shuffled().also {
                println("Returning ${it.size} songs (random): ${it.joinToString { s -> s.title }}")
            }
        }

        val seedSongMetadata = topPlayedSeedSongs.mapNotNull { allSongMetaDataMap[it.data] }
        if (seedSongMetadata.isEmpty()) {
            println("No metadata for seed songs. Returning random from library.")
            allLibrarySongs.shuffled().also {
                println("Returning ${it.size} songs (random due to no seed metadata): ${it.joinToString { s -> s.title }}")
            }
        }

        val tasteProfile = createUserTasteProfile(seedSongMetadata, numProminentArtistsToPick)
        println("Created taste profile: $tasteProfile")


        val seedSongIds = topPlayedSeedSongs.map { it.id }.toSet()

        val candidateSongs = allLibrarySongs
            .filter { song -> !seedSongIds.contains(song.id) } // Exclude seed songs
            .mapNotNull { song ->
                allSongMetaDataMap[song.data]?.let { meta ->
                    val score = calculateSimilarityScore(meta, tasteProfile)
                    if (score > 0.0) {
                        ScoredSong(song, score)
                    } else {
                        null
                    }
                }
            }
        println("Scored ${candidateSongs.size} candidate songs.")

        if (candidateSongs.isEmpty()) {
            println("No suitable candidate songs found after scoring. Returning random from library.")
            allLibrarySongs.shuffled().also {
                println("Returning ${it.size} songs (random due to no candidates): ${it.joinToString { s -> s.title }}")
            }
        }

        val selectedSongs = mutableListOf<Song>()
        val availableCandidates = candidateSongs.toMutableList()

        repeat(50) {
            if (availableCandidates.isEmpty()) {
                println("Ran out of candidates during selection.")
                return@repeat // Break repeat if no more candidates
            }

            val totalWeight = availableCandidates.sumOf { it.score }
            if (totalWeight <= 0.0) {
                println("Total weight is zero or negative, selecting purely random from remaining.")
                if (availableCandidates.isNotEmpty()) {
                    selectedSongs.add(availableCandidates.removeAt(Random.nextInt(availableCandidates.size)).song)
                }
                return@repeat
            }

            var randomPick = Random.nextDouble(totalWeight)
            var chosenSong: ScoredSong? = null

            for (scoredSong in availableCandidates) {
                if (randomPick < scoredSong.score) {
                    chosenSong = scoredSong
                    break
                }
                randomPick -= scoredSong.score
            }

            if (chosenSong == null && availableCandidates.isNotEmpty()) {
                println("Fallback: Choosing random candidate as weighted selection missed.")
                chosenSong = availableCandidates.random()
            }


            chosenSong?.let {
                selectedSongs.add(it.song)
                availableCandidates.remove(it)
                println("Selected by weight: ${it.song.title} (Score: ${it.score})")
            }
        }

        println("Final selected songs for taste (${selectedSongs.size}): ${selectedSongs.joinToString { it.title }}")
        selectedSongs

    } catch (e: Exception) {
        println("An error occurred in getSongsForTaste: ${e.message}")
        e.printStackTrace()
        val allLibrarySongsFallback = try { songRepository.songs() } catch (_: Exception) { emptyList() }
        println("Error fallback: Returning purely random songs.")
        allLibrarySongsFallback.shuffled()
    }

    override fun getTrendingYouTubeSongs(needed: Int): List<Song> {
        if (needed <= 0) return emptyList()
        return try {
            runBlocking {
                val trendingSongsDeferred = async {
                    YouTube.getChartsPage().fold(
                        onSuccess = { it },
                        onFailure = {
                            println("Populate: Failed to fetch trending YouTube songs from charts: $it")
                            Timber.tag("RealRepository")
                                .e(it, "Failed to fetch trending YouTube songs from charts")
                            null
                        }
                    )
                }

                val homeSongsDeferred = async {
                    YouTube.getHomeSongs().getOrElse {
                        println("Populate: Failed to fetch home songs: $it")
                        Timber.tag("RealRepository").e(it, "Failed to fetch home songs")
                        emptyList()
                    }
                }

                val chartsPage = trendingSongsDeferred.await()
                val homeSongItems = homeSongsDeferred.await()

                val trendingSongItems = chartsPage?.sections
                    ?.flatMap { section -> section.items }
                    ?.filterIsInstance<SongItem>() ?: emptyList()

                println("Populate: Fetched ${trendingSongItems.size} trending songs and ${homeSongItems.size} home songs.")

                val combinedItems = (trendingSongItems + homeSongItems).distinctBy { it.id }
                println("Populate: Combined and filtered to ${combinedItems.size} unique songs.")

                combinedItems.map { ytSongItem ->
                    Song(
                        id = ytSongItem.id.hashCode().toLong(),
                        title = ytSongItem.title,
                        trackNumber = 0,
                        year = 0,
                        duration = (ytSongItem.duration?.toLong() ?: 0L) * 1000L,
                        data = "https://music.youtube.com/watch?v=${ytSongItem.id}",
                        dateModified = System.currentTimeMillis(),
                        albumId = ytSongItem.album?.id?.hashCode()?.toLong() ?: 0L,
                        albumName = ytSongItem.album?.name ?: "YouTube Charts",
                        artistId = ytSongItem.artists.firstOrNull()?.id?.hashCode()?.toLong() ?: 0L,
                        artistName = ytSongItem.artists.joinToString { it.name }.ifEmpty { "Unknown Artist" },
                        composer = "",
                        albumArtist = ytSongItem.artists.firstOrNull()?.name ?: "",
                        bpm = null,
                        ytID = ytSongItem.id,
                        isYTSong = true,
                        streamUrl = null
                    )
                }.shuffled().take(needed)
            }
        } catch (e: Exception) {
            println("Populate: Exception in getTrendingYouTubeSongs: $e")
            Timber.tag("RealRepository").e(e, "Exception in getTrendingYouTubeSongs")
            emptyList()
        }
    }

    private fun createUserTasteProfile(
        seedMetadata: List<SongMetaData>,
        numProminentArtists: Int
    ): UserTasteProfile {
        val prominentGenres = seedMetadata
            .flatMap { it.genre }
            .groupingBy { it.lowercase() }
            .eachCount()

        val validEnergies = seedMetadata.mapNotNull { it.energy }.filter { it in 0.0..1.0 }
        val validDanceabilities = seedMetadata.mapNotNull { it.danceability }.filter { it in 0.0..1.0 }
        val validTempos = seedMetadata.mapNotNull { it.tempo }.filter { it > 0 }


        val prominentArtistNames = seedMetadata
            .flatMap { it.artists }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(numProminentArtists)
            .map { it.first }

        return UserTasteProfile(
            prominentGenres = prominentGenres,
            averageEnergy = if (validEnergies.isNotEmpty()) validEnergies.average() else null,
            averageDanceability = if (validDanceabilities.isNotEmpty()) validDanceabilities.average() else null,
            averageTempo = if (validTempos.isNotEmpty()) validTempos.average() else null,
            prominentArtistNames = prominentArtistNames
        )
    }

    private fun calculateSimilarityScore(
        songMeta: SongMetaData,
        profile: UserTasteProfile
    ): Double {
        var score = 0.0
        val maxScorePerFeature = 10.0

        val matchedGenres = songMeta.genre.count { profile.prominentGenres.containsKey(it.lowercase()) }
        val profileGenreCount = profile.prominentGenres.size.coerceAtLeast(1)
        val genreScore = (matchedGenres.toDouble() / profileGenreCount) * maxScorePerFeature
        score += genreScore

        if (songMeta.artists.any { profile.prominentArtistNames.contains(it) }) {
            score += maxScorePerFeature * 0.5
        }

        val energyWeight = 0.33
        val danceabilityWeight = 0.33
        val tempoWeight = 0.34

        var audioFeatureScore = 0.0

        profile.averageEnergy?.let { avgEnergy ->
            songMeta.energy?.let { songEnergy ->
                val diff = kotlin.math.abs(avgEnergy - songEnergy)
                audioFeatureScore += (1.0 - diff) * energyWeight * maxScorePerFeature
            }
        }
        profile.averageDanceability?.let { avgDance ->
            songMeta.danceability?.let { songDance ->
                val diff = kotlin.math.abs(avgDance - songDance)
                audioFeatureScore += (1.0 - diff) * danceabilityWeight * maxScorePerFeature
            }
        }
        profile.averageTempo?.let { avgTempo ->
            songMeta.tempo?.let { songTempo ->
                val tempoDiffRatio = kotlin.math.abs(avgTempo - songTempo) / avgTempo.coerceAtLeast(1.0)
                audioFeatureScore += (1.0 - tempoDiffRatio.coerceAtMost(1.0)) * tempoWeight * maxScorePerFeature
            }
        }
        score += audioFeatureScore.coerceAtLeast(0.0)

        score += Random.nextDouble(0.0, 0.5)
        return score.coerceAtLeast(0.0)
    }

    override suspend fun upsertSongInPlayCount(playCountEntity: PlayCountEntity) =
        roomRepository.upsertSongInPlayCount(playCountEntity)

    override suspend fun deleteSongInPlayCount(playCountEntity: PlayCountEntity) =
        roomRepository.deleteSongInPlayCount(playCountEntity)

    override suspend fun deleteSongInHistory(songId: Long) =
        roomRepository.deleteSongInHistory(songId)

    override suspend fun clearSongHistory() {
        roomRepository.clearSongHistory()
    }

    override suspend fun findSongExistInPlayCount(songId: Long): PlayCountEntity? =
        roomRepository.findSongExistInPlayCount(songId)

    override suspend fun playCountSongs(): List<PlayCountEntity> =
        roomRepository.playCountSongs()

    override fun observableHistorySongs(): LiveData<List<Song>> =
        roomRepository.observableHistorySongs().map {
            it.fromHistoryToSongs() // Assuming List<HistoryEntity>.fromHistoryToSongs() extension exists
        }
    override fun newSongs(): List<Song> {
        return try {
            val historySongIds = roomRepository.historySongs().map { it.id }.toSet()

            val allLibrarySongs = songRepository.songs()

            val unplayedSongs = allLibrarySongs.filterNot { librarySong ->
                librarySong.id in historySongIds
            }

            if (unplayedSongs.isEmpty()) {
                emptyList()
            } else {
                println("Returning ${unplayedSongs.size} new songs: ${unplayedSongs.joinToString { it.title }}")
                unplayedSongs.shuffled()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun historySong(): List<HistoryEntity> =
        roomRepository.historySongs()

    override fun favorites(): LiveData<List<SongEntity>> =
        roomRepository.favoritePlaylistLiveData(context.getString(R.string.favorites))

    override suspend fun suggestions(): List<Song> {
        return NotPlayedPlaylist().songs().shuffled().takeIf {
            it.size > 9
        } ?: emptyList()
    }

    override suspend fun genresHome(): Home {
        val genres = genreRepository.genres().shuffled()
        return Home(genres, GENRES, R.string.genres)
    }

    override suspend fun playlists(): Home {
        val playlist = playlistRepository.playlists()
        return Home(playlist, PLAYLISTS, R.string.playlists)
    }

    override suspend fun topArtistsHome(): Home {
        val artists = topPlayedRepository.topArtists().take(5)
        return Home(artists, TOP_ARTISTS, R.string.top_artists)
    }

    override suspend fun favoritePlaylistHome(): Home {
        val songs = favoritePlaylistSongs().map {
            it.toSong() // Assuming SongEntity.toSong() extension exists
        }
        return Home(songs, FAVOURITES, R.string.favorites)
    }

    // Similar Songs Implementations
    override suspend fun addSimilarSong(originalSongId: Long, similarSong: Song) {
        val similarSongEntity = similarSong.toSimilarSongEntity(originalSongId)
        roomRepository.similarSongDao().addSimilarSong(similarSongEntity)
    }

    override fun getSimilarSongs(originalSongId: Long): LiveData<List<Song>> {
        return roomRepository.similarSongDao().getSimilarSongs(originalSongId).map { entities ->
            entities.map { it.toSong() }
        }
    }

    override suspend fun getSimilarSongsList(originalSongId: Long): List<Song> {
        return roomRepository.similarSongDao().getSimilarSongsList(originalSongId).map { it.toSong() }
    }

    override suspend fun removeSimilarSong(originalSongId: Long, similarSongId: Long) {
        roomRepository.similarSongDao().removeSimilarSong(originalSongId, similarSongId)
    }

    override suspend fun clearSimilarSongsForOriginal(originalSongId: Long) {
        roomRepository.similarSongDao().clearSimilarSongsForOriginal(originalSongId)
    }

    override suspend fun isSongSimilar(originalSongId: Long, potentialSimilarSongId: Long): Boolean {
        return roomRepository.similarSongDao().findSimilarSongEntry(originalSongId, potentialSimilarSongId) != null
    }
}
