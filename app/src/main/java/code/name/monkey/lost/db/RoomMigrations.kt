package code.name.monkey.lost.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS LyricsEntity")
        db.execSQL("DROP TABLE IF EXISTS BlackListStoreEntity")
    }
}

val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create the new similar_songs_data table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `similar_songs_data` (
                `entry_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `original_song_id` INTEGER NOT NULL,
                `song_id` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `track_number` INTEGER NOT NULL,
                `year` INTEGER NOT NULL,
                `duration` INTEGER NOT NULL,
                `data` TEXT NOT NULL,
                `date_modified` INTEGER NOT NULL,
                `album_id` INTEGER NOT NULL,
                `album_name` TEXT NOT NULL,
                `artist_id` INTEGER NOT NULL,
                `artist_name` TEXT NOT NULL,
                `composer` TEXT,
                `album_artist` TEXT
            )
        """)

        // Create indices as defined in the SimilarSongEntity
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_similar_songs_data_original_song_id` ON `similar_songs_data` (`original_song_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_similar_songs_data_song_id` ON `similar_songs_data` (`song_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_similar_songs_data_original_song_id_song_id` ON `similar_songs_data` (`original_song_id`, `song_id`)")
    }
}
