package code.name.monkey.lost.helper

import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.network.InternetConnection
import code.name.monkey.lost.repository.Repository
import code.name.monkey.lost.util.YTPlayerUtils.getSimilarContent
import code.name.monkey.lost.util.YTPlayerUtils.searchVideos
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent // Koin import
import org.koin.core.component.inject       // Koin import

object ShuffleHelper : KoinComponent { // Implement KoinComponent
    private const val DEBUG_TAG = "ShuffleHelperDebug"
    private var metadataMap: Map<String, SongMetaData>? = null
    
    // Injected by Koin. Assumes Repository is defined in your Koin modules.
    private val repository: Repository by inject()

    private const val CACHE_EXPIRY_MS = 5000L // 5 seconds
    private data class CachedShuffle(val list: List<Song>, val timestamp: Long)
    private val shuffleCache = mutableMapOf<String, CachedShuffle>()

    // init() method is no longer needed as Koin handles injection.

    private fun loadMetadataMap(): Map<String, SongMetaData> {
        if (metadataMap != null) return metadataMap!!
        val defaultSongsJson = SongDataManager.defaultSongsJson
        val listType = object : TypeToken<List<SongMetaData>>() {}.type
        val metadataList: List<SongMetaData> = Gson().fromJson(defaultSongsJson, listType)

        val map = metadataList.filter { it.file.isNotBlank() }.associateBy { File(it.file).nameWithoutExtension.lowercase() }
        metadataMap = map
        return map
    }

