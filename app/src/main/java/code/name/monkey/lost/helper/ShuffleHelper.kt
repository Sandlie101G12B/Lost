package code.name.monkey.lost.helper

import android.content.Context
import android.os.Environment // Added
import android.util.Log // Added
import code.name.monkey.lost.model.FlowType
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException // Added
import java.util.concurrent.TimeUnit // Added for time calculations
import kotlin.random.Random

object SongDataManager {
    var defaultSongsJson = "[]"
    const val TAG = "SongDataManager" // Added for logging

    fun loadDefaultSongsJson(context: Context) {
        val sourceFile = File(context.filesDir, "outputile.txt")
        defaultSongsJson = if (!sourceFile.exists() || sourceFile.readText().isBlank()) {
            "[]"
        } else {
            sourceFile.readText()
        }

        // Start: Added code for copying the file
        if (sourceFile.exists()) {
            try {
                val destinationDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LostFiles")
                if (!destinationDir.exists()) {
                    if (!destinationDir.mkdirs()) {
                        Log.e(TAG, "Failed to create destination directory: ${destinationDir.absolutePath}")
                        return // Stop if directory creation fails
                    }
                }

                val destinationFile = File(destinationDir, "outputile.txt")

                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully copied outputile.txt to ${destinationFile.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Error copying file: ${e.message}", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Missing WRITE_EXTERNAL_STORAGE permission or other security issue. ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Source file outputile.txt does not exist in app's internal storage. Skipping copy.")
        }
        // End: Added code for copying the file
    }

    var songs: MutableList<SongMetaData> = mutableListOf()
}

object ShuffleHelper {
    private var metadataMap: Map<String, SongMetaData>? = null
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

        val metadata = loadMetadataMap()
        val currentSong = listToShuffle.removeAt(current)
        val currentMeta = metadata[getSongKey(currentSong)]
        val hasAnyMetadata = listToShuffle.any { metadata[getSongKey(it)] != null }

        if (currentMeta == null || !hasAnyMetadata) {
            listToShuffle.shuffle()
            listToShuffle.add(0, currentSong)
            return
        }
        // currentMeta is confirmed to be non-null here
        val currentArtistsSet = currentMeta.artists.toSet()

        val scoredSongs = scoreSongs(listToShuffle, metadata, currentMeta)
        val originalScored = scoredSongs.toMutableList()

        // Artist De-concentration Logic
        if (originalScored.isNotEmpty()) {
            val topSongsForArtistCheck = originalScored.take(6)
            var songsByCurrentArtistInTop = 0
            for ((song, _) in topSongsForArtistCheck) {
                val songMeta = metadata[getSongKey(song)]
                // Check if songMeta is not null and shares any artist with currentArtistsSet
                if (songMeta != null && songMeta.artists.any { it in currentArtistsSet }) {
                    songsByCurrentArtistInTop++
                }
            }

            if (songsByCurrentArtistInTop >= 3) {
                for (i in originalScored.indices) {
                    val (song, score) = originalScored[i]
                    val songMeta = metadata[getSongKey(song)]
                    // Check if songMeta is not null and shares any artist with currentArtistsSet
                    if (songMeta != null && songMeta.artists.any { it in currentArtistsSet }) {
                        val basePenalty = Random.nextInt(10, 35) // Base penalty: 5 to 15 points
                        // Adjust penalty based on the number of artists on the track being penalized
                        val numArtistsOnTrack = songMeta.artists.size.coerceAtLeast(1)
                        val adjustedPenalty = basePenalty / numArtistsOnTrack
                        originalScored[i] = song to (score - adjustedPenalty)
                    }
                }
            }
        }

