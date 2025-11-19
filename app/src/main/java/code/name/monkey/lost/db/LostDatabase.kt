package code.name.monkey.lost.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import code.name.monkey.lost.db.FormatEntity

@Database(
    entities = [PlaylistEntity::class, SongEntity::class, HistoryEntity::class, PlayCountEntity::class, SimilarSongEntity::class, FormatEntity::class, DownloadedSongsEntity::class],
    version = 28, // We'll need to increment this when we add the migration
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
