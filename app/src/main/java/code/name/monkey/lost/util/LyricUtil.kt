package code.name.monkey.lost.util

import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.lyrics.AbsSynchronizedLyrics
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import timber.log.Timber
import java.io.*

/**
 * Created by hefuyi on 2016/11/8.
 */
object LyricUtil {
    private val lrcRootPath =
        getExternalStorageDirectory().toString() + "/Lost/lyrics/"

    //So in Lost, Lrc file can be same folder as Music File or in Lost Folder
    // In this case we pass location of the file and Contents to write to file
    fun writeLrc(song: Song, lrcContext: String) {
        var writer: FileWriter? = null
        val location: File?
        try {
            if (isLrcOriginalFileExist(song.data)) {
                location = getLocalLyricOriginalFile(song.data)
            } else if (isLrcFileExist(song.title, song.artistName)) {
                location = getLocalLyricFile(song.title, song.artistName)
            } else {
                location = File(getLrcPath(song.title, song.artistName))
                if (location.parentFile?.exists() != true) {
                    location.parentFile?.mkdirs()
                }
            }
            writer = FileWriter(location)
            writer.write(lrcContext)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                writer?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun isLrcFileExist(title: String, artist: String): Boolean {
        val file = File(getLrcPath(title, artist))
        return file.exists()
    }

    private fun isLrcOriginalFileExist(path: String): Boolean {
        val file = File(getLrcOriginalPath(path))
        return file.exists()
    }

    private fun getLocalLyricFile(title: String, artist: String): File? {
        val file = File(getLrcPath(title, artist))
        return if (file.exists()) {
            file
        } else {
            null
        }
    }

    private fun getLocalLyricOriginalFile(path: String): File? {
        val file = File(getLrcOriginalPath(path))
        return if (file.exists()) {
            file
        } else {
            null
        }
    }

    private fun getLrcPath(title: String, artist: String): String {
        return "$lrcRootPath$title - $artist.lrc"
    }

    private fun getLrcOriginalPath(filePath: String): String {
        return filePath.replace(filePath.substring(filePath.lastIndexOf(".") + 1), "lrc")
    }

    fun removeHtmlTags(input: String): String {
        return input.replace("v1:", "").replace(Regex("<.*?>"), "\n")
    }
    fun getStringFromLrc(file: File?): String {
        try {
            val reader = BufferedReader(FileReader(file))
            return removeHtmlTags(reader.readLines().joinToString(separator = "\n"))
        } catch (_: Exception) {
            Timber.tag("Error").i("Error Occurred")
        }
        return ""
    }

    fun getSyncedLyricsFile(song: Song): File? {
        return when {
            isLrcOriginalFileExist(song.data) -> {
                getLocalLyricOriginalFile(song.data)
            }
            isLrcFileExist(song.title, song.artistName) -> {
                getLocalLyricFile(song.title, song.artistName)
            }
            else -> {
                null
            }
        }
    }

    fun getEmbeddedSyncedLyrics(data: String): String? {
        val embeddedLyrics = try {
            AudioFileIO.read(File(data)).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (_: Exception) {
            return null
        }
        return if (AbsSynchronizedLyrics.isSynchronized(embeddedLyrics)) {
            embeddedLyrics
        } else {
            null
        }
    }
}