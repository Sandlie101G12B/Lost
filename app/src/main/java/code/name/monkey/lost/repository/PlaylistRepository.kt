@file:Suppress("DEPRECATION")

package code.name.monkey.lost.repository

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore.Audio.Playlists.*
import android.provider.MediaStore.Audio.AudioColumns
import androidx.core.database.getStringOrNull
import code.name.monkey.lost.Constants
import code.name.monkey.lost.db.PlaylistDao
import code.name.monkey.lost.db.PlaylistEntity
import code.name.monkey.lost.db.SongEntity
import code.name.monkey.lost.helper.AutomaticPlaylistGenerator
import code.name.monkey.lost.helper.MetaDataManagerHelper
import code.name.monkey.lost.model.Playlist
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Interface remains the same
interface PlaylistRepository {
    fun playlist(cursor: Cursor?): Playlist
    suspend fun searchPlaylist(query: String): List<Playlist>
    suspend fun playlist(playlistName: String): Playlist
    suspend fun playlists(): List<Playlist>
    fun playlists(cursor: Cursor?): List<Playlist> // MediaStore specific
    suspend fun favoritePlaylist(playlistName: String): List<Playlist>
    suspend fun deletePlaylist(playlistId: Long, playlistName: String?)
    suspend fun playlist(playlistId: Long): Playlist
    suspend fun playlistSongs(playlistId: Long, playlistNameHint: String? = null): List<Song>
}

// Configuration for an automatic playlist type
private data class AutomaticPlaylistDefinition(
    val baseName: String,
    val generator: suspend (meta: List<SongMetaData>, songs: List<Song>) -> AutomaticPlaylistGenerator.PlaylistBlueprint?
)

