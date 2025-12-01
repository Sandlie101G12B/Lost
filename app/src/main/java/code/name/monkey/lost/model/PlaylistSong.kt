package code.name.monkey.lost.model

import kotlinx.parcelize.Parcelize

/**
 * Created by hemanths on 3/4/19
 */
@Parcelize
class PlaylistSong(
    override val id: Long,
    override val title: String,
    override val trackNumber: Int,
    override val year: Int,
    override val duration: Long,
    override val data: String,
    override val dateModified: Long,
    override val albumId: Long,
    override val albumName: String,
    override val artistId: Long,
    override val artistName: String,
    val playlistId: Long,
    val idInPlayList: Long,
    override val composer: String?,
    override val albumArtist: String?,
    override val bpm: Float? = null,
    override val ytID: String? = null,
    override val isYTSong: Boolean = false,
    override var streamUrl: String? = null,
    override val isLocal: Boolean = false,
    override val thumbnale: String? = null
) : Song(
    id = id,
    title = title,
    trackNumber = trackNumber,
    year = year,
    duration = duration,
    data = data,
    dateModified = dateModified,
    albumId = albumId,
    albumName = albumName,
    artistId = artistId,
    artistName = artistName,
    composer = composer,
    albumArtist = albumArtist,
    bpm = bpm,
    ytID = ytID,
    isYTSong = isYTSong,
    streamUrl = streamUrl,
    isLocal = isLocal,
    thumbnale = thumbnale
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as PlaylistSong

        if (id != other.id) return false
        if (title != other.title) return false
        if (trackNumber != other.trackNumber) return false
        if (year != other.year) return false
        if (duration != other.duration) return false
        if (data != other.data) return false
        if (dateModified != other.dateModified) return false
        if (albumId != other.albumId) return false
        if (albumName != other.albumName) return false
        if (artistId != other.artistId) return false
        if (artistName != other.artistName) return false
        if (playlistId != other.playlistId) return false
        if (idInPlayList != other.idInPlayList) return false
        if (composer != other.composer) return false
        if (albumArtist != other.albumArtist) return false
        if (bpm != other.bpm) return false
        if (ytID != other.ytID) return false
        if (isYTSong != other.isYTSong) return false
        if (streamUrl != other.streamUrl) return false
        if (isLocal != other.isLocal) return false
        if (thumbnale != other.thumbnale) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + trackNumber
        result = 31 * result + year
        result = 31 * result + duration.hashCode()
        result = 31 * result + data.hashCode()
        result = 31 * result + dateModified.hashCode()
        result = 31 * result + albumId.hashCode()
        result = 31 * result + albumName.hashCode()
        result = 31 * result + artistId.hashCode()
        result = 31 * result + artistName.hashCode()
        result = 31 * result + playlistId.hashCode()
        result = 31 * result + idInPlayList.hashCode()
        result = 31 * result + composer.hashCode()
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (bpm?.hashCode() ?: 0)
        result = 31 * result + (ytID?.hashCode() ?: 0)
        result = 31 * result + isYTSong.hashCode()
        result = 31 * result + (streamUrl?.hashCode() ?: 0)
        result = 31 * result + isLocal.hashCode()
        result = 31 * result + (thumbnale?.hashCode() ?: 0)
        return result
    }
}
