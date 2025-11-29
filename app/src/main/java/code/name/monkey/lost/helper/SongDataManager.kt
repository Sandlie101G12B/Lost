package code.name.monkey.lost.helper

import android.content.Context
import android.os.Environment
import code.name.monkey.lost.BuildConfig
import code.name.monkey.lost.model.SongMetaData
import timber.log.Timber
import java.io.File
import java.io.IOException

object SongDataManager {
    var defaultSongsJson = "[]"
    const val TAG = "SongDataManager"

    fun loadDefaultSongsJson(context: Context) {
        val sourceFile = File(context.filesDir, resultantPath)
        defaultSongsJson = if (!sourceFile.exists() || sourceFile.readText().isBlank()) {
            "[]"
        } else {
            sourceFile.readText()
        }

        if (sourceFile.exists()) {
            try {
                val lostFilesRelease = if (BuildConfig.DEBUG) {
                    "LostFiles"
                } else {
                    "LostFilesRelease"
                }
                val destinationDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), lostFilesRelease)
                if (!destinationDir.exists()) {
                    if (!destinationDir.mkdirs()) {
                        return // Stop if directory creation fails
                    }
                }

                val destinationFile = File(destinationDir, resultantPath)

                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                } catch (_: IOException) {

                } catch (_: SecurityException) {
                }
        } else {
            }
    }
    var songs: MutableList<SongMetaData> = mutableListOf()
}
