package code.name.monkey.lost.helper

import android.content.Context
import android.os.Build
import com.google.gson.JsonParser
import okhttp3.*
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import code.name.monkey.lost.repository.RealSongRepository
import code.name.monkey.lost.model.SongTMPContainer
import okhttp3.OkHttpClient
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import androidx.core.net.toUri
import code.name.monkey.lost.network.InternetConnection

object LyricsGetter {
    fun writeLyricsToFile(
        file: File?,
        lrcContent: String,
        context: Context,
        song: SongTMPContainer,
        sdCardPath: String?
    ) {
        try {
            file?.writeText(lrcContent)
        } catch (_: FileNotFoundException) {
            handleFileNotFoundException(context, song, file, lrcContent, sdCardPath)
        }
    }

    fun handleFileNotFoundException(
        context: Context,
        song: SongTMPContainer,
        file: File?,
        lrc: String,
        sdCardPath: String?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !song.data.contains("/storage/emulated/0") && sdCardPath != null) {
            val sd = context.externalCacheDirs[1].absolutePath.substring(
                0,
                context.externalCacheDirs[1].absolutePath.indexOf("/Android/data")
            )
            val path = file?.absolutePath?.substringAfter(sd)?.split("/")?.dropLast(1)
            var sdCardFiles = DocumentFile.fromTreeUri(context, sdCardPath.toUri())
            for (element in path!!) {
                for (sdCardFile in sdCardFiles!!.listFiles()) {
                    if (sdCardFile.name == element) {
                        sdCardFiles = sdCardFile
                    }
                }
            }
            sdCardFiles?.listFiles()?.forEach {
                if (it.name == file.name) {
                    it.delete()
                    return@forEach
                }
            }
            sdCardFiles?.createFile("text/lrc", file.name)?.let {
                val outputStream = context.contentResolver.openOutputStream(it.uri)
                outputStream?.write(lrc.toByteArray())
                outputStream?.close()
            }
        } 
    }

    fun String.toLrcFile(): File? {
        return if (this.isNotEmpty()) {
            File(this.substringBeforeLast('.') + ".lrc")
        } else {
            null
        }
    }

    fun fetchLyricsForSong(song: SongTMPContainer): String? {
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        var lyricsContentToWrite = ""
        val artist = song.artistName?.joinToString(" ") ?: ""
        val title = song.title
        val request = Request.Builder().url(buildLyricsApiUrl(artist, title)).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    if (body.isBlank()) return ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val syncedLyrics = if (json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull) json.get("syncedLyrics").asString else null
                    val plainLyrics = if (json.has("plainLyrics") && !json.get("plainLyrics").isJsonNull) json.get("plainLyrics").asString else null
                    val rawApiLyrics = syncedLyrics ?: plainLyrics ?: ""
                    lyricsContentToWrite = removeHtmlTags(rawApiLyrics)
                    if (lyricsContentToWrite.isBlank() && (!syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank())) {
                        return ""
                    }
                } else if (response.code == 404) {
                    return "No Lyrics Found"
                } else {
                    return ""
                }
            }
        } catch (_: Exception) {
            return ""
        }
        return lyricsContentToWrite
    }

    fun removeHtmlTags(input: String): String {
        return input.replace("v1:<", " <").replace(Regex("<.*?>"), "")
    }

    fun buildLyricsApiUrl(artist: String, title: String): String {
        val baseUrl = "https://lrclib.net/api/get"
        val artistParam = URLEncoder.encode(artist, "UTF-8")
        val titleParam = URLEncoder.encode(title, "UTF-8")
        return "$baseUrl?artist_name=$artistParam&track_name=$titleParam"
    }
    fun downloadLyrics(context: Context) {

        var totalWaitTimeMillis = 0L
        val initialSleepTimeMillis = 2 * 60 * 1000L // 2 minutes
        val thirtyMinThresholdSleepTimeMillis = 11000L * 60L * 15L // 165 minutes
        val oneHourThresholdSleepTimeMillis = 20 * 60 * 1000L     // 20 minutes
        val thirtyMinutesMillis = 30 * 60 * 1000L
        val oneHourMillis = 60 * 60 * 1000L
        var sleepDurationForThisIterationMillis: Long

        while (!InternetConnection.hasInternetConnection(context)) {
            sleepDurationForThisIterationMillis = if (totalWaitTimeMillis >= oneHourMillis) {
                oneHourThresholdSleepTimeMillis
            } else if (totalWaitTimeMillis >= thirtyMinutesMillis) {
                thirtyMinThresholdSleepTimeMillis
            } else {
                initialSleepTimeMillis
            }

            val nextCheckInMinutes = sleepDurationForThisIterationMillis / (60 * 1000)
            val totalWaitTimeSoFarMinutes = totalWaitTimeMillis / (60 * 1000)
            val waitMsg = if (totalWaitTimeMillis == 0L) {
                "Waiting for internet. Retrying in $nextCheckInMinutes min."
            } else {
                "Still no internet. Retrying in $nextCheckInMinutes min. Total wait: $totalWaitTimeSoFarMinutes min."
            }
            try {
                Thread.sleep(sleepDurationForThisIterationMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            totalWaitTimeMillis += sleepDurationForThisIterationMillis
        }

        val songRepository = RealSongRepository(context)
        val deviceSongs = songRepository.songs().map {
            SongTMPContainer(
                title = it.title,
                artistName = it.artistName
                    .split(',', '/')
                    .map { name -> name.trim() }
                    .filter { name -> name.isNotEmpty() },
                data = it.data,
                year = it.year,
                liked = false,
                favorite = false,
                rating = 0
            )
        }

        if (deviceSongs.isEmpty()) {
            return
        }

        var songsProcessedCount = 0
        for (song in deviceSongs) {
            songsProcessedCount++

            // Update notification for the current song being processed
            val file = song.data.toLrcFile()
            if (doesFileExist(file)) {
                continue
            }
            val lyrics = fetchLyricsForSong(song)
            if (lyrics != null && lyrics != "No Lyrics Found" && lyrics.isNotBlank()) {
                writeLyricsToFile(file, lyrics, context, song, null)
            } else if (lyrics == "No Lyrics Found") {
                // Optionally, log or handle this case, e.g., create an empty .lrc file
                continue
            }
            // Small delay between API calls, if necessary
            Thread.sleep(1000)
        }
    }

    fun doesFileExist(file: File?): Boolean {
        return file?.exists() == true && file.isFile
    }
}
