package code.name.monkey.lost.helper

import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Generates playlist blueprints based on song metadata.
 * A PlaylistBlueprint contains a suggested name and a list of song file paths.
 */
object AutomaticPlaylistGenerator {

    data class PlaylistBlueprint(val name: String, val songFilePaths: List<String>)

    private fun getDailySeed(): Long {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 10000L +
                (calendar.get(Calendar.MONTH) + 1) * 100L +
                calendar.get(Calendar.DAY_OF_MONTH)
    }

    private fun getCurrentDateSuffix(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun findMatchingSongs(
        selectedMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>
    ): List<Song> {
        if (selectedMetaData.isEmpty() || allLibrarySongs.isEmpty()) {
            return emptyList()
        }
        val librarySongMap = allLibrarySongs.associateBy { it.data } // O(N) for map creation
        return selectedMetaData.mapNotNull { metaData -> // O(M) for mapping
            librarySongMap[metaData.file]
        }
    }

    fun generateThrowbackPlaylist(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        targetYear: Int,
        count: Int = 20
    ): PlaylistBlueprint? {
        val random = Random(getDailySeed())
        val filteredMetaData = allSongMetaData.asSequence()
            .filter { (it.year.toIntOrNull() ?: 0) <= targetYear }
            .shuffled(random)
            .take(count)
            .toList()

        if (filteredMetaData.isEmpty()) return null

        val selectedSongs = findMatchingSongs(filteredMetaData, allLibrarySongs)
        if (selectedSongs.isEmpty()) return null

        val playlistName = "Throwback $targetYear (${getCurrentDateSuffix()})"
        return PlaylistBlueprint(playlistName, selectedSongs.map { it.data })
    }

    fun generateDecadeRewindPlaylist(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        startYear: Int,
        endYear: Int,
        count: Int = 30
    ): PlaylistBlueprint? {
        val random = Random(getDailySeed())
        val filteredMetaData = allSongMetaData.asSequence()
            .filter {
                val year = it.year.toIntOrNull() ?: 0
                year in startYear..endYear
            }
            .shuffled(random)
            .take(count)
            .toList()

        if (filteredMetaData.isEmpty()) return null

        val selectedSongs = findMatchingSongs(filteredMetaData, allLibrarySongs)
        if (selectedSongs.isEmpty()) return null

        val playlistName = "Decade Rewind ${startYear}-${endYear} (${getCurrentDateSuffix()})"
        return PlaylistBlueprint(playlistName, selectedSongs.map { it.data })
    }

    fun generateHighEnergyPlaylist(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        minEnergy: Double = 0.7,
        minTempo: Double = 120.0,
        count: Int = 25
    ): PlaylistBlueprint? {
        val random = Random(getDailySeed())
        val filteredMetaData = allSongMetaData.asSequence()
            .filter { (it.energy ?: 0.0) >= minEnergy && (it.tempo ?: 0.0) >= minTempo }
            .shuffled(random)
            .take(count)
            .toList()

        if (filteredMetaData.isEmpty()) return null

        val selectedSongs = findMatchingSongs(filteredMetaData, allLibrarySongs)
        if (selectedSongs.isEmpty()) return null

        val playlistName = "High Energy Mix (${getCurrentDateSuffix()})"
        return PlaylistBlueprint(playlistName, selectedSongs.map { it.data })
    }

    fun generateMoodPlaylist(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        targetMood: String,
        count: Int = 20
    ): PlaylistBlueprint? {
        val random = Random(getDailySeed())
        val filteredMetaData = allSongMetaData.asSequence()
            .filter { metaData ->
                metaData.mood.any { mood -> mood.equals(targetMood, ignoreCase = true) }
            }
            .shuffled(random)
            .take(count)
            .toList()

        if (filteredMetaData.isEmpty()) return null

        val selectedSongs = findMatchingSongs(filteredMetaData, allLibrarySongs)
        if (selectedSongs.isEmpty()) return null

        val playlistName = "$targetMood Vibes (${getCurrentDateSuffix()})"
        return PlaylistBlueprint(playlistName, selectedSongs.map { it.data })
    }
    
    fun generateAllMoodPlaylists(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        countPerMood: Int = 20
    ): List<PlaylistBlueprint> {
        val allMoods = allSongMetaData.asSequence()
            .flatMap { it.mood }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .toList()

        return allMoods.mapNotNull { mood ->
            generateMoodPlaylist(allSongMetaData, allLibrarySongs, mood, countPerMood)
        }
    }

    fun generateGenreSpecificPlaylist(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        targetGenre: String,
        count: Int = 25
    ): PlaylistBlueprint? {
        val random = Random(getDailySeed())
        // Assuming genres in SongMetaData are already somewhat normalized.
        // If not, more sophisticated matching might be needed.
        val filteredMetaData = allSongMetaData.asSequence()
            .filter { metaData ->
                metaData.genre.any { genre -> genre.equals(targetGenre, ignoreCase = true) }
            }
            .shuffled(random)
            .take(count)
            .toList()

        if (filteredMetaData.isEmpty()) return null

        val selectedSongs = findMatchingSongs(filteredMetaData, allLibrarySongs)
        if (selectedSongs.isEmpty()) return null

        val playlistName = "$targetGenre Essentials (${getCurrentDateSuffix()})"
        return PlaylistBlueprint(playlistName, selectedSongs.map { it.data })
    }

    fun generateAllGenrePlaylists(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        countPerGenre: Int = 25
    ): List<PlaylistBlueprint> {
        val allGenres = allSongMetaData.asSequence()
            .flatMap { it.genre }
            .distinctBy { it.lowercase(Locale.getDefault()) } // Normalize for distinctness
            .toList()

        return allGenres.mapNotNull { genre ->
            generateGenreSpecificPlaylist(allSongMetaData, allLibrarySongs, genre, countPerGenre)
        }
    }
    
    fun generateLikedSongsRadio(
        allSongMetaData: List<SongMetaData>,
        allLibrarySongs: List<Song>,
        count: Int = 50
    ): PlaylistBlueprint? {
        val random = Random(getDailySeed())
        val likedMetaData = allSongMetaData.asSequence()
            .filter { it.liked }
            .shuffled(random) // Shuffle liked songs first
            .toList()

        if (likedMetaData.isEmpty()) return null // No liked songs, no radio

        // Simple approach: just take a number of liked songs.
        // More complex: find songs similar to liked songs (would require more metadata or embeddings)
        val selectedMetaData = likedMetaData.take(count)
        
        if (selectedMetaData.isEmpty()) return null

        val selectedSongs = findMatchingSongs(selectedMetaData, allLibrarySongs)
        if (selectedSongs.isEmpty()) return null
        
        val playlistName = "Liked Songs Radio (${getCurrentDateSuffix()})"
        return PlaylistBlueprint(playlistName, selectedSongs.map { it.data })
    }
}
