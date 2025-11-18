package code.name.monkey.lost.db

import android.content.Context
import code.name.monkey.lost.db.entities.ESong
import timber.log.Timber
import java.io.File
import java.time.ZoneId

fun ESong.toSong(context: Context): code.name.monkey.lost.model.Song {
    Timber.tag("SpotifyPlaylist").d("ESong title: ${song.title}")
    val artistName = artists.joinToString(", ") { it.name }
    // Assuming DownloadUtil.getSongFile(ytID) returns the File object for the downloaded song.
    // This is a placeholder for the actual implementation that should be in DownloadUtil.kt.
    val songFile = File(context.filesDir, "download/${song.id}")
    return code.name.monkey.lost.model.Song(
        id = song.id.hashCode().toLong(),
        title = song.title,
        trackNumber = 0, // Not available for songs from database
        year = 0, // Not available for songs from database
        duration = song.duration * 1000L,
        data = songFile.path,
        dateModified = song.dateDownload?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L,
        albumId = album?.id?.hashCode()?.toLong() ?: -1L,
        albumName = album?.title ?: "",
        artistId = artists.firstOrNull()?.id?.hashCode()?.toLong() ?: -1L,
        artistName = artistName,
        composer = null,
        albumArtist = null,
        bpm = null,
        ytID = song.id,
        isYTSong = false,
        streamUrl = null,
        isLocal = false
    )
}