@Suppress("Deprecation") // For MediaStore.Audio.Playlists usage
class RealPlaylistRepository(
    private val contentResolver: ContentResolver // ContentResolver is typically context-dependent
) : PlaylistRepository, KoinComponent { // Added KoinComponent

    // Injected dependencies
    private val playlistDao: PlaylistDao by inject()
    private val songRepository: SongRepository by inject()
    private val metaDataManagerHelper: MetaDataManagerHelper by inject()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val autoPlaylistUpdateMutex = Mutex()
    private var lastAutoDbCheckMs: Long = 0
    private val autoDbCheckCooldownMs = TimeUnit.MINUTES.toMillis(60)

    companion object {
        private const val AUTO_SUFFIX = " [AUTO]" // Changed from AUTO_PREFIX
        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private const val MEDIA_STORE_PLAYLIST_ID_THRESHOLD: Long = 0
    }

    private val automaticPlaylistDefinitions: List<AutomaticPlaylistDefinition> by lazy {
        listOf(
            AutomaticPlaylistDefinition("Throwback 2000") { meta, songs -> AutomaticPlaylistGenerator.generateThrowbackPlaylist(meta, songs, 2000) },
            AutomaticPlaylistDefinition("Throwback 1990") { meta, songs -> AutomaticPlaylistGenerator.generateThrowbackPlaylist(meta, songs, 1990) },
            AutomaticPlaylistDefinition("Decade Rewind 80s") { meta, songs -> AutomaticPlaylistGenerator.generateDecadeRewindPlaylist(meta, songs, 1980, 1989) },
            AutomaticPlaylistDefinition("High Energy") { meta, songs -> AutomaticPlaylistGenerator.generateHighEnergyPlaylist(meta, songs) },
            AutomaticPlaylistDefinition("Liked Songs Radio") { meta, songs -> AutomaticPlaylistGenerator.generateLikedSongsRadio(meta, songs) }
            // Add more definitions as needed
        )
    }

    init {
        // repositoryScope.launch { ensureDailyAutomaticPlaylistsInDb() } // Consider calling this strategically
    }

    private fun getCurrentDateString(): String = DATE_FORMATTER.format(Date())

    private fun songToSongEntity(song: Song, playlistDbId: Long): SongEntity {
        return SongEntity(
            playlistCreatorId = playlistDbId,
            id = song.id,
            title = song.title,
            trackNumber = song.trackNumber,
            year = song.year,
            duration = song.duration,
            data = song.data,
            dateModified = song.dateModified,
            albumId = song.albumId,
            albumName = song.albumName,
            artistId = song.artistId,
            artistName = song.artistName,
            composer = song.composer,
            albumArtist = song.albumArtist // Assuming Song model has albumArtistName
        )
    }

    private fun songEntityToSong(entity: SongEntity): Song {
        return Song(
            id = entity.id,
            title = entity.title,
            trackNumber = entity.trackNumber,
            year = entity.year,
            duration = entity.duration,
            data = entity.data,
            dateModified = entity.dateModified,
            albumId = entity.albumId,
            albumName = entity.albumName,
            artistId = entity.artistId,
            artistName = entity.artistName,
            composer = entity.composer ?: "",
            albumArtist = entity.albumArtist ?: ""
        )
    }

    private suspend fun playlistEntityToPlaylist(entity: PlaylistEntity): Playlist {
        return Playlist(id = entity.playListId, name = entity.playlistName)
    }

    private suspend fun resolveSongPathsToSongs(
        songFilePaths: List<String>,
        allLibrarySongs: List<Song>
    ): List<Song> {
        if (songFilePaths.isEmpty() || allLibrarySongs.isEmpty()) {
            return emptyList()
        }
        val librarySongMap = allLibrarySongs.associateBy { it.data }
        return songFilePaths.mapNotNull { path -> librarySongMap[path] }
    }

    private suspend fun ensureDailyAutomaticPlaylistsInDb(forceUpdate: Boolean = false) = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        if (!forceUpdate && (currentTime - lastAutoDbCheckMs < autoDbCheckCooldownMs)) {
            return@withContext
        }

        autoPlaylistUpdateMutex.withLock {
            if (!forceUpdate && (System.currentTimeMillis() - lastAutoDbCheckMs < autoDbCheckCooldownMs)) {
                return@withLock
            }
            lastAutoDbCheckMs = System.currentTimeMillis()

            val currentDateString = getCurrentDateString()
            // Fetch these only once if multiple definitions need them
            val allLibrarySongs by lazy { songRepository.songs() }
            val allSongMetaData by lazy { metaDataManagerHelper.getSongMetaDataList() }

            for (definition in automaticPlaylistDefinitions) {
                // Changed to AUTO_SUFFIX
                val todaysPlaylistName = "${definition.baseName} ($currentDateString)$AUTO_SUFFIX"
                val existingTodayPlaylist = playlistDao.getPlaylistByName(todaysPlaylistName)

                if (existingTodayPlaylist == null || forceUpdate) {
                    if (forceUpdate && existingTodayPlaylist != null) {
                        playlistDao.deletePlaylistSongs(existingTodayPlaylist.playListId)
                    }
                    val blueprint = definition.generator(allSongMetaData, allLibrarySongs)
                    if (blueprint != null && blueprint.songFilePaths.isNotEmpty()) {
                        val songsForPlaylist = resolveSongPathsToSongs(blueprint.songFilePaths, allLibrarySongs)
                        if (songsForPlaylist.isNotEmpty()) {
                            val playlistIdToUse = if (existingTodayPlaylist != null) {
                                existingTodayPlaylist.playListId
                            } else {
                                val newPlaylistEntity = PlaylistEntity(playlistName = todaysPlaylistName)
                                playlistDao.createPlaylist(newPlaylistEntity)
                            }
                            val songEntities = songsForPlaylist.map { songToSongEntity(it, playlistIdToUse) }
                            playlistDao.insertSongsToPlaylist(songEntities) // Assumes this handles conflicts or is preceded by delete
                        }
                    }
                }

                // Clean up old versions for this baseName
                // Changed to AUTO_SUFFIX
                val likePattern = "${definition.baseName} (%$AUTO_SUFFIX"
                val oldPlaylists = playlistDao.getPlaylistsWithNameLikeAndNotName(likePattern, todaysPlaylistName)
                for (oldPlaylist in oldPlaylists) {
                    playlistDao.deletePlaylistSongs(oldPlaylist.playListId)
                    playlistDao.deletePlaylist(oldPlaylist)
                }
            }
        }
    }

    override fun playlist(cursor: Cursor?): Playlist {
        // MediaStore specific
        return cursor.use {
            if (it?.moveToFirst() == true) getPlaylistFromMediaStoreCursorImpl(it) else Playlist.empty
        }
    }

    override fun playlists(cursor: Cursor?): List<Playlist> {
        // MediaStore specific
        val playlists = mutableListOf<Playlist>()
        cursor.use { c ->
            if (c != null && c.moveToFirst()) {
                do {
                    playlists.add(getPlaylistFromMediaStoreCursorImpl(c))
                } while (c.moveToNext())
            }
        }
        return playlists
    }

    override suspend fun searchPlaylist(query: String): List<Playlist> = withContext(Dispatchers.IO) {
        ensureDailyAutomaticPlaylistsInDb()
        val daoPlaylists = playlistDao.playlists()
            .filter { it.playlistName.contains(query, ignoreCase = true) }
            .map { playlistEntityToPlaylist(it) }

        val mediaStorePlaylists = mutableListOf<Playlist>()
        makePlaylistCursor("$NAME LIKE ?", arrayOf("%$query%")).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val msPlaylist = getPlaylistFromMediaStoreCursorImpl(cursor)
                    if (daoPlaylists.none { it.name == msPlaylist.name }) {
                        mediaStorePlaylists.add(msPlaylist)
                    }
                } while (cursor.moveToNext())
            }
        }
        return@withContext (daoPlaylists + mediaStorePlaylists).distinctBy { it.name }
    }

    override suspend fun playlist(playlistName: String): Playlist = withContext(Dispatchers.IO) {
        ensureDailyAutomaticPlaylistsInDb()
        playlistDao.getPlaylistByName(playlistName)?.let { return@withContext playlistEntityToPlaylist(it) }

        makePlaylistCursor("$NAME=?", arrayOf(playlistName)).use { cursor ->
            if (cursor?.moveToFirst() == true) return@withContext getPlaylistFromMediaStoreCursorImpl(cursor)
        }
        return@withContext Playlist.empty
    }

    override suspend fun playlists(): List<Playlist> = withContext(Dispatchers.IO) {
        ensureDailyAutomaticPlaylistsInDb()
        val daoPlaylists = playlistDao.playlists().map { playlistEntityToPlaylist(it) }

        val mediaStorePlaylists = mutableListOf<Playlist>()
        makePlaylistCursor(null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val msPlaylist = getPlaylistFromMediaStoreCursorImpl(cursor)
                    if (daoPlaylists.none { it.name == msPlaylist.name }) {
                        mediaStorePlaylists.add(msPlaylist)
                    }
                } while (cursor.moveToNext())
            }
        }
        return@withContext (daoPlaylists + mediaStorePlaylists).distinctBy { it.name }
    }

    override suspend fun favoritePlaylist(playlistName: String): List<Playlist> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Playlist>()
        playlistDao.getPlaylistByName(playlistName)?.let { results.add(playlistEntityToPlaylist(it)) }

        makePlaylistCursor("$NAME=?", arrayOf(playlistName)).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val msPlaylist = getPlaylistFromMediaStoreCursorImpl(cursor)
                    if (results.none { it.id == msPlaylist.id }) { results.add(msPlaylist) }
                } while (cursor.moveToNext())
            }
        }
        return@withContext results.distinctBy { it.id }
    }

    override suspend fun deletePlaylist(playlistId: Long, playlistName: String?) = withContext(Dispatchers.IO) {
        // Changed to AUTO_SUFFIX and endsWith
        if (playlistName != null && playlistName.endsWith(AUTO_SUFFIX)) {
            return@withContext // Or handle differently if specific old auto playlists can be user-deleted.
        }

        // Try DAO delete first
        val playlistEntity = playlistDao.getPlaylistByName(playlistName ?: "###INVALID_NAME_FOR_DAO_LOOKUP_BY_ID_ALONE###") // Imperfect for ID only
        // Ideally, DAO would have getPlaylistById(Long) and deleteById(Long)
        var deletedFromDao = false
        if (playlistEntity != null && playlistEntity.playListId == playlistId) {
            playlistDao.deletePlaylistSongs(playlistEntity.playListId)
            playlistDao.deletePlaylist(playlistEntity)
            deletedFromDao = true
        }


        // Try MediaStore delete if ID is positive (heuristic) and not already handled by DAO
        if (playlistId > MEDIA_STORE_PLAYLIST_ID_THRESHOLD) {
            try {
                val rowsDeleted = contentResolver.delete(EXTERNAL_CONTENT_URI, "$_ID=?", arrayOf(playlistId.toString()))
            } catch (_: SecurityException) {

            }
        }
    }

    override suspend fun playlist(playlistId: Long): Playlist = withContext(Dispatchers.IO) {
        ensureDailyAutomaticPlaylistsInDb()
        // TODO: Add getPlaylistById(id: Long): PlaylistEntity? to PlaylistDao for efficiency
        val daoEntity = playlistDao.playlists().find { it.playListId == playlistId }
        if (daoEntity != null) {
            return@withContext playlistEntityToPlaylist(daoEntity)
        }

        if (playlistId > MEDIA_STORE_PLAYLIST_ID_THRESHOLD) {
            makePlaylistCursor("$_ID=?", arrayOf(playlistId.toString())).use { cursor ->
                if (cursor?.moveToFirst() == true) return@withContext getPlaylistFromMediaStoreCursorImpl(cursor)
            }
        }
        return@withContext Playlist.empty
    }

    override suspend fun playlistSongs(playlistId: Long, playlistNameHint: String?): List<Song> = withContext(Dispatchers.IO) {
        ensureDailyAutomaticPlaylistsInDb()
        val daoSongs = playlistDao.getSongsByPlaylistIdSync(playlistId)
        if (daoSongs.isNotEmpty()) {
            return@withContext daoSongs.map { songEntityToSong(it) }
        }

        if (playlistId > MEDIA_STORE_PLAYLIST_ID_THRESHOLD) {
            val songs = mutableListOf<Song>()
            makePlaylistSongCursor(playlistId).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        songs.add(getPlaylistSongFromMediaStoreCursorImpl(cursor, playlistId))
                    } while (cursor.moveToNext())
                    return@withContext songs
                }
            }
        }
        return@withContext emptyList()
    }

    private fun getPlaylistFromMediaStoreCursorImpl(cursor: Cursor): Playlist {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(_ID))
        val name = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(NAME))
        return Playlist(id, name ?: "Unknown MediaStore Playlist")
    }

    private fun getPlaylistSongFromMediaStoreCursorImpl(cursor: Cursor, playlistIdForContext: Long): Song {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Members.AUDIO_ID))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(AudioColumns.TITLE))
        val trackNumber = cursor.getInt(cursor.getColumnIndexOrThrow(AudioColumns.TRACK))
        val year = cursor.getInt(cursor.getColumnIndexOrThrow(AudioColumns.YEAR))
        val duration = cursor.getLong(cursor.getColumnIndexOrThrow(AudioColumns.DURATION))
        val data = cursor.getString(cursor.getColumnIndexOrThrow(Constants.DATA)) // Corrected to use Constants.DATA
        val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(AudioColumns.DATE_MODIFIED))
        val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(AudioColumns.ALBUM_ID))
        val albumName = cursor.getString(cursor.getColumnIndexOrThrow(AudioColumns.ALBUM))
        val artistId = cursor.getLong(cursor.getColumnIndexOrThrow(AudioColumns.ARTIST_ID))
        val artistName = cursor.getString(cursor.getColumnIndexOrThrow(AudioColumns.ARTIST))
        val composer = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(AudioColumns.COMPOSER))
        // album_artist is not standard in Playlists.Members, derive or use artistName
        val albumArtist = artistName // Simplified, actual logic might be more complex if "album_artist" is available elsewhere

        return Song(
            id = id, title = title, trackNumber = trackNumber, year = year, duration = duration,
            data = data, dateModified = dateModified, albumId = albumId, albumName = albumName,
            artistId = artistId, artistName = artistName, composer = composer ?: "",
            albumArtist = albumArtist ?: ""
        )
    }

    private fun makePlaylistCursor(selection: String?, values: Array<String>?): Cursor? {
        return try {
            contentResolver.query(
                EXTERNAL_CONTENT_URI,
                arrayOf(_ID, NAME),
                selection,
                values,
                DEFAULT_SORT_ORDER
            )
        } catch (e: SecurityException) {
            null
        }
    }

    private fun makePlaylistSongCursor(playlistId: Long): Cursor? {
        return try {
            contentResolver.query(
                Members.getContentUri("external", playlistId),
                arrayOf(
                    Members.AUDIO_ID, AudioColumns.TITLE, AudioColumns.TRACK, AudioColumns.YEAR,
                    AudioColumns.DURATION, Constants.DATA, AudioColumns.DATE_MODIFIED,
                    AudioColumns.ALBUM_ID, AudioColumns.ALBUM, AudioColumns.ARTIST_ID,
                    AudioColumns.ARTIST, AudioColumns.COMPOSER
                ),
                Constants.IS_MUSIC,
                null,
                Members.DEFAULT_SORT_ORDER
            )
        } catch (e: SecurityException) {
            null
        }
    }
}