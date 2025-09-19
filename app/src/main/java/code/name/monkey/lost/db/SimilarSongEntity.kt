package code.name.monkey.lost.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "similar_songs_data",
    // foreignKeys = [...] has been removed
    indices = [
        Index(value = ["original_song_id"]),
        Index(value = ["song_id"]),
        Index(value = ["original_song_id", "song_id"], unique = true)
    ]
)
data class SimilarSongEntity(
    @PrimaryKey(autoGenerate = true)@ColumnInfo(name = "entry_id")
    val entryId: Long = 0,

    @ColumnInfo(name = "original_song_id")
    val originalSongId: Long, // Logically refers to an ID in SongEntity

    @ColumnInfo(name = "song_id")
    val songId: Long, // Logically refers to an ID in SongEntity (ID of this similar song)

    val title: String,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int,
    val year: Int,
    val duration: Long,
    val data: String,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "album_name")
    val albumName: String,
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    @ColumnInfo(name = "artist_name")
    val artistName: String,
    val composer: String?,
    @ColumnInfo(name = "album_artist")
    val albumArtist: String?
)
