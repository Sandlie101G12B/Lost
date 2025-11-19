package code.name.monkey.lost.db

import android.content.Context
import timber.log.Timber
import java.time.ZoneId

fun DownloadedSongsEntity.toSong(context: Context, streamUrl: String?): code.name.monkey.lost.model.Song {
    Timber.tag("SpotifyPlaylist").d("ESong title: $title")
    // Assuming DownloadUtil.getSongFile(ytID) returns the File object for the downloaded song.
    // This is a placeholder for the actual implementation that should be in DownloadUtil.kt.
    return code.name.monkey.lost.model.Song(
        id = this.id.hashCode().toLong(),
        title = this.title,
        trackNumber = this.trackNumber,
        year = this.year,
        duration = this.duration * 1000L,
        data = this.data,
        dateModified = this.dateDownload?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L,
        albumId = this.albumId,
        albumName = this.albumName,
        artistId = this.artistId,
        artistName = this.artistName,
        composer = this.composer,
        albumArtist = this.albumArtist,
        bpm = null,
        ytID = this.id,
        isYTSong = true,
        streamUrl = streamUrl,
        isLocal = this.isDownloaded
    )
}
