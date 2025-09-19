package code.name.monkey.lost.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "similar_songs_data", // Using a distinct table name
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"], // SongEntity's main identifier for a song
            childColumns = ["original_song_id"], // The song these entries are similar TO
            onDelete = ForeignKey.CASCADE // If original song is deleted, these entries are removed
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"], // SongEntity's main identifier for a song
            childColumns = ["song_id"],      // The ID of THIS similar song
            onDelete = ForeignKey.CASCADE // If this similar song is deleted from SongEntity, these entries are removed
        )
    ],    indices = [
        Index(value = ["original_song_id"]), // To quickly find all songs similar to an original
        // Ensures a song isn't marked as similar to the same original song multiple times
        Index(value = ["original_song_id", "song_id"], unique = true)
    ]
)
data class SimilarSongEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "entry_id")
    val entryId: Long = 0, // Unique ID for this specific similarity record

    @ColumnInfo(name = "original_song_id")
    val originalSongId: Long, // ID of the song for which this entry is a "similar song"

    // --- Fields representing the actual similar song's details ---
    // These mirror the fields in SongEntity and HistoryEntity
    @ColumnInfo(name = "song_id")
    val songId: Long, // The ID of this similar song (from SongEntity.id)

    val title: String,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int,
    val year: Int,
    val duration: Long,
    val data: String, // File path or URI
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
