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
    
    // Public function to write raw output, updates cache and writes to file (can be immediate)
    fun writeRawOutputFile(content: String) {
        // Update cache first
        val newMetaDataList = parseSongMetaDataJson(content)
        synchronized(cacheLock) {
            cachedSongMetaDataList = newMetaDataList
        }
        // Then write to file (immediately in this case, or could be background)
        internalWriteRawOutputFile(content)
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
                        genre = (rawMap["genre"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
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
                return it
            }
        }
        // Cache is null, read from file, parse, and populate cache
        val jsonString = readRawOutputFile()
        val listFromFile = parseSongMetaDataJson(jsonString)
        synchronized(cacheLock) {
            cachedSongMetaDataList = listFromFile
        }
        return listFromFile
    }

    fun saveSongMetaDataList(dataList: List<SongMetaData>) {
        // Update cache immediately
        synchronized(cacheLock) {
            cachedSongMetaDataList = dataList
        }
        // Launch a coroutine to save to file in the background
        coroutineScope.launch {
            try {
                val jsonString = Gson().toJson(dataList)
                internalWriteRawOutputFile(jsonString) // Use the internal write function
            } catch (e: Exception) {
                Log.e("MetaDataManagerHelper", "Error saving SongMetaData list in background", e)
                // Handle error (e.g., retry logic, notify user through other means)
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
        // Get the list (potentially from cache)
        val currentList = getSongMetaDataList()
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
                saveSongMetaDataList(mutableList.toList()) // Save the modified list (triggers cache update and background write)
            }
        } else {
            Log.w("MetaDataManagerHelper", "No metadata found for song with file path: $filePath to update interaction.")
        }
    }

    suspend fun reconcileLikedStatusWithLibrary(allSongsFromLibrary: List<Song>) = withContext(Dispatchers.IO) {
        val playlistRepository: RealPlaylistRepository = get() // Assuming get() is fine in withContext(Dispatchers.IO)
        
        // Get the list (potentially from cache, but work on a mutable copy)
        val currentMetaDataList = getSongMetaDataList() 
        val mutableMetaDataList = currentMetaDataList.toMutableList()
        var changesMade = false

        val favoritePlaylists = playlistRepository.searchPlaylist("Favorites") // This might involve DB/IO
        val favoriteSongFilePaths = if (favoritePlaylists.isNotEmpty()) {
            favoritePlaylists.first().getSongs().map { it.data }.toSet() // getSongs() might involve DB/IO
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
                )
                changesMade = true
            }
        }

        if (changesMade) {
            // Save the modified list (this will update cache and trigger background write)
            saveSongMetaDataList(mutableMetaDataList.toList()) 
            Log.i("MetaDataManagerHelper", "Reconciled liked status with Favorites playlist. Changes saved.")
        } else {
            Log.i("MetaDataManagerHelper", "Reconciled liked status with Favorites playlist. No changes needed.")
        }
    }
}