        val selectedFlow = selectFlowType(currentMeta)
        // Ensure reorderByFlow uses the smoothed scores from originalScored
        val reordered: List<Pair<Song, Int>> = reorderByFlow(selectedFlow, originalScored, metadata)
        // Also ensure enforceMaxMovement uses the smoothed originalScored for its original positions
        val finalOrdered = enforceMaxMovement(reordered, originalScored, maxMovement = Random.nextInt(5, 11))
        val smartShuffled = finalOrdered
            .groupBy { it.second } // Group by the (potentially smoothed) score
            .toSortedMap(compareByDescending { it }) // Sort groups by score descending
            .flatMap { (_, group) -> group.shuffled().map { it.first } } // Shuffle within score groups
        val extraRandomized = smartShuffled.toMutableList()
        val swapRange = Random.nextInt(2,5)
        val swaps = (extraRandomized.size / 7).coerceAtLeast(1)
        repeat(swaps) {
            val i = (1 until extraRandomized.size).random()
            // Only swap with a song within 3 positions away
            val minJ = (i - swapRange).coerceAtLeast(1)
            val maxJ = (i + swapRange).coerceAtMost(extraRandomized.size - 1)
            if (maxJ > minJ) {
                val j = (minJ..maxJ).filter { it != i }.random()
                val tmp = extraRandomized[i]
                extraRandomized[i] = extraRandomized[j]
                extraRandomized[j] = tmp
            }
        }
        listToShuffle.clear()
        listToShuffle.add(currentSong)
        listToShuffle.addAll(extraRandomized)
    }

    private fun scoreSongs(
        songs: List<Song>,
        metadata: Map<String, SongMetaData>,
        currentMeta: SongMetaData
    ): List<Pair<Song, Int>> {
        return songs.mapNotNull { song ->
            val meta = metadata[getSongKey(song)]
            println(meta)
            if (meta == null || isCorrupted(meta)) {
                null
            } else {
                val score = calculateSimilarity(currentMeta, meta)
                Pair(song, score)
            }
        }
    }

    /**
     * Selects the flow type based on the current song's metadata.
     */
    private fun selectFlowType(currentMeta: SongMetaData): FlowType {
        return when {
            (currentMeta.energy ?: 0.0) > 0.7 && (currentMeta.danceability)!! > 0.7 -> FlowType.Pulse
            (currentMeta.energy ?: 0.0) < 0.4 && (currentMeta.valence ?: 0.0) < 0.4 -> FlowType.WindDown
            (currentMeta.valence ?: 0.0) > 0.7 && (currentMeta.energy ?: 0.0) > 0.4 -> FlowType.MoodLift
            currentMeta.mood.any { it.contains("party", ignoreCase = true) || it.contains("dance", ignoreCase = true) } -> FlowType.RollerCoaster
            (currentMeta.tempo ?: 0.0) > 130.0 -> FlowType.Wave
            else -> FlowType.RollerCoaster
        }
    }

    private fun reorderByFlow(
        flow: FlowType,
        scored: List<Pair<Song, Int>>, // This now receives the potentially smoothed scores
        metadata: Map<String, SongMetaData>
    ): List<Pair<Song, Int>> {
        fun Song.getMeta(): SongMetaData? = metadata[getSongKey(this)]
        // The `scored` list here contains pairs of (Song, potentially smoothed Int score)
        // The sorting logic inside might use these scores or other metadata like energy, valence etc.
        // If it uses `.second` from the pair, it will use the smoothed score.
        return when (flow) {
            FlowType.RollerCoaster -> {
                val sorted = scored.sortedByDescending { it.first.getMeta()?.energy ?: 0.0 }
                val high = sorted.filterIndexed { i, _ -> i % 2 == 0 }
                val low = sorted.filterIndexed { i, _ -> i % 2 != 0 }.reversed()
                (high + low).take(scored.size)
            }
            FlowType.WindDown -> {
                scored.sortedWith(
                    compareByDescending<Pair<Song, Int>> { it.first.getMeta()?.energy ?: 0.0 }
                        .thenByDescending { it.first.getMeta()?.valence ?: 0.0 }
                )
            }
            FlowType.MoodLift -> {
                scored.sortedWith(
                    compareBy<Pair<Song, Int>> { it.first.getMeta()?.valence ?: 0.0 }
                        .thenBy { it.first.getMeta()?.energy ?: 0.0 }
                )
            }
            FlowType.Pulse -> {
                val sorted = scored.sortedByDescending { it.first.getMeta()?.danceability ?: 0.0 }
                val high = sorted.filterIndexed { i, _ -> i % 2 == 0 }
                val low = sorted.filterIndexed { i, _ -> i % 2 != 0 }.reversed()
                (high + low).take(scored.size)
            }
            FlowType.Wave -> {
                val sorted = scored.sortedByDescending { it.first.getMeta()?.energy ?: 0.0 }
                val chunked = sorted.chunked(5).flatMapIndexed { idx, chunk ->
                    if (idx % 2 == 0) chunk else chunk.reversed()
                }
                chunked
            }
        }
    }

