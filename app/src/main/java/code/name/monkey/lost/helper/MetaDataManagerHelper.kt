package code.name.monkey.lost.helper

import android.content.Context
import android.util.Log
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.model.Song // Added import for Song model
import code.name.monkey.lost.repository.RealPlaylistRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.IOException
import java.util.Locale // Added for genre normalization

object MetaDataManagerHelper : KoinComponent {

    private const val OUTPUT_FILE_NAME = "outputile.txt"
    private var appContext: Context? = null

    // Cache for SongMetaData list
    @Volatile
    private var cachedSongMetaDataList: List<SongMetaData>? = null
    private val cacheLock = Any()

    // Coroutine scope for background tasks
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun saveContext(context: Context) {
        appContext = context.applicationContext
    }

    private fun getContext(): Context {
        return appContext ?: throw IllegalStateException("Context not initialized in MetaDataManagerHelper. Call saveContext first.")
    }

    private fun getOutputFile(): File {
        return File(getContext().filesDir, OUTPUT_FILE_NAME)
    }

    fun readRawOutputFile(): String {
        val file = getOutputFile()
        return if (file.exists() && file.canRead()) {
            try {
                file.readText()
            } catch (e: IOException) {
                Log.e("MetaDataManagerHelper", "Error reading output file", e)
                "[]" // Default to empty JSON array on read error
            }
        } else {
            "[]" // Default to empty JSON array if file doesn't exist or cannot be read
        }
    }

    // Internal function to write to file, to be called from background or when immediate write is fine
    private fun internalWriteRawOutputFile(content: String) {
        try {
            val file = getOutputFile()
            file.writeText(content)
            // Assuming SongDataManager.loadDefaultSongsJson should be updated based on new file content
            // Consider if this needs to run on a specific thread or if it's safe here
            SongDataManager.loadDefaultSongsJson(getContext())
        } catch (e: IOException) {
            Log.e("MetaDataManagerHelper", "Error writing output file", e)
            // Handle error (e.g., log it, show a toast if on main thread, though this is background)
        }
    }

    private fun normalizeGenreName(genre: String): String {
        return genre
            .replace("&", "and")
            .replace(Regex("-"), " ") // Hyphen to space
            .split(Regex("\\s+")) // Split by one or more spaces
            .filter { it.isNotBlank() } // Remove empty strings if any
            .map { word ->
                word.lowercase(Locale.ROOT)
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
            .joinToString("-") // Join title-cased words with a hyphen
    }

    // Public function to write raw output, updates cache and writes to file
    fun writeRawOutputFile(content: String) {
        val newMetaDataList = parseSongMetaDataJson(content)
        // Normalize genres after parsing
        val normalizedMetaDataList = newMetaDataList.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }
        synchronized(cacheLock) {
            cachedSongMetaDataList = normalizedMetaDataList // Update cache with normalized data
        }
        // Write the *normalized* content back to the file
        val normalizedJsonString = Gson().toJson(normalizedMetaDataList)
        internalWriteRawOutputFile(normalizedJsonString)
    }


