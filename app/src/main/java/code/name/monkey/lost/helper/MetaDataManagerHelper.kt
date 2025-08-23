package code.name.monkey.lost.helper

import android.content.Context
import android.util.Log
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.model.Song // Added import for Song model
import code.name.monkey.lost.repository.RealPlaylistRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.IOException

object MetaDataManagerHelper : KoinComponent {

    private const val OUTPUT_FILE_NAME = "outputile.txt"
    private var appContext: Context? = null

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
            file.readText()
        } else {
            "[]" // Default to empty JSON array if file doesn't exist or cannot be read
        }
    }

    fun writeRawOutputFile(content: String) {
        try {
            val file = getOutputFile()
            file.writeText(content)
            SongDataManager.loadDefaultSongsJson(getContext()) // Added call
        } catch (e: IOException) {
            Log.e("MetaDataManagerHelper", "Error writing output file", e)
            // Handle error (e.g., log it, show a toast)
        }
    }

    fun getSongMetaDataList(): List<SongMetaData> {
        val jsonString = readRawOutputFile()
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
                        year = rawMap["year"] as? String ?: "", // Ensures "" if year is null or not a string
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
                    null // Skip this problematic entry and log the error
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e("MetaDataManagerHelper", "Error parsing SongMetaData list from JSON", e)
            emptyList()
        }
    }

    fun saveSongMetaDataList(dataList: List<SongMetaData>) {
        try {
            val jsonString = Gson().toJson(dataList)
            writeRawOutputFile(jsonString)
        } catch (e: Exception) {
            Log.e("MetaDataManagerHelper", "Error saving SongMetaData list", e)
            // Handle error
        }
    }

    // Defines the canonical string key for a Song object.
    // Assumes Song has a unique 'id: Long' property.
    fun getSongKey(song: Song): String { // Changed to use the imported Song type directly
        return song.id.toString()
    }

    /**
     * Updates interaction details for a song identified by its file path.
     *
     * @param filePath The absolute path of the song file.
     * @param toggleLike If true, the liked status will be inverted.
     * @param recordPlay If true, a play event (timestamp) will be recorded.
     * @param recordSkip If true, a skip event (timestamp and count) will be recorded.
     */
    fun updateSongInteraction(
        filePath: String,
        toggleLike: Boolean = false,
        recordPlay: Boolean = false,
        recordSkip: Boolean = false
    ) {
        val metaDataList = getSongMetaDataList().toMutableList()
        val songIndex = metaDataList.indexOfFirst { it.file == filePath }

        if (songIndex != -1) {
            val songMetaData = metaDataList[songIndex]
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
            metaDataList[songIndex] = updatedMetaData
            saveSongMetaDataList(metaDataList)
        } else {
            Log.w("MetaDataManagerHelper", "No metadata found for song with file path: $filePath")
            // Optionally, create new metadata if it doesn't exist,
            // but this requires more information about the song (title, artist, etc.)
            // For now, we'll just log a warning.
        }
    }

    suspend fun reconcileLikedStatusWithLibrary(allSongsFromLibrary: List<Song>) = withContext(Dispatchers.IO) {
        val playlistRepository: RealPlaylistRepository = get()
        val metaDataList = getSongMetaDataList().toMutableList()
        var changesMade = false

        // Get songs from the "Favorites" playlist
        val favoritePlaylists = playlistRepository.searchPlaylist("Favorites")
        val favoriteSongFilePaths = if (favoritePlaylists.isNotEmpty()) {
            // Assuming the first playlist found is the "Favorites" playlist
            // The getSongs() method is on the Playlist object, not the repository
            favoritePlaylists.first().getSongs().map { it.data }.toSet()
        } else {
            emptySet()
        }

        val librarySongFilePaths = allSongsFromLibrary.map { it.data }.toSet()

        metaDataList.forEachIndexed { index, songMetaData ->
            var updatedMetaData = songMetaData
            val songExistsInLibrary = songMetaData.file in librarySongFilePaths
            val isLikedAccordingToPlaylist = songMetaData.file in favoriteSongFilePaths

            val newLikedStatus = songExistsInLibrary && isLikedAccordingToPlaylist

            if (songMetaData.liked != newLikedStatus) {
                updatedMetaData = updatedMetaData.copy(
                    liked = newLikedStatus,
                    likedTimestamp = if (newLikedStatus) System.currentTimeMillis() else null
                )
                changesMade = true
            }
            
            // If song doesn't exist in library but metadata says it's liked (edge case if not covered by above)
            // This is mostly covered by newLikedStatus logic, but an explicit check might be clearer
            // if (!songExistsInLibrary && songMetaData.liked) {
            //    updatedMetaData = updatedMetaData.copy(liked = false, likedTimestamp = null)
            //    changesMade = true
            // }


            if (updatedMetaData !== songMetaData) { // Check if a new object was created by copy()
                 metaDataList[index] = updatedMetaData
            }
        }

        if (changesMade) {
            saveSongMetaDataList(metaDataList)
            Log.i("MetaDataManagerHelper", "Reconciled liked status with Favorites playlist. Changes saved.")
        } else {
            Log.i("MetaDataManagerHelper", "Reconciled liked status with Favorites playlist. No changes needed.")
        }
    }
}