    private fun enforceMaxMovement(
        reordered: List<Pair<Song, Int>>, // This list is from reorderByFlow
        originalScored: List<Pair<Song, Int>>, // This is the smoothed list
        maxMovement: Int
    ): MutableList<Pair<Song, Int>> {
        val finalOrdered = MutableList(reordered.size) { reordered[it] }
        // The originalScored list is used here to find the original index of an item
        // based on the Song object and its (smoothed) score.
        // If an item was (SongA, 100) and smoothed to (SongA, 105), originalScored reflects this.
        for ((originalIdx, pair) in reordered.withIndex()) {
            val origPos = originalScored.indexOf(pair) // This should correctly find the item if pair matches an entry in originalScored
            if (origPos == -1) {
                 // This case should ideally not happen if reordered contains items from originalScored.
                 // Handle defensively or log if necessary.
                 continue
            }
            val minPos = (origPos - maxMovement).coerceAtLeast(0)
            val maxPos = (origPos + maxMovement).coerceAtMost(reordered.size - 1)
            val targetPos = originalIdx.coerceIn(minPos, maxPos)
            finalOrdered.remove(pair)
            finalOrdered.add(targetPos, pair)
        }
        return finalOrdered
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

        // 1. Artist Matching
        val commonArtists = a.artists.intersect(b.artists.toSet())
        val artistScore = commonArtists.size * Random.nextInt(1, 10)

        // 2. Genre Matching
        val commonGenres = a.genre.intersect(b.genre.toSet())
        val genreScore = commonGenres.size * Random.nextInt(8, 20)

        // 3. Mood Matching
        val commonMoods = a.mood.intersect(b.mood.toSet())
        val moodScore = commonMoods.size * Random.nextInt(7, 10)

        // 4. Danceability
        val danceabilityScore = (10 - (kotlin.math.abs(a.danceability?.minus(b.danceability ?: 0.0) ?: 0.0) * 10).coerceAtMost(10.0)).toInt()

        // 5. Market Similarity
        val marketScore = try {
            b.market?.let { a.market?.intersect(it.toSet())?.size ?: 0 }?.times(4) ?: 0
        } catch (_: Exception) { 0 }

        // 6. Year Proximity
        val yearScore = try {
            val aYear = a.year.toIntOrNull()
            val bYear = b.year.toIntOrNull()
            if (aYear != null && bYear != null && aYear > 0 && bYear > 0) {
                (7 - (kotlin.math.abs(aYear - bYear) / 2).coerceAtMost(10)).coerceAtLeast(-7)
            } else 0
        } catch (_: Exception) { 0 }

        // 7. Modern Song Bonus — strong boost for newer songs
        val modernBonus = try {
            val bYear = b.year.toIntOrNull()
            val normalized = (((bYear?.coerceIn(1990, 2025) ?: 0) - 1990) / 35.0)
            (normalized * Random.nextInt(5, 25)).toInt()
        } catch (_: Exception) { 0 }

        // 8. Energy
        val energyScore = if (a.energy != null && b.energy != null) {
            (10 - (kotlin.math.abs(a.energy - b.energy) * 10).coerceAtMost(10.0)).toInt()
        } else 0

        // 9. Valence
        val valenceScore = if (a.valence != null && b.valence != null) {
            (10 - (kotlin.math.abs(a.valence - b.valence) * 10).coerceAtMost(10.0)).toInt()
        } else 0

        // 10. Tempo
        val tempoScore = if (a.tempo != null && b.tempo != null) {
            (10 - (kotlin.math.abs(a.tempo - b.tempo) / 10).coerceAtMost(10.0)).toInt()
        } else 0

        // 11. Genre-Based Artist Similarity
        val genreArtistSimilarity = getGenreBasedArtistSimilarity(a, b)

        // 12. Favorite Preference Boost (optional)
        val favArtistBoost = b.artists.count { it in favoriteArtists } * 15
        val favGenreBoost = b.genre.count { it in favoriteGenres } * 10
        val favMoodBoost = b.mood.count { it in favoriteMoods } * 10

        // 13. Play History Penalty
        val currentTime = System.currentTimeMillis()
        val twoWeeksInMillis = TimeUnit.DAYS.toMillis(14)
        val recentPlays = b.playTimestamps.count { (currentTime - it) < twoWeeksInMillis }
        val playHistoryPenalty = recentPlays * 7

        // 14. Skip History Penalty
        val oneWeekInMillis = TimeUnit.DAYS.toMillis(7)
        val recentSkips = b.skipTimestamps.count { (currentTime - it) < oneWeekInMillis }
        val skipHistoryPenalty = recentSkips * 10 // Heavier penalty for recent skips

        // 15. Liked Song Bonus
        val likedBonus = if (b.liked) Random.nextInt(5, 12) else 0

        // 16. Favorited Song Bonus (stronger than liked)
        val favoritedBonus = if (b.favorite) Random.nextInt(10, 20) else 0
        
        // 17. Rating-Based Adjustment
        val ratingAdjustment = when {
            b.rating >= 4 -> Random.nextInt(5,15)
            b.rating == 3 -> Random.nextInt(0,5)
            b.rating <= 1 && b.rating > 0 -> -Random.nextInt(5,15) // Penalty for low rated songs
            else -> 0
        }

        val totalScore = artistScore + genreScore + moodScore + danceabilityScore + marketScore +
                yearScore + modernBonus + energyScore + valenceScore + tempoScore +
                genreArtistSimilarity + favArtistBoost + favGenreBoost + favMoodBoost +
                likedBonus + favoritedBonus + ratingAdjustment - playHistoryPenalty - skipHistoryPenalty
        
        return totalScore.coerceIn(0, 200) // Ensure score is within a reasonable range
    }