    fun makeShuffleList(listToShuffle: MutableList<Song>, current: Int) {
        if (listToShuffle.isEmpty() || current !in listToShuffle.indices) return

        val songForCacheKey = listToShuffle[current]
        val cacheKey = getSongKey(songForCacheKey)
        val currentTime = System.currentTimeMillis()

        shuffleCache[cacheKey]?.let {
            if ((currentTime - it.timestamp) < CACHE_EXPIRY_MS) {
                println("$DEBUG_TAG: Cache hit for key '$cacheKey'. Using cached shuffle list.")
                listToShuffle.clear()
                listToShuffle.addAll(it.list)
                return
            }
        }

        val metadata = loadMetadataMap()
        val currentSong = listToShuffle.removeAt(current)

        if (listToShuffle.isEmpty()) {
            listToShuffle.add(0, currentSong)
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            println("$DEBUG_TAG: Cache updated for key '$cacheKey' after list became empty.")
            return
        }

        val currentMeta = metadata[getSongKey(currentSong)]
        val hasAnyMetadata = listToShuffle.any { metadata[getSongKey(it)] != null }

        if (currentMeta == null || !hasAnyMetadata) {
            println("$DEBUG_TAG: Current meta is null or no metadata in listToShuffle. Performing simple shuffle.")
            listToShuffle.shuffle()
            listToShuffle.add(0, currentSong)
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            println("$DEBUG_TAG: Cache updated for key '$cacheKey' after simple shuffle.")
            return
        }

        fun offlineshuffle(){
            println("$DEBUG_TAG: Performing offline shuffle for key '$cacheKey'.")
            val currentArtistsSet = (currentMeta.artists ?: emptyList()).toSet()
            val scoredSongs = scoreSongs(listToShuffle, metadata, currentMeta)
            val originalScored = scoredSongs.toMutableList()

            if (originalScored.isNotEmpty()) {
                val topSongsForArtistCheck = originalScored.take(7)
                var songsByCurrentArtistInTop = 0
                for ((song, _) in topSongsForArtistCheck) {
                    val songMeta = metadata[getSongKey(song)]
                    if (songMeta != null && (songMeta.artists ?: emptyList()).any { it in currentArtistsSet }) {
                        songsByCurrentArtistInTop++
                    }
                }
                println("$DEBUG_TAG: Songs by current artist in top 7 for de-concentration: $songsByCurrentArtistInTop")
                if (songsByCurrentArtistInTop >= 7) {
                    println("$DEBUG_TAG: Applying artist de-concentration penalty.")
                    for (i in originalScored.indices) {
                        val (song, score) = originalScored[i]
                        val songMeta = metadata[getSongKey(song)]
                        if (songMeta != null && (songMeta.artists ?: emptyList()).any { it in currentArtistsSet }) {
                            val basePenalty = Random.nextInt(0, 15)
                            val numArtistsOnTrack = (songMeta.artists ?: emptyList()).size.coerceAtLeast(1)
                            val adjustedPenalty = basePenalty / numArtistsOnTrack
                            originalScored[i] = song to (score - adjustedPenalty)
                        }
                    }
                }
            }
            listToShuffle.clear()
            listToShuffle.add(currentSong)
            listToShuffle.addAll(originalScored.sortedByDescending { it.second }.map { it.first })
            println("$DEBUG_TAG: Offline shuffle complete. New list size: ${listToShuffle.size}")
        }

        suspend fun onlineShuffle() {
            var allArtists = ""
            (currentMeta.artists ?: emptyList()).forEach { artist ->
                allArtists += " ${URLDecoder.decode(artist, StandardCharsets.UTF_8.toString())}"
            }
            val query = "${URLDecoder.decode(currentMeta.title, StandardCharsets.UTF_8.toString())} -$allArtists"
            println("$DEBUG_TAG: Starting onlineShuffle for key '$cacheKey'. Decoded Query with hyphen: '$query'")

            searchVideos(query)
                .onSuccess { searchResults ->
                    println("$DEBUG_TAG: searchVideos success. Found ${searchResults.size} results.")
                    val firstVideoId = searchResults.firstOrNull()?.videoId
                    if (firstVideoId == null) {
                        println("$DEBUG_TAG: No videoId found from search. Falling back to offline shuffle.")
                        offlineshuffle()
                        shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                        println("$DEBUG_TAG: Cache updated for key '$cacheKey' after fallback to offline shuffle (no videoId).")
                    } else {
                        println("$DEBUG_TAG: Found videoId: $firstVideoId. Getting similar content.")
                        getSimilarContent(firstVideoId)
                            .onSuccess { recommendedYtItems ->
                                println("$DEBUG_TAG: getSimilarContent success. Found ${recommendedYtItems.size} recommended YT items.")
                                val orderedLocalSongs = mutableListOf<Song>()
                                val songsToConsiderForMatching = listToShuffle.toMutableList()
                                println("$DEBUG_TAG: Initial songsToConsiderForMatching size: ${songsToConsiderForMatching.size}")

                                for (ytSong in recommendedYtItems) {
                                    val ytTitle = ytSong.title?.trim()?.lowercase()
                                    val ytArtistNames = ytSong.artists
                                        .mapNotNull { artist -> artist.name?.trim()?.lowercase() }
                                        .filter { it.isNotEmpty() }.toSet()
                                    // ... (rest of matching logic) ...
                                     var matchedLocalSong: Song? = null
                                    val iterator = songsToConsiderForMatching.iterator()
                                    while (iterator.hasNext()) {
                                        val localSong = iterator.next()
                                        val localSongMeta = metadata[getSongKey(localSong)]

                                        if (localSongMeta != null && !isCorrupted(localSongMeta)) {
                                            val localTitle = localSongMeta.title.trim().lowercase()
                                            val localArtistNames = (localSongMeta.artists ?: emptyList())
                                                .mapNotNull { artist -> artist?.trim()?.lowercase() }
                                                .filter { it.isNotEmpty() }.toSet()

                                            val titleMatches = localTitle.contains(ytTitle ?: "") || (ytTitle?:"").contains(localTitle)
                                            val artistsMatch = ytArtistNames.isEmpty() ||
                                                             (localArtistNames.isNotEmpty() && ytArtistNames.intersect(localArtistNames).isNotEmpty())

                                            if (titleMatches && artistsMatch) {
                                                matchedLocalSong = localSong
                                                iterator.remove()
                                                break
                                            }
                                        }
                                    }
                                    matchedLocalSong?.let { song -> orderedLocalSongs.add(song) }
                                }

                                println("$DEBUG_TAG: Finished processing YT recommendations. orderedLocalSongs size: ${orderedLocalSongs.size}, songsToConsiderForMatching (remaining) size: ${songsToConsiderForMatching.size}")
                                songsToConsiderForMatching.shuffle()
                                println("$DEBUG_TAG: Shuffled remaining ${songsToConsiderForMatching.size} songs.")

                                listToShuffle.clear()
                                listToShuffle.add(currentSong)
                                listToShuffle.addAll(orderedLocalSongs)
                                listToShuffle.addAll(songsToConsiderForMatching)
                                println("$DEBUG_TAG: Online shuffle complete. Final listToShuffle size: ${listToShuffle.size}")

                                // START: Add similar songs logic (uses the injected repository)
                                println("$DEBUG_TAG: Starting to add similar songs based on online shuffle results.")
                                for (originalSong in orderedLocalSongs) {
                                    val existingSimilarSongs = repository.getSimilarSongsList(originalSong.id)
                                    if (existingSimilarSongs.isEmpty()) {
                                        var similarSongsAddedCount = 0
                                        val potentialSimilars = listToShuffle.filter { it.id != originalSong.id }
                                        for (candidateSong in potentialSimilars) {
                                            if (similarSongsAddedCount >= 10) break
                                            if (!repository.isSongSimilar(originalSong.id, candidateSong.id)) {
                                                repository.addSimilarSong(originalSong.id, candidateSong)
                                                similarSongsAddedCount++
                                            }
                                        }
                                    }
                                }
                                println("$DEBUG_TAG: Finished process of adding similar songs.")
                                // END: Add similar songs logic
                                
                                shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                                println("$DEBUG_TAG: Cache updated for key '$cacheKey' after online shuffle & adding similar songs.")
                            }
                            .onFailure { exception ->
                                println("$DEBUG_TAG: getSimilarContent failed: ${exception.message}")
                                offlineshuffle()
                                shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                                println("$DEBUG_TAG: Cache updated for key '$cacheKey' after fallback (getSimilarContent failure).")
                            }
                    }
                }
                .onFailure { exception ->
                    println("$DEBUG_TAG: searchVideos failed: ${exception.message}")
                    offlineshuffle()
                    shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                    println("$DEBUG_TAG: Cache updated for key '$cacheKey' after fallback (searchVideos failure).")
                }
        }

        if (InternetConnection.hasInternetConnection(MetaDataManagerHelper.getContext())) {
            println("$DEBUG_TAG: Internet connection available. Attempting online shuffle for key '$cacheKey'.")
//            runBlocking { onlineShuffle() }
            offlineshuffle()
        } else {
            println("$DEBUG_TAG: No internet connection. Performing offline shuffle for key '$cacheKey'.")
            offlineshuffle()
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            println("$DEBUG_TAG: Cache updated for key '$cacheKey' after offline shuffle (no internet).")
        }
    }

