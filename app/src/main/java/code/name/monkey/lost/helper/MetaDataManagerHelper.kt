package code.name.monkey.lost.helper

import android.content.Context
import android.util.Log
import code.name.monkey.lost.model.SongMetaData
// It's good practice to add the import for Song if it's not already implicitly available
import code.name.monkey.lost.model.Song // Added import for Song model
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException

object MetaDataManagerHelper {

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
        } catch (e: IOException) {
            Log.e("MetaDataManagerHelper", "Error writing output file", e)
            // Handle error (e.g., log it, show a toast)
        }
    }

    fun getSongMetaDataList(): List<SongMetaData> {
        val jsonString = readRawOutputFile()
        return try {
            val listType = object : TypeToken<List<SongMetaData>>() {}.type
            Gson().fromJson(jsonString, listType) ?: emptyList()
        } catch (e: Exception) {
            Log.e("MetaDataManagerHelper", "Error parsing SongMetaData list", e)
            // Handle error (e.g., log it, return empty list)
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

    // Loads all song metadata into a map, keyed by the song's ID string.
    // Assumes SongMetaData has a unique 'id: Long' property corresponding to Song.id.
//    fun loadMetadataMap(): Map<String, SongMetaData> {
//        val list = getSongMetaDataList() // Existing function
//        return list.associateBy { it.id.toString() } // Assumes SongMetaData has 'id: Long'
//    }
}