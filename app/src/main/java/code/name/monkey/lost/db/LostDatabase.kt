package code.name.monkey.lost.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PlaylistEntity::class, SongEntity::class, HistoryEntity::class, PlayCountEntity::class, SimilarSongEntity::class],
    version = 25, // We'll need to increment this when we add the migration
    exportSchema = false
)
abstract class LostDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playCountDao(): PlayCountDao
    abstract fun historyDao(): HistoryDao
    abstract fun similarSongDao(): SimilarSongDao // Added this line
}
