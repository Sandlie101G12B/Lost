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
import code.name.monkey.lost.model.SongMetaData
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
    private val genreCache: MutableMap<String, List<Genre>> = mutableMapOf() // New genre cache
    private val unknownGenreId = -1L

    companion object {
        private const val ALL_GENRES_CACHE_KEY = "_ALL_GENRES_"
    }

    // Formats a raw genre name for display (e.g., "afro pop" -> "Afro-Pop")
    private fun formatGenreNameForDisplay(genreName: String): String {
        val trimmedName = genreName.trim()
        if (trimmedName.isEmpty()) return "" // Return empty if input is blank after trim
        return trimmedName
            .lowercase()
            .replace("afrohouse", "Afro-house")
            .replace("afropop", "Afro-pop")
            .replace("afroswing", "Afro-swing")
            .replace("afrosoul", "Afro-soul")
            .replace("afrofunk", "Afro-funk")
            .replace("afrosinger", "Afro-singer")
            .replace("afrofusion", "Afro-fusion")
            .replace("afrobeat", "Afro-beat")
            .replace("afrobeats", "Afro-beats")
            .replace("afrotrap", "Afro-trap")
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
        genreCache[ALL_GENRES_CACHE_KEY]?.let {
            return it
        }

        // Cache miss, proceed to compute, clear relevant caches first
        derivedIdToOriginalNameCache.clear()
        // genreCache.clear() // Clear the whole genre cache or just this key?
        // Clearing the whole cache here might be too aggressive if other queries are cached.
        // However, since this method recalculates derived IDs which are used by `songs(genreId)`,
        // and it forms the basis for queried genres, clearing it ensures consistency.
        // Let's clear the entire genre cache as `derivedIdToOriginalNameCache` is also fully repopulated.
        genreCache.clear()
        normalizeGenreNamesInMetaData()

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
        // derivedIdToOriginalNameCache was cleared above

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
        val songsWithExplicitGenres = genreNameToCombinedSongsMap.values.flatten().toSet()
        val songsWithNoExplicitGenre = allSongs.filterNot { songsWithExplicitGenres.contains(it) }

        if (songsWithNoExplicitGenre.isNotEmpty()) {
            val unknownGenreName = "Unknown Genre" // Not formatted
            if (resultGenres.none { it.id == unknownGenreId || it.name.equals(unknownGenreName, ignoreCase = false) }) {
                resultGenres.add(Genre(unknownGenreId, unknownGenreName, songsWithNoExplicitGenre.size))
                derivedIdToOriginalNameCache[unknownGenreId] = unknownGenreName // Also cache it
            }
        }

        val sortedResultGenres = resultGenres.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        genreCache[ALL_GENRES_CACHE_KEY] = sortedResultGenres
        return sortedResultGenres
    }


    override fun genres(query: String): List<Genre> {
        if (query.isEmpty()) {
            return genres() // This will use the ALL_GENRES_CACHE_KEY
        }

        genreCache[query]?.let {
            return it
        }

        val allGenres = genres() // Ensures base list is cached/retrieved
        val formattedQuery = formatGenreNameForDisplay(query)

        val filteredGenres = allGenres.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.name.contains(formattedQuery, ignoreCase = true)
        }
        genreCache[query] = filteredGenres
        return filteredGenres
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
            // Ensure genres() has been called at least once if derivedIdToOriginalNameCache might be empty
            if (derivedIdToOriginalNameCache.isEmpty() && genreCache[ALL_GENRES_CACHE_KEY] == null) {
                genres() // This populates derivedIdToOriginalNameCache
            }
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
                                    if (formattedMediaStoreName.equals(displayableOriginalGenreName, ignoreCase = false)) {
                                        songRepository.songs(makeGenreSongCursor(mediaStoreId)).let { resultingSongs.addAll(it) }
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
                            if (formattedMetaGenre.equals(displayableOriginalGenreName, ignoreCase = false)) {
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

    private fun getGenreFromCursor(cursor: Cursor): Genre {
        val originalMediaStoreId = cursor.getLong(Genres._ID)
        val nameString = cursor.getStringOrNull(Genres.NAME)
        val representativeName = splitGenreString(nameString).firstOrNull() ?: "Unknown Genre"
        val displayFormattedName = formatGenreNameForDisplay(representativeName)
        val processedNameForId = processGenreNameForId(displayFormattedName)
        val derivedId = generateIdFromProcessedName(processedNameForId)
        val songCount = getSongCount(originalMediaStoreId)
        return Genre(derivedId, displayFormattedName, songCount)
    }

    private fun getSongsWithNoMediaStoreGenre(): List<Song> {
        val selection = BaseColumns._ID + " NOT IN (SELECT DISTINCT " + Genres.Members.AUDIO_ID + " FROM audio_genres_map)"
        val cursor = songRepository.makeSongCursor(
            selection = selection,
            selectionValues = null
        )
        return songRepository.songs(cursor)
    }

    fun makeGenreSongCursor(mediaStoreGenreId: Long): Cursor? {
        if (mediaStoreGenreId <= 0) return null
        return try {
            contentResolver.query(
                Genres.Members.getContentUri("external", mediaStoreGenreId),
                baseProjection,
                IS_MUSIC,
                null,
                PreferenceUtil.songSortOrder
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
                null,
                null,
                PreferenceUtil.genreSortOrder
            )
        } catch (_: SecurityException) {
            null
        }
    }

    private fun makeGenreCursor(query: String): Cursor? {
        if (query.isEmpty()) return makeGenreCursor()
        val projection = arrayOf(Genres._ID, Genres.NAME)
        return try {
            contentResolver.query(
                Genres.EXTERNAL_CONTENT_URI,
                projection,
                Genres.NAME + " LIKE ?",
                arrayOf("%$query%"),
                PreferenceUtil.genreSortOrder
            )
        } catch (_: SecurityException) {
            null
        }
    }

    override fun normalizeGenreNamesInMetaData() {
        val currentMetaDataList = metaDataManagerHelper.getSongMetaDataList()
        val updatedMetaDataList = mutableListOf<SongMetaData>()
        var changesMade = false

        currentMetaDataList.forEach { songMeta ->
            val originalGenres = songMeta.genre
            val formattedAndDeduplicatedGenres = originalGenres
                .map { formatGenreNameForDisplay(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            // Corrected check for changes
            val originalSimplyFormattedAndDeduplicated = originalGenres
                .map { formatGenreNameForDisplay(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            if (originalSimplyFormattedAndDeduplicated != formattedAndDeduplicatedGenres || originalGenres.any { it.trim().isBlank() && it.isNotBlank() } || originalGenres.size != formattedAndDeduplicatedGenres.size) {
                 // A more robust check might be needed if the order of non-distinct original genres matters,
                 // but distinct().sorted() normalizes this for comparison.
                 // The main point is if the *final list of genre strings* changes.
                 // Also, checking if originalGenres content is different from formattedAndDeduplicatedGenres
                if (originalGenres.map { formatGenreNameForDisplay(it).trim() }.filter{it.isNotEmpty()}.distinct().sorted() != formattedAndDeduplicatedGenres) changesMade = true

                updatedMetaDataList.add(songMeta.copy(genre = formattedAndDeduplicatedGenres))

            } else {
                updatedMetaDataList.add(songMeta)
            }
        }

        if (changesMade) {
            metaDataManagerHelper.saveSongMetaDataList(updatedMetaDataList)
            // Clear caches as genre data has changed
            derivedIdToOriginalNameCache.clear()
            genreCache.clear()
        }
    }
}