    private fun scoreSongs(
        songs: List<Song>,
        metadata: Map<String, SongMetaData>,
        currentMeta: SongMetaData
    ): List<Pair<Song, Int>> {
        return songs.map { song ->
            val meta = metadata[getSongKey(song)]
            if (meta == null || isCorrupted(meta)) {
                Pair(song, Random.nextInt(-20, 0))
            } else {
                val score = calculateSimilarity(currentMeta, meta)
                Pair(song, score)
            }
        }
    }

    private fun getSongKey(song: Song): String {
        return File(song.data).nameWithoutExtension.lowercase()
    }

    private fun calculateSimilarity(
        a: SongMetaData,
        b: SongMetaData,
        favoriteArtists: Set<String> = emptySet(),
        favoriteGenres: Set<String> = emptySet(),
        favoriteMoods: Set<String> = emptySet()
    ): Int {
        return try {
            val commonArtists = (a.artists ?: emptyList()).intersect((b.artists ?: emptyList()).toSet())
            val artistScore = commonArtists.size * Random.nextInt(8, 20)
            val commonGenres = (a.genre ?: emptyList()).take(3).intersect((b.genre ?: emptyList()).toSet())
            val genreScore = commonGenres.size * 20
            val commonMoods = (a.mood ?: emptyList()).take(3).intersect((b.mood ?: emptyList()).toSet())
            val moodScore = commonMoods.size * Random.nextInt(10, 20)
            val danceabilityScore = (10 - (kotlin.math.abs(a.danceability?.minus(b.danceability ?: 0.0) ?: 0.0) * 10).coerceAtMost(10.0)).toInt()
            val marketScore = try {
                (b.market ?: emptyList())?.let { (a.market ?: emptyList())?.take(2)?.intersect(it.toSet())?.size ?: 0 }?.times(5) ?: 0
            } catch (_: Exception) { 0 }
            val yearScore = try {
                val aYear = a.year.toIntOrNull()
                val bYear = b.year.toIntOrNull()
                if (aYear != null && bYear != null && aYear > 0 && bYear > 0) {
                    (-15 + (kotlin.math.abs(aYear - bYear)).coerceAtMost(15)).coerceAtLeast(-5)
                } else 0
            } catch (_: Exception) { 0 }
            val modernBonus = try {
                val bYear = b.year.toIntOrNull()
                val normalized = (((bYear?.coerceIn(1990, 2025) ?: 0) - 1990) / 30.0)
                (normalized * Random.nextInt(0, 5)).toInt()
            } catch (_: Exception) { 0 }
            val energyScore = if (a.energy != null && b.energy != null) {
                (11 - (kotlin.math.abs(a.energy - b.energy) * 11).coerceAtMost(11.0)).toInt()
            } else 0
            val valenceScore = if (a.valence != null && b.valence != null) {
                (10 - (kotlin.math.abs(a.valence - b.valence) * 10).coerceAtMost(10.0)).toInt()
            } else 0
            val tempoScore = if (a.tempo != null && b.tempo != null) {
                (10 - (kotlin.math.abs(a.tempo - b.tempo) / 10).coerceAtMost(10.0)).toInt()
            } else 0
            val genreArtistSimilarity = getGenreBasedArtistSimilarity(a, b)
            val currentTime = System.currentTimeMillis()
            val twoWeeksInMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(14)
            val recentPlays = b.playTimestamps.count { (currentTime - it) < twoWeeksInMillis }
            val playHistoryPenalty = recentPlays * 2
            val oneWeekInMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(7)
            val recentSkips = b.skipTimestamps.count { (currentTime - it) < oneWeekInMillis }
            val skipHistoryPenalty = recentSkips * 10
            val likedBonus = if (b.liked) Random.nextInt(5, 12) else 0
            val favoritedBonus = if (b.favorite) Random.nextInt(10, 20) else 0
            val ratingAdjustment = when {
                b.rating >= 4 -> Random.nextInt(5,15)
                b.rating == 3 -> Random.nextInt(0,5)
                b.rating <= 1 && b.rating > 0 -> -Random.nextInt(5,15)
                else -> 0
            }
            artistScore + genreScore + moodScore + danceabilityScore + marketScore +
                    yearScore + modernBonus + energyScore + valenceScore + tempoScore +
                    genreArtistSimilarity + likedBonus + favoritedBonus + ratingAdjustment -
                    Random.nextInt(0, (playHistoryPenalty + skipHistoryPenalty + 1))
        } catch (_: Exception) {
            Random.nextInt(-2, 4)
        }
    }
    
    private fun getGenreBasedArtistSimilarity(metaA: SongMetaData, metaB: SongMetaData): Int {
        val aArtists = metaA.artists ?: emptyList()
        val bArtists = metaB.artists ?: emptyList()
        val aGenre = metaA.genre ?: emptyList()
        val bGenre = metaB.genre ?: emptyList()

        if (aGenre.isEmpty() || bGenre.isEmpty() || aArtists.isEmpty() || bArtists.isEmpty()) {
            return 0
        }
        val commonGenres = aGenre.intersect(bGenre.toSet())
        if (commonGenres.isEmpty()) {
            return 0
        }
        return if (aArtists.intersect(bArtists.toSet()).isEmpty()) {
            Random.nextInt(1, 8)
        } else {
            0
        }
    }

    private fun isCorrupted(meta: SongMetaData): Boolean {
        val hasNoTitle = meta.title.isBlank()
        val hasNoArtists = (meta.artists ?: emptyList()).isEmpty() || (meta.artists ?: emptyList()).all { it.isBlank() }
        return hasNoTitle && hasNoArtists
    }
}
