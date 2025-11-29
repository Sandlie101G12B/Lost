package code.name.monkey.lost.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `songs` (`id` INTEGER NOT NULL, `title` TEXT NOT NULL, `trackNumber` INTEGER NOT NULL, `year` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `data` TEXT NOT NULL, `dateModified` INTEGER NOT NULL, `albumId` INTEGER NOT NULL, `albumName` TEXT NOT NULL, `artistId` INTEGER NOT NULL, `artistName` TEXT NOT NULL, `composer` TEXT, `albumArtist` TEXT, `isLocal` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `format` (
                `id` TEXT NOT NULL,
                `itag` INTEGER NOT NULL,
                `mimeType` TEXT NOT NULL,
                `codecs` TEXT NOT NULL,
                `bitrate` INTEGER NOT NULL,
                `sampleRate` INTEGER,
                `contentLength` INTEGER NOT NULL,
                `loudnessDb` REAL,
                `playbackUrl` TEXT,
                PRIMARY KEY(`id`)
            )
        """)
    }
}

val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS songs")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `downloaded_songs` (
                `id` TEXT NOT NULL,
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
                `album_artist` TEXT,
                `dateDownload` INTEGER,
                `isDownloaded` INTEGER NOT NULL,
                `thumbnailUrl` TEXT,
                `inLibrary` INTEGER,
                PRIMARY KEY(`id`)
            )
        """)
    }
}

val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `SongEntity` ADD COLUMN `dateDownload` INTEGER")
        db.execSQL("ALTER TABLE `SongEntity` ADD COLUMN `isDownloaded` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `SongEntity` ADD COLUMN `thumbnailUrl` TEXT")
        db.execSQL("ALTER TABLE `SongEntity` ADD COLUMN `inLibrary` INTEGER")
        db.execSQL("ALTER TABLE `SongEntity` ADD COLUMN `ytID` TEXT")
    }
}

val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloaded_songs` ADD COLUMN `streamUrl` TEXT")
        db.execSQL("ALTER TABLE `SongEntity` ADD COLUMN `streamUrl` TEXT")
    }
}

val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `HistoryEntity` ADD COLUMN `streamUrl` TEXT")
        db.execSQL("ALTER TABLE `similar_songs_data` ADD COLUMN `streamUrl` TEXT")
        db.execSQL("ALTER TABLE `PlayCountEntity` ADD COLUMN `streamUrl` TEXT")
    }
}
