package code.name.monkey.lost.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PlaylistEntity::class, SongEntity::class, HistoryEntity::class, PlayCountEntity::class, SimilarSongEntity::class, FormatEntity::class, DownloadedSongsEntity::class],
    version = 31, // Incremented version
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LostDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playCountDao(): PlayCountDao
    abstract fun historyDao(): HistoryDao
    abstract fun similarSongDao(): SimilarSongDao
    abstract fun songsDao(): DownloadedSongsDao
}