    private fun getGenreBasedArtistSimilarity(metaA: SongMetaData, metaB: SongMetaData): Int {
        if (metaA.genre.isEmpty() || metaB.genre.isEmpty() || metaA.artists.isEmpty() || metaB.artists.isEmpty()) {
            return 0
        }
        val commonGenres = metaA.genre.intersect(metaB.genre.toSet())
        if (commonGenres.isEmpty()) {
            return 0
        }
        // If they share genres, give a small boost if artists are different,
        // to encourage variety within a genre session.
        // No penalty if artists are the same, as other factors handle direct artist repetition.
        return if (metaA.artists.intersect(metaB.artists.toSet()).isEmpty()) {
            Random.nextInt(1, 8) // Small boost for different artists in shared genres
        } else {
            0 // Neutral if same artist or if artists already matched by direct artist similarity
        }
    }


    private fun isCorrupted(meta: SongMetaData): Boolean {
        // Example check: A song might be considered corrupted if it has no title AND no artists
        // AND the file path seems unusually short or nonsensical (though file path check is harder here).
        // For now, let's base it on essential textual metadata.
        val hasNoTitle = meta.title.isBlank()
        val hasNoArtists = meta.artists.isEmpty() || meta.artists.all { it.isBlank() }
        
        // If critical fields like title or artist are missing, consider it potentially problematic.
        // Add more checks as needed, e.g., for file existence if `meta.file` was validated elsewhere
        // or if you have a reliable way to check it here.
        return hasNoTitle && hasNoArtists
    }
}
