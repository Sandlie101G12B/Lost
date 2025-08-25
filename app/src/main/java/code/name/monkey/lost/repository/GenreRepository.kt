package code.name.monkey.lost.repository

import android.content.ContentResolver
import android.database.Cursor
import android.provider.BaseColumns
import android.provider.MediaStore.Audio.Genres
import code.name.monkey.lost.Constants.IS_MUSIC
import code.name.monkey.lost.Constants.baseProjection
import code.name.monkey.lost.extensions.getLong
import code.name.monkey.lost.extensions.getStringOrNull
import code.name.monkey.lost.helper.MetaDataManagerHelper
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData // Added for normalizeGenreNamesInMetaData
import code.name.monkey.lost.util.PreferenceUtil
import java.util.Locale

interface GenreRepository {
    fun genres(query: String): List<Genre>
    fun genres(): List<Genre>
    fun songs(genreId: Long): List<Song>
    fun song(genreId: Long): Song
    fun normalizeGenreNamesInMetaData() // New method signature
}

class RealGenreRepository(
    private val contentResolver: ContentResolver,
    private val songRepository: RealSongRepository

) : GenreRepository {
    private val metaDataManagerHelper = MetaDataManagerHelper // Added
    private val derivedIdToOriginalNameCache: MutableMap<Long, String> = mutableMapOf()
    private val unknownGenreId = -1L

    // Formats a raw genre name for display (e.g., "afro pop" -> "Afro-Pop")
    private fun formatGenreNameForDisplay(genreName: String): String {
        val trimmedName = genreName.trim()
        if (trimmedName.isEmpty()) return "" // Return empty if input is blank after trim
        return trimmedName
            .replace("&", "and") // Replace & with 'and'
            .replace(Regex("-"), " ")
            .split(Regex("\\s+")) // Split by one or more spaces
            .joinToString("-") { word ->
                word.lowercase(Locale.ROOT)
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
    }

    // Processes a display-formatted genre name for ID generation (e.g., "Afro-Pop" -> "afropop")
    private fun processGenreNameForId(displayFormattedGenreName: String): String {
        // Input is already display-formatted (e.g., "Afro-Pop")
        return displayFormattedGenreName.lowercase(Locale.ROOT).replace("-", "")
    }

    // Generates a Long ID from the processed name
    private fun generateIdFromProcessedName(processedName: String): Long {
        // Using hashCode. Note: This can have collisions for different strings.
        // If collisions are an issue, a more robust unique string ID generator would be needed.
        return processedName.hashCode().toLong()
    }
    
    // Splits a genre string (e.g., from MediaStore) by common delimiters
    private fun splitGenreString(genreString: String?): List<String> {
        return genreString?.split("[,/]".toRegex())?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private fun getAllSongsFromRepository(): List<Song> {
        val cursor = songRepository.makeSongCursor(
            selection = null,
            selectionValues = null,
            ignoreBlacklist = true // Fetch all songs for a comprehensive genre calculation
        )
        return songRepository.songs(cursor)
    }

    override fun genres(): List<Genre> {
        val allSongs = getAllSongsFromRepository()
        val songMetaDataList = metaDataManagerHelper.getSongMetaDataList()
        val songDataToMetaMap = songMetaDataList.associateBy { it.file }

        // Use the display-formatted name as the key for aggregation
        val genreNameToCombinedSongsMap: MutableMap<String, MutableSet<Song>> = mutableMapOf()

        // 1. Process MediaStore Genres
        makeGenreCursor()?.use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val mediaStoreGenreId = cursor.getLong(Genres._ID) // Original MediaStore ID for song fetching
                    val mediaStoreGenreNameString = cursor.getStringOrNull(Genres.NAME)
                    val songsInThisMediaStoreEntry = songRepository.songs(makeGenreSongCursor(mediaStoreGenreId))

                    val rawIndividualMediaStoreNames = splitGenreString(mediaStoreGenreNameString)
                    rawIndividualMediaStoreNames.forEach { rawName ->
                        if (rawName.isNotBlank()) {
                            val displayableName = formatGenreNameForDisplay(rawName)
                            genreNameToCombinedSongsMap.getOrPut(displayableName) { mutableSetOf() }.addAll(songsInThisMediaStoreEntry)
                        }
                    }
                } while (cursor.moveToNext())
            }
        }

        // 2. Process SongMetaData Genres
        allSongs.forEach { song ->
            songDataToMetaMap[song.data]?.genre?.forEach { rawMetaGenreName ->
                if (rawMetaGenreName.isNotBlank()) {
                    val displayableName = formatGenreNameForDisplay(rawMetaGenreName)
                    genreNameToCombinedSongsMap.getOrPut(displayableName) { mutableSetOf() }.add(song)
                }
            }
        }

        // 3. Construct Genre objects using new ID scheme
        val resultGenres = mutableListOf<Genre>()
        derivedIdToOriginalNameCache.clear() // Clear cache before repopulating

        genreNameToCombinedSongsMap.forEach { (displayableNameFromMap, songsSet) ->
            if (songsSet.isNotEmpty()) {
                val processedNameForId = processGenreNameForId(displayableNameFromMap)
                val derivedId = generateIdFromProcessedName(processedNameForId)
                
                // Store mapping from new ID to the *displayable* name
                derivedIdToOriginalNameCache[derivedId] = displayableNameFromMap
                resultGenres.add(Genre(derivedId, displayableNameFromMap, songsSet.size))
            }
        }
        
        // 4. Add "Unknown Genre" category
        // These are songs not categorized by MediaStore OR by any specific metadata genre
        val songsWithExplicitGenres = genreNameToCombinedSongsMap.values.flatten().toSet()
        val songsWithNoExplicitGenre = allSongs.filterNot { songsWithExplicitGenres.contains(it) }

        if (songsWithNoExplicitGenre.isNotEmpty()) {
            val unknownGenreName = "Unknown Genre" // Not formatted
            // Ensure "Unknown Genre" is added only once with its dedicated ID
            if (resultGenres.none { it.id == unknownGenreId || it.name.equals(unknownGenreName, ignoreCase = false) }) {
                 resultGenres.add(Genre(unknownGenreId, unknownGenreName, songsWithNoExplicitGenre.size))
                 derivedIdToOriginalNameCache[unknownGenreId] = unknownGenreName // Also cache it
            }
        }

        return resultGenres.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    

    override fun genres(query: String): List<Genre> {
        val allGenres = genres() // Get the fully processed list
        if (query.isEmpty()) return allGenres

        // Format the query string in the same way genre names are displayed for a better direct match
        val formattedQuery = formatGenreNameForDisplay(query)
        
        return allGenres.filter { 
            // Check against the displayable name
            it.name.contains(query, ignoreCase = true) || // User might type "afro pop"
            it.name.contains(formattedQuery, ignoreCase = true) // or "Afro-Pop"
        }
    }

    override fun songs(genreId: Long): List<Song> {
        val resultingSongs = mutableSetOf<Song>() // Use a Set to handle duplicates automatically

        if (genreId == unknownGenreId) {
            // Logic for "Unknown Genre"
            val allSongs = getAllSongsFromRepository()
            val songMetaDataList = metaDataManagerHelper.getSongMetaDataList()
            val songDataToMetaMap = songMetaDataList.associateBy { it.file }
            val explicitlyCategorizedSongs = mutableSetOf<Song>()

            // Songs categorized by MediaStore
            makeGenreCursor()?.use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val mediaStoreId = cursor.getLong(Genres._ID)
                        val songsInEntry = songRepository.songs(makeGenreSongCursor(mediaStoreId))
                        explicitlyCategorizedSongs.addAll(songsInEntry)
                    } while (cursor.moveToNext())
                }
            }

            // Songs categorized by SongMetaData
            allSongs.forEach { song ->
                songDataToMetaMap[song.data]?.genre?.forEach { metaGenreName ->
                    if (metaGenreName.isNotBlank()) { // A non-blank genre in metadata means it's categorized
                        explicitlyCategorizedSongs.add(song) 
                    }
                }
            }
            val unknownGenreSongs = allSongs.filterNot { explicitlyCategorizedSongs.contains(it) }
            resultingSongs.addAll(unknownGenreSongs)

        } else {
            // For specific genres (ID derived from formatted name)
            val displayableOriginalGenreName = derivedIdToOriginalNameCache[genreId]
            
            if (displayableOriginalGenreName != null) {
                val allSongs = getAllSongsFromRepository()
                val songMetaDataList = metaDataManagerHelper.getSongMetaDataList()
                val songDataToMetaMap = songMetaDataList.associateBy { it.file }

                // Check songs from MediaStore entries
                makeGenreCursor()?.use { msCursor ->
                    if (msCursor.moveToFirst()) {
                        do {
                            val mediaStoreId = msCursor.getLong(Genres._ID)
                            val mediaStoreGenreNameString = msCursor.getStringOrNull(Genres.NAME)
                            val rawIndividualMediaStoreNames = splitGenreString(mediaStoreGenreNameString)

                            rawIndividualMediaStoreNames.forEach { rawName ->
                                if (rawName.isNotBlank()) {
                                    val formattedMediaStoreName = formatGenreNameForDisplay(rawName)
                                    // Compare displayable names
                                    if (formattedMediaStoreName.equals(displayableOriginalGenreName, ignoreCase = false)) { // Case sensitive match after formatting
                                        songRepository.songs(makeGenreSongCursor(mediaStoreId))?.let { resultingSongs.addAll(it) }
                                    }
                                }
                            }
                        } while (msCursor.moveToNext())
                    }
                }

                // Check songs from SongMetaData
                allSongs.forEach { song ->
                    songDataToMetaMap[song.data]?.genre?.forEach { rawMetaGenre ->
                        if (rawMetaGenre.isNotBlank()) {
                            val formattedMetaGenre = formatGenreNameForDisplay(rawMetaGenre)
                            // Compare displayable names
                            if (formattedMetaGenre.equals(displayableOriginalGenreName, ignoreCase = false)) { // Case sensitive match after formatting
                                resultingSongs.add(song)
                            }
                        }
                    }
                }
            }
        }
        return resultingSongs.toList().sortedBy { it.title.lowercase(Locale.ROOT) }
    }


    override fun song(genreId: Long): Song {
        return songs(genreId).firstOrNull() ?: Song.emptySong
    }

    private fun getSongCount(mediaStoreGenreId: Long): Int {
        // This is a helper, ensure it's used appropriately or integrated if needed
        contentResolver.query(
            Genres.Members.getContentUri("external", mediaStoreGenreId),
            arrayOf(BaseColumns._ID), 
            IS_MUSIC, 
            null,
            null
        )?.use {
            return it.count
        }
        return 0
    }
    
    // This method's direct utility might be reduced given the new ID scheme.
    // It's kept for potential direct MediaStore queries if ever needed.
    private fun getGenreFromCursor(cursor: Cursor): Genre {
        val originalMediaStoreId = cursor.getLong(Genres._ID) // Still the MediaStore ID
        val nameString = cursor.getStringOrNull(Genres.NAME)
        
        // For a representative name, take the first split part and format it
        val representativeName = splitGenreString(nameString).firstOrNull() ?: "Unknown Genre"
        val displayFormattedName = formatGenreNameForDisplay(representativeName)
        
        // Generate ID based on this representative display name
        val processedNameForId = processGenreNameForId(displayFormattedName)
        val derivedId = generateIdFromProcessedName(processedNameForId)

        val songCount = getSongCount(originalMediaStoreId) // Count is for the original MediaStore entry
        
        return Genre(derivedId, displayFormattedName, songCount)
    }
    
    // Kept for clarity on its specific purpose.
    // The main "Unknown Genre" logic in genres() and songs() is more comprehensive.
    private fun getSongsWithNoMediaStoreGenre(): List<Song> {
        val selection = BaseColumns._ID + " NOT IN (SELECT DISTINCT " + Genres.Members.AUDIO_ID + " FROM audio_genres_map)"
        val cursor = songRepository.makeSongCursor(
            selection = selection,
            selectionValues = null
        )
        return songRepository.songs(cursor)
    }

    fun makeGenreSongCursor(mediaStoreGenreId: Long): Cursor? {
        if (mediaStoreGenreId <= 0) return null // MediaStore IDs are positive
        return try {
            contentResolver.query(
                Genres.Members.getContentUri("external", mediaStoreGenreId),
                baseProjection, // Assuming baseProjection is appropriate for songs
                IS_MUSIC, // Filter for music files
                null,
                PreferenceUtil.songSortOrder // Use preferred sort order for songs
            )
        } catch (_: SecurityException) {
            null
        }
    }
    
    private fun makeGenreCursor(): Cursor? {
        val projection = arrayOf(Genres._ID, Genres.NAME)
        return try {
            contentResolver.query(
                Genres.EXTERNAL_CONTENT_URI,
                projection,
                null, // No selection, get all genres
                null,
                PreferenceUtil.genreSortOrder // Use preferred sort order for genres
            )
        } catch (_: SecurityException) {
            null
        }
    }

    // This query method against MediaStore for specific genre names might be less effective
    // now that primary filtering happens on the fully constructed and formatted list.
    // It's kept for completeness but `genres(query: String)` is the main query interface.
    private fun makeGenreCursor(query: String): Cursor? {
        if (query.isEmpty()) return makeGenreCursor() // Delegate to no-arg version if query is empty
        val projection = arrayOf(Genres._ID, Genres.NAME)
        // Note: Querying MediaStore directly with a formatted name might not yield expected results
        // if MediaStore stores names differently (e.g. "Pop / Rock" vs "Pop-Rock").
        // The primary genres(query: String) method handles this better.
        return try {
            contentResolver.query(
                Genres.EXTERNAL_CONTENT_URI,
                projection,
                Genres.NAME + " LIKE ?", // Basic LIKE query on MediaStore's names
                arrayOf("%$query%"),    // Unformatted query here, as MediaStore doesn't know our format
                PreferenceUtil.genreSortOrder  // Use preferred sort order for genres
            )
        } catch (_: SecurityException) {
            null
        }
    }

    // New method to normalize genre names in the metadata file
    override fun normalizeGenreNamesInMetaData() {
        val currentMetaDataList = metaDataManagerHelper.getSongMetaDataList()
        val updatedMetaDataList = mutableListOf<SongMetaData>()
        var changesMade = false

        currentMetaDataList.forEach { songMeta ->
            val originalGenres = songMeta.genre
            val formattedAndDeduplicatedGenres = originalGenres
                .map { formatGenreNameForDisplay(it) } // Format each genre name
                .filter { it.isNotBlank() }           // Remove any blank genres
                .distinct()                           // Ensure uniqueness
                .sorted()                             // Optional: sort for consistent order

            // More robust check for changes
            val originalFormattedAndDeduplicated = originalGenres
                .map { formatGenreNameForDisplay(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            if (originalFormattedAndDeduplicated != formattedAndDeduplicatedGenres) {
                 updatedMetaDataList.add(songMeta.copy(genre = formattedAndDeduplicatedGenres))
                changesMade = true
            } else {
                updatedMetaDataList.add(songMeta) // No change, add original
            }
        }

        if (changesMade) {
            metaDataManagerHelper.saveSongMetaDataList(updatedMetaDataList)
            // Log.i("RealGenreRepository", "Normalized genre names in metadata.")
        } else {
            // Log.i("RealGenreRepository", "Genre names in metadata are already normalized.")
        }
    }
}
