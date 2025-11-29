package code.name.monkey.lost.db

import timber.log.Timber
import java.time.ZoneId
import code.name.monkey.lost.model.Song

fun DownloadedSongsEntity.toSong(): Song {
    return Song(
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
        streamUrl = this.streamUrl,
        isLocal = this.isDownloaded,
        thumbnale = this.thumbnailUrl
    )
}

fun DownloadedSongsEntity.toSongEntity(playlistCreatorId: Long): SongEntity {
    return SongEntity(
        playlistCreatorId = playlistCreatorId,
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
        dateDownload = this.dateDownload,
        isDownloaded = this.isDownloaded,
        thumbnailUrl = this.thumbnailUrl,
        inLibrary = this.inLibrary,
        ytID = this.id,
        streamUrl = this.streamUrl
    )
}

fun Song.songToSongEntity(playlistCreatorId: Long): SongEntity {
    return SongEntity(
        playlistCreatorId = playlistCreatorId,
        id = this.id,
        title = this.title,
        trackNumber = this.trackNumber,
        year = this.year,
        duration = this.duration,
        data = this.data,
        dateModified = this.dateModified,
        albumId = this.albumId,
        albumName = this.albumName,
        artistId = this.artistId,
        artistName = this.artistName,
        composer = this.composer,
        albumArtist = this.albumArtist,
        dateDownload = null,
        isDownloaded = this.isLocal,
        thumbnailUrl = this.thumbnale,
        inLibrary = null,
        ytID = this.ytID,
        streamUrl = this.streamUrl
    )
}
