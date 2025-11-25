package code.name.monkey.lost.model

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

// update equals and hashcode if fields changes
@Parcelize
open class Song(
    open val id: Long,
    open val title: String,
    open val trackNumber: Int,
    open val year: Int,
    open val duration: Long,
    open val data: String,
    open val dateModified: Long,
    open val albumId: Long,
    open val albumName: String,
    open val artistId: Long,
    open val artistName: String, // Main artist name string, used to derive artistNames
    open val composer: String?,
    open val albumArtist: String?,
    open val bpm: Float? = null,
    open val ytID: String? = null,
    open val isYTSong: Boolean = false,
    open var streamUrl: String? = null,
    open val isLocal: Boolean = false,
    open val thumbnale: String? = null,
) : Parcelable {

    @IgnoredOnParcel
    open val artistNames: List<String> by lazy {
        val names = artistName.split(Regex("\\s*[/,&;]\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // If splitting results in an empty list but the original artistName was not blank,
        // use the original artistName as a single entry.
        if (names.isEmpty() && artistName.isNotBlank()) {
            listOf(artistName)
        } else {
            names
        }
    }

    @IgnoredOnParcel
    open val artistIds: List<Long> by lazy {
        artistNames.map { it.hashCode().toLong() }
    }

    // need to override manually because is open and cannot be a data class
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Song

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
        if (artistName != other.artistName) return false // Compare the original string
        if (composer != other.composer) return false
        if (albumArtist != other.albumArtist) return false
        if (bpm != other.bpm) return false
        // Compare the derived lists
        if (artistNames != other.artistNames) return false
        if (artistIds != other.artistIds) return false

        // Note: The constructor parameters artistNames and artistIds are not directly compared here,
        // as we are focusing on the derived lists for equality.
        // If they should also be part of equality, this logic would need adjustment.

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + trackNumber
        result = 31 * result + year
        result = 31 * result + duration.hashCode()
        result = 31 * result + data.hashCode()
        result = 31 * result + dateModified.hashCode()
        result = 31 * result + albumId.hashCode()
        result = 31 * result + albumName.hashCode()
        result = 31 * result + artistId.hashCode()
        result = 31 * result + artistName.hashCode() // Hash the original string
        result = 31 * result + (composer?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (bpm?.hashCode() ?: 0)
        // Hash the derived lists
        result = 31 * result + artistNames.hashCode()
        result = 31 * result + artistIds.hashCode()

        // Note: The constructor parameters artistNames and artistIds are not directly included here.
        return result
    }

    // Inside the Song class
    override fun toString(): String {
        return "Song(id=$id, title='$title', trackNumber=$trackNumber, year=$year, " +
                "duration=$duration, data='$data', dateModified=$dateModified, " +
                "albumId=$albumId, albumName='$albumName', artistId=$artistId, " +
                "artistName='$artistName', composer=$composer, albumArtist=$albumArtist, " +
                "bpm=$bpm, ytID=$ytID, isYTSong=$isYTSong, streamUrl=$streamUrl, " +
                "artistNames=$artistNames, artistIds=$artistIds)"
    }

    open fun copy(
        id: Long = this.id,
        title: String = this.title,
        trackNumber: Int = this.trackNumber,
        year: Int = this.year,
        duration: Long = this.duration,
        data: String = this.data,
        dateModified: Long = this.dateModified,
        albumId: Long = this.albumId,
        albumName: String = this.albumName,
        artistId: Long = this.artistId,
        artistName: String = this.artistName,
        composer: String? = this.composer,
        albumArtist: String? = this.albumArtist,
        bpm: Float? = this.bpm,
        ytID: String? = this.ytID,
        isYTSong: Boolean = this.isYTSong,
        streamUrl: String? = this.streamUrl,
        isLocal: Boolean = this.isLocal
    ): Song {
        return Song(
            id,
            title,
            trackNumber,
            year,
            duration,
            data,
            dateModified,
            albumId,
            albumName,
            artistId,
            artistName,
            composer,
            albumArtist,
            bpm,
            ytID,
            isYTSong,
            streamUrl,
            isLocal
        )
    }

    companion object {

        @JvmStatic
        val emptySong = Song(
            id = -1,
            title = "",
            trackNumber = -1,
            year = -1,
            duration = -1,
            data = "",
            dateModified = -1,
            albumId = -1,
            albumName = "",
            artistId = -1,
            artistName = "", // This will lead to empty artistNames and artistIds
            composer = "",
            albumArtist = "",
            bpm = null,  // Explicit constructor args for emptySong
            isLocal = false
        )
    }
}