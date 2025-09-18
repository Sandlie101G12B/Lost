package code.name.monkey.lost.helper

import android.content.Context
import android.os.Environment
import android.util.Log
import code.name.monkey.lost.model.SongMetaData
import java.io.File
import java.io.IOException

object SongDataManager {
    var defaultSongsJson = "[]"
    const val TAG = "SongDataManager"

    fun loadDefaultSongsJson(context: Context) {
        val sourceFile = File(context.filesDir, "resultantPath.txt")
        defaultSongsJson = if (!sourceFile.exists() || sourceFile.readText().isBlank()) {
            "[]"
        } else {
            sourceFile.readText()
        }

        if (sourceFile.exists()) {
            try {
                val destinationDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LostFiles")
                if (!destinationDir.exists()) {
                    if (!destinationDir.mkdirs()) {
                        Log.e(TAG, "Failed to create destination directory: ${destinationDir.absolutePath}")
                        return // Stop if directory creation fails
                    }
                }

                val destinationFile = File(destinationDir, "resultantPath.txt")

                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully copied resultantPath.txt to ${destinationFile.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Error copying file: ${e.message}", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Missing WRITE_EXTERNAL_STORAGE permission or other security issue. ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Source file resultantPath.txt does not exist in app's internal storage. Skipping copy.")
        }
    }
    var songs: MutableList<SongMetaData> = mutableListOf()
}