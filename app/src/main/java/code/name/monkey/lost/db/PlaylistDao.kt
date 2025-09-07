package code.name.monkey.lost.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface PlaylistDao {
    @Insert
    suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long

    @Query("UPDATE PlaylistEntity SET playlist_name = :name WHERE playlist_id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    // Keep existing if used elsewhere, but new one is more specific for single playlist
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name = :name")
    fun playlist(name: String): List<PlaylistEntity> // Existing

    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): PlaylistEntity? // New

    @Query("SELECT * FROM PlaylistEntity")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("DELETE FROM SongEntity WHERE playlist_creator_id = :playlistId")
    suspend fun deletePlaylistSongs(playlistId: Long)

    @Query("DELETE FROM SongEntity WHERE playlist_creator_id = :playlistId AND id = :songId")
    suspend fun deleteSongFromPlaylist(playlistId: Long, songId: Long)

    @Transaction
    @Query("SELECT * FROM PlaylistEntity")
    suspend fun playlistsWithSongs(): List<PlaylistWithSongs>

    @Transaction
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_id= :playlistId")
    fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs> // Existing LiveData for UI

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongsToPlaylist(songEntities: List<SongEntity>)

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId AND id = :songId")
    suspend fun isSongExistsInPlaylist(playlistId: Long, songId: Long): List<SongEntity>

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId ORDER BY song_key desc")
    fun songsFromPlaylist(playlistId: Long): LiveData<List<SongEntity>> // Existing LiveData

    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id = :playlistId ORDER BY song_key desc")
    suspend fun getSongsByPlaylistIdSync(playlistId: Long): List<SongEntity> // New suspend function

    @Delete
    suspend fun deletePlaylist(playlistEntity: PlaylistEntity)

    @Delete
    suspend fun deletePlaylists(playlistEntities: List<PlaylistEntity>)

    @Delete
    suspend fun deletePlaylistSongs(songs: List<SongEntity>)

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM SongEntity ,(SELECT playlist_id FROM PlaylistEntity WHERE playlist_name= :playlistName LIMIT 1) AS playlist WHERE playlist_creator_id= playlist.playlist_id ORDER BY song_key desc")
    fun favoritesSongsLiveData(playlistName: String): LiveData<List<SongEntity>>

    // Existing, can be used by getSongsByPlaylistIdSync if desired, or keep both
    @Query("SELECT * FROM SongEntity WHERE playlist_creator_id= :playlistId ORDER BY song_key desc")
    fun favoritesSongs(playlistId: Long): List<SongEntity>

    @Query("SELECT EXISTS(SELECT * FROM PlaylistEntity WHERE playlist_id = :playlistId)")
    fun checkPlaylistExists(playlistId: Long): LiveData<Boolean>

    // New method to find old automatic playlists
    @Query("SELECT * FROM PlaylistEntity WHERE playlist_name LIKE :likePattern AND playlist_name != :actualName")
    suspend fun getPlaylistsWithNameLikeAndNotName(likePattern: String, actualName: String): List<PlaylistEntity>
}
