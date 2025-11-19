package code.name.monkey.lost.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import code.name.monkey.lost.db.FormatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(downloadedSongsEntity: DownloadedSongsEntity)

    @Query("SELECT * FROM downloaded_songs")
    fun getAllSongs(): Flow<List<DownloadedSongsEntity>>

    @Query("DELETE FROM downloaded_songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormat(format: FormatEntity)

    @Query("SELECT * FROM format WHERE id = :id")
    suspend fun getFormatById(id: Long): FormatEntity

    @Query("SELECT * FROM downloaded_songs WHERE id = :songId")
    suspend fun getSongById(songId: String): DownloadedSongsEntity?

    @Update
    suspend fun updateSong(downloadedSongsEntity: DownloadedSongsEntity)
}
