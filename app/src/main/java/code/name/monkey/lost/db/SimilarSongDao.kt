package code.name.monkey.lost.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SimilarSongDao {

    /**
     * Inserts a similar song entry. If an entry linking the same originalSongId
     * to the same songId already exists, it will be replaced due to Upsert.
     */
    @Upsert
    suspend fun addSimilarSong(similarSongEntity: SimilarSongEntity)

    /**
     * Retrieves all songs marked as similar to the given originalSongId.
     * Returns LiveData for observing changes.
     */
    @Query("SELECT * FROM similar_songs_data WHERE original_song_id = :originalSongId")
    fun getSimilarSongs(originalSongId: Long): LiveData<List<SimilarSongEntity>>

    /**
     * Retrieves a list of all songs marked as similar to the given originalSongId.
     * This is a suspend function for one-time fetches.
     */
    @Query("SELECT * FROM similar_songs_data WHERE original_song_id = :originalSongId")
    suspend fun getSimilarSongsList(originalSongId: Long): List<SimilarSongEntity>

    /**
     * Removes a specific similar song relationship.
     */
    @Query("DELETE FROM similar_songs_data WHERE original_song_id = :originalSongId AND song_id = :similarSongIdToRemove")
    suspend fun removeSimilarSong(originalSongId: Long, similarSongIdToRemove: Long)

    /**
     * Removes all similar song entries associated with a specific originalSongId.
     */
    @Query("DELETE FROM similar_songs_data WHERE original_song_id = :originalSongId")
    suspend fun clearSimilarSongsForOriginal(originalSongId: Long)

    /**
     * Checks if a specific song is already marked as similar to an original song.
     * Returns the entity if it exists, otherwise null.
     */
    @Query("SELECT * FROM similar_songs_data WHERE original_song_id = :originalSongId AND song_id = :potentialSimilarSongId LIMIT 1")
    suspend fun findSimilarSongEntry(originalSongId: Long, potentialSimilarSongId: Long): SimilarSongEntity?

    /**
     * Deletes all entries from the similar_songs_data table.
     * Use with caution.
     */
    @Query("DELETE FROM similar_songs_data")
    suspend fun clearAllSimilarSongs()
}