    private fun parseSongMetaDataJson(jsonString: String): List<SongMetaData> {
        return try {
            val mapListType = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>>? = Gson().fromJson(jsonString, mapListType)

            rawList?.mapNotNull { rawMap ->
                try {
                    SongMetaData(
                        title = rawMap["title"] as? String ?: "",
                        artists = (rawMap["artists"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        file = rawMap["file"] as? String ?: "",
                        mood = (rawMap["mood"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        genre = (rawMap["genre"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(), // Read as is
                        playlist = (rawMap["playlist"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        year = rawMap["year"] as? String ?: "",
                        liked = rawMap["liked"] as? Boolean ?: false,
                        favorite = rawMap["favorite"] as? Boolean ?: false,
                        rating = (rawMap["rating"] as? Number)?.toInt() ?: 0,
                        danceability = (rawMap["danceability"] as? Number)?.toDouble(),
                        tempo = (rawMap["tempo"] as? Number)?.toDouble(),
                        energy = (rawMap["energy"] as? Number)?.toDouble(),
                        valence = (rawMap["valence"] as? Number)?.toDouble(),
                        market = (rawMap["market"] as? List<*>)?.mapNotNull { it as? String },
                        skips = (rawMap["skips"] as? Number)?.toInt() ?: 0,
                        bpm = (rawMap["bpm"] as? Number)?.toFloat(),
                        playTimestamps = (rawMap["playTimestamps"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.toMutableList() ?: mutableListOf(),
                        skipTimestamps = (rawMap["skipTimestamps"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.toMutableList() ?: mutableListOf(),
                        likedTimestamp = (rawMap["likedTimestamp"] as? Number)?.toLong()
                    )
                } catch (e: Exception) {
                    Log.e("MetaDataManagerHelper", "Error parsing individual SongMetaData object from map: $rawMap", e)
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MetaDataManagerHelper", "Error parsing SongMetaData list from JSON: $jsonString", e)
            emptyList()
        }
    }

    fun getSongMetaDataList(): List<SongMetaData> {
        synchronized(cacheLock) {
            cachedSongMetaDataList?.let {
                return it // Already normalized if it came from writeRawOutputFile or saveSongMetaDataList
            }
        }
        // Cache is null, read from file, parse, and populate cache
        val jsonString = readRawOutputFile()
        val listFromFile = parseSongMetaDataJson(jsonString) // parseSongMetaDataJson itself doesn't normalize

        // Normalize after reading if the cache was empty
        val normalizedListFromFile = listFromFile.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }

        synchronized(cacheLock) {
            cachedSongMetaDataList = normalizedListFromFile
        }
        return normalizedListFromFile
    }

    fun saveSongMetaDataList(dataList: List<SongMetaData>) {
        // Normalize genres before caching and saving
        val normalizedDataList = dataList.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }

        synchronized(cacheLock) {
            cachedSongMetaDataList = normalizedDataList // Update cache with normalized data
        }
        // Launch a coroutine to save to file in the background
        coroutineScope.launch {
            try {
                val jsonString = Gson().toJson(normalizedDataList) // Serialize normalized data
                internalWriteRawOutputFile(jsonString)
            } catch (e: Exception) {
                Log.e("MetaDataManagerHelper", "Error saving SongMetaData list in background", e)
            }
        }
    }

    fun getSongKey(song: Song): String {
        return song.id.toString()
    }

    fun updateSongInteraction(
        filePath: String,
        toggleLike: Boolean = false,
        recordPlay: Boolean = false,
        recordSkip: Boolean = false
    ) {
        val currentList = getSongMetaDataList() // This will return a list with normalized genres
        val songIndex = currentList.indexOfFirst { it.file == filePath }

        if (songIndex != -1) {
            val songMetaData = currentList[songIndex]
            var updatedMetaData = songMetaData

            if (toggleLike) {
                val newLikedState = !songMetaData.liked
                updatedMetaData = updatedMetaData.copy(
                    liked = newLikedState,
                    likedTimestamp = if (newLikedState) System.currentTimeMillis() else null
                )
            }

            if (recordPlay) {
                val updatedPlayTimestamps = updatedMetaData.playTimestamps.toMutableList().apply {
                    add(System.currentTimeMillis())
                }
                updatedMetaData = updatedMetaData.copy(playTimestamps = updatedPlayTimestamps)
            }

            if (recordSkip) {
                val updatedSkipTimestamps = updatedMetaData.skipTimestamps.toMutableList().apply {
                    add(System.currentTimeMillis())
                }
                updatedMetaData = updatedMetaData.copy(
                    skips = updatedMetaData.skips + 1,
                    skipTimestamps = updatedSkipTimestamps
                )
            }
            
            if (updatedMetaData !== songMetaData) {
                val mutableList = currentList.toMutableList()
                mutableList[songIndex] = updatedMetaData
                saveSongMetaDataList(mutableList.toList()) // Save the modified list (triggers cache update, normalization, and background write)
            }
        } else {
            Log.w("MetaDataManagerHelper", "No metadata found for song with file path: $filePath to update interaction.")
        }
    }

    suspend fun reconcileLikedStatusWithLibrary(allSongsFromLibrary: List<Song>) = withContext(Dispatchers.IO) {
        val playlistRepository: RealPlaylistRepository = get()
        
        val currentMetaDataList = getSongMetaDataList() // Already normalized genres
        val mutableMetaDataList = currentMetaDataList.toMutableList()
        var changesMade = false

        val favoritePlaylists = playlistRepository.searchPlaylist("Favorites")
        val favoriteSongFilePaths = if (favoritePlaylists.isNotEmpty()) {
            favoritePlaylists.first().getSongs().map { it.data }.toSet()
        } else {
            emptySet()
        }

        val librarySongFilePaths = allSongsFromLibrary.map { it.data }.toSet()

        currentMetaDataList.forEachIndexed { index, songMetaData ->
            var needsUpdate = false
            var tempLikedStatus = songMetaData.liked
            var tempLikedTimestamp = songMetaData.likedTimestamp

            val songExistsInLibrary = songMetaData.file in librarySongFilePaths
            val isLikedAccordingToPlaylist = songMetaData.file in favoriteSongFilePaths
            
            val newLikedStatus = songExistsInLibrary && isLikedAccordingToPlaylist

            if (songMetaData.liked != newLikedStatus) {
                tempLikedStatus = newLikedStatus
                tempLikedTimestamp = if (newLikedStatus) System.currentTimeMillis() else null
                needsUpdate = true
            }

            if (needsUpdate) {
                 mutableMetaDataList[index] = songMetaData.copy(
                    liked = tempLikedStatus,
                    likedTimestamp = tempLikedTimestamp
                    // Genres are already normalized from getSongMetaDataList and in songMetaData
                )
                changesMade = true
            }
        }

        if (changesMade) {
            saveSongMetaDataList(mutableMetaDataList.toList()) // This will handle re-normalization just in case, cache, and write
            Log.i("MetaDataManagerHelper", "Reconciled liked status with Favorites playlist. Changes saved.")
        } else {
            Log.i("MetaDataManagerHelper", "Reconciled liked status with Favorites playlist. No changes needed.")
        }
    }
}
