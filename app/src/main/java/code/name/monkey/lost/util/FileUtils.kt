package code.name.monkey.lost.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import timber.log.Timber
import java.io.File
import java.io.IOException


object FileUtils {
    fun copyFileToUri(context: Context, fromFile: File, toUri: Uri) {
        context.contentResolver.openOutputStream(toUri)
            ?.use { output ->
                fromFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
    }

    /**
     * creates a new file in storage in app specific directory.
     *
     * @return the file
     * @throws IOException
     */
    fun createFile(
        context: Context,
        directoryName: String,
        fileName: String,
        body: String,
        fileType: String
    ): File {
        val root = createDirectory(context, directoryName)
        val filePath = "$root/$fileName$fileType"
        val file = File(filePath)

        // create file if not exist
        if (!file.exists()) {
            try {
                // create a new file and write text in it.
                file.createNewFile()
                file.writeText(body)
                Timber.tag(FileUtils::class.java.name).d("File has been created and saved")
            } catch (e: IOException) {
                Timber.tag(FileUtils::class.java.name).d(e.message.toString())
            }
        }
        return file
    }

    /**
     * creates a new directory in storage in app specific directory.
     *
     * @return the file
     */
    private fun createDirectory(context: Context, directoryName: String): File {
        val file = File(
            context.getExternalFilesDir(directoryName)
                .toString()
        )
        if (!file.exists()) {
            file.mkdir()
        }
        return file
    }
}
@Suppress("Deprecation")
fun getExternalStorageDirectory(): File {
    return Environment.getExternalStorageDirectory()
}

@Suppress("Deprecation")
fun getExternalStoragePublicDirectory(type: String): File {
    return Environment.getExternalStoragePublicDirectory(type)
}