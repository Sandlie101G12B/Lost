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
                        Timber.tag(TAG)
                            .e("Failed to create destination directory: ${destinationDir.absolutePath}")
                        return // Stop if directory creation fails
                    }
                }

                val destinationFile = File(destinationDir, resultantPath)

                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.tag(TAG)
                    .i("Successfully copied resultantPath.txt to ${destinationFile.absolutePath}")
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Error copying file: ${e.message}")
            } catch (e: SecurityException) {
                Timber.tag(TAG).e(
                    e,
                    "SecurityException: Missing WRITE_EXTERNAL_STORAGE permission or other security issue. ${e.message}"
                )
            }
        } else {
            Timber.tag(TAG)
                .w("Source file resultantPath.txt does not exist in app's internal storage. Skipping copy.")
        }
    }
    var songs: MutableList<SongMetaData> = mutableListOf()
}
