package code.name.monkey.lost.helper

import android.content.Context
import android.util.Log
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.UserSongInputByNameAndArtists
import code.name.monkey.lost.repository.SongRepository
import code.name.monkey.lost.util.MusicUtil // Import for MusicUtil
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.IOException
import java.util.Locale

object MetaDataManagerHelper : KoinComponent {

    private const val OUTPUT_FILE_NAME = "outputile.txt"
    private var appContext: Context? = null

    @Volatile
    private var cachedSongMetaDataList: List<SongMetaData>? = null
    private val cacheLock = Any()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun applyFavoriteToggleToCache(songToToggle: Song, makeFavorite: Boolean): Boolean {
        println("[MDM_DEBUG] Entering applyFavoriteToggleToCache for song path: ${songToToggle.data}, makeFavorite: $makeFavorite")
        synchronized(cacheLock) {
            println("[MDM_DEBUG] applyFavoriteToggleToCache: Acquired cacheLock.")
            val currentList = getSongMetaDataList().toMutableList()
            val songIndex = currentList.indexOfFirst { it.file == songToToggle.data }
            println("[MDM_DEBUG] applyFavoriteToggleToCache: Found songIndex: $songIndex for path ${songToToggle.data}")

            if (songIndex != -1) {
                val metaData = currentList[songIndex]
                if (metaData.liked != makeFavorite) {
                    println("[MDM_DEBUG] applyFavoriteToggleToCache: Updating liked status for ${metaData.title} to $makeFavorite")
                    currentList[songIndex] = metaData.copy(
                        liked = makeFavorite,
                        likedTimestamp = if (makeFavorite) System.currentTimeMillis() else null
                    )
                    cachedSongMetaDataList = currentList.toList()
                    println("[MDM_DEBUG] applyFavoriteToggleToCache: Cache updated. Returning true.")
                    return true
                } else {
                    println("[MDM_DEBUG] applyFavoriteToggleToCache: Liked status for ${metaData.title} is already $makeFavorite. No change needed.")
                }
            } else {
                println("[MDM_DEBUG] applyFavoriteToggleToCache: Song with path ${songToToggle.data} not found in metadata cache.")
            }
        }
        println("[MDM_DEBUG] applyFavoriteToggleToCache: No change made to cache. Returning false.")
        return false
    }

    fun toggleFavorite(songToToggle: Song, makeFavorite: Boolean) {
        println("[MDM_DEBUG] Entering toggleFavorite for song path: ${songToToggle.data}, makeFavorite: $makeFavorite")
        if (cachedSongMetaDataList == null) {
            println("[MDM_DEBUG] toggleFavorite: Cache was null, loading it first.")
            getSongMetaDataList()
        }
        val cacheChanged = applyFavoriteToggleToCache(songToToggle, makeFavorite)
        if (cacheChanged) {
            println("[MDM_DEBUG] toggleFavorite: Cache was changed. Current desired state: $makeFavorite. Checking DB state.")
            coroutineScope.launch {
                println("[MDM_DEBUG] Coroutine started for DB sync in toggleFavorite for song: ${songToToggle.title}")
                val currentDbFavoriteState = MusicUtil.isFavorite(songToToggle)
                println("[MDM_DEBUG] toggleFavorite (DB sync): Song '${songToToggle.title}' DB favorite state: $currentDbFavoriteState, Desired state: $makeFavorite")
                if (makeFavorite != currentDbFavoriteState) {
                    println("[MDM_DEBUG] toggleFavorite (DB sync): DB state (${currentDbFavoriteState}) differs from desired state ($makeFavorite). Calling MusicUtil.toggleFavorite for ${songToToggle.title}.")
                    MusicUtil.toggleFavorite(songToToggle)
                } else {
                    println("[MDM_DEBUG] toggleFavorite (DB sync): DB state (${currentDbFavoriteState}) already matches desired state ($makeFavorite) for ${songToToggle.title}. No DB toggle needed.")
                }
            }
            // Save MetaDataManagerHelper's cache
            cachedSongMetaDataList?.let { saveSongMetaDataList(it) }
            println("[MDM_DEBUG] toggleFavorite: MetaDataManagerHelper cache save initiated.")
        } else {
            println("[MDM_DEBUG] toggleFavorite: No change in metadata cache, no DB sync or save needed from here.")
        }
        println("[MDM_DEBUG] Exiting toggleFavorite for song path: ${songToToggle.data}")
    }

    fun resetAndApplyFavoriteList(userSongsJsonString: String) {
        println("[MDM_DEBUG] Entering resetAndApplyFavoriteList. JSON length: ${userSongsJsonString.length}")
        val songRepository: SongRepository = get()
        val allDeviceSongs = songRepository.songs()
        println("[MDM_DEBUG] Fetched ${allDeviceSongs.size} songs from SongRepository.")

        var operationsMadeOverallInCache = false
        val gson = Gson()
        val userSongListType = object : TypeToken<List<UserSongInputByNameAndArtists>>() {}.type
        val userSongsToFavorite: List<UserSongInputByNameAndArtists> = try {
            gson.fromJson<List<UserSongInputByNameAndArtists>>(userSongsJsonString, userSongListType) ?: emptyList()
        } catch (e: Exception) {
            println("[MDM_DEBUG] Error parsing userSongsJsonString: ${e.message}")
            Log.e("MetaDataManagerHelper", "Error parsing userSongsJsonString", e)
            emptyList()
        }
        println("[MDM_DEBUG] Parsed ${userSongsToFavorite.size} songs from JSON input.")

        synchronized(cacheLock) {
            println("[MDM_DEBUG] resetAndApplyFavoriteList: Acquired cacheLock.")
            val mutableMetaDataList = getSongMetaDataList().toMutableList()
            println("[MDM_DEBUG] Initial mutableMetaDataList count: ${mutableMetaDataList.size}")

            println("[MDM_DEBUG] Unfavoriting all currently liked songs in cache and syncing with DB...")
            var unfavoritedCountInCache = 0
            val songsToUnfavoriteInDb = mutableListOf<Song>()

            for (i in mutableMetaDataList.indices) {
                if (mutableMetaDataList[i].liked) {
                    val previouslyLikedMetaData = mutableMetaDataList[i]
                    mutableMetaDataList[i] = previouslyLikedMetaData.copy(liked = false, likedTimestamp = null)
                    operationsMadeOverallInCache = true
                    unfavoritedCountInCache++
                    // Find the corresponding Song object to queue for DB update
                    allDeviceSongs.find { it.data == previouslyLikedMetaData.file }?.let { songToUpdateInDb ->
                        songsToUnfavoriteInDb.add(songToUpdateInDb)
                    }
                }
            }
            println("[MDM_DEBUG] Unfavorited $unfavoritedCountInCache songs in cache. operationsMadeOverallInCache: $operationsMadeOverallInCache")

            // Perform DB unfavoriting for songs that were liked in cache
            if (songsToUnfavoriteInDb.isNotEmpty()) {
                coroutineScope.launch {
                    println("[MDM_DEBUG] Coroutine started for batch DB unfavoriting. Count: ${songsToUnfavoriteInDb.size}")
                    songsToUnfavoriteInDb.forEach { song ->
                        val currentDbFavoriteState = MusicUtil.isFavorite(song)
                        println("[MDM_DEBUG] Batch DB Unfavorite: Song '${song.title}' DB state: $currentDbFavoriteState. Intended: false.")
                        if (currentDbFavoriteState) { // If it's currently favorited in DB
                            println("[MDM_DEBUG] Batch DB Unfavorite: Calling MusicUtil.toggleFavorite for ${song.title} to unfavorite.")
                            MusicUtil.toggleFavorite(song)
                        } else {
                            println("[MDM_DEBUG] Batch DB Unfavorite: Song ${song.title} already not favorited in DB.")
                        }
                    }
                    println("[MDM_DEBUG] Coroutine finished batch DB unfavoriting.")
                }
            }

            println("[MDM_DEBUG] Processing userSongsToFavorite (from JSON, reversed) to apply new favorites to cache and sync with DB...")
            val songsToFavoriteInDb = mutableListOf<Song>()

            userSongsToFavorite.reversed().forEach { userSongInput ->
                println("[MDM_DEBUG] Processing userSongInput: Name='${userSongInput.name}', Artists='${userSongInput.artists?.joinToString()}'")
                val matchedDeviceSong = allDeviceSongs.find { deviceSong ->
                    val titleMatches = deviceSong.title.equals(userSongInput.name, ignoreCase = true)
                    if (!titleMatches) false
                    else {
                        val userInputArtistsLower = userSongInput.artists?.mapNotNull { it?.lowercase(Locale.ROOT) }?.toSet() ?: emptySet()
                        val deviceSongArtistsLower = deviceSong.artistNames.mapNotNull { it?.lowercase(Locale.ROOT) }.toSet()
                        if (userInputArtistsLower.isEmpty() && deviceSongArtistsLower.isEmpty()) true
                        else if (userInputArtistsLower.isEmpty() || deviceSongArtistsLower.isEmpty()) false
                        else userInputArtistsLower.any { it in deviceSongArtistsLower }
                    }
                }

                if (matchedDeviceSong != null) {
                    println("[MDM_DEBUG] Matched userSongInput '${userSongInput.name}' to device song: '${matchedDeviceSong.title}' with path '${matchedDeviceSong.data}'")
                    val targetIndexInCache = mutableMetaDataList.indexOfFirst { it.file == matchedDeviceSong.data }
                    if (targetIndexInCache != -1) {
                        val currentMeta = mutableMetaDataList[targetIndexInCache]
                        if (!currentMeta.liked) { // If not already liked in cache
                            println("[MDM_DEBUG] Favoriting song in cache: ${currentMeta.title}")
                            mutableMetaDataList[targetIndexInCache] = currentMeta.copy(
                                liked = true,
                                likedTimestamp = System.currentTimeMillis()
                            )
                            operationsMadeOverallInCache = true
                            songsToFavoriteInDb.add(matchedDeviceSong) // Queue for DB update
                        } else {
                            println("[MDM_DEBUG] Song in cache ${currentMeta.title} is already liked.")
                            // If it's already liked in cache, ensure DB also reflects this (could have been out of sync)
                            // This adds robustness if cache was somehow ahead of DB
                            songsToFavoriteInDb.add(matchedDeviceSong)
                        }
                    } else {
                        songsToFavoriteInDb.add(matchedDeviceSong)
                        println("[MDM_DEBUG] Song with path '${matchedDeviceSong.data}' (matched from device) not found in metadata cache.")
                    }
                } else {
                    println("[MDM_DEBUG] UserSongInput '${userSongInput.name}' with artists '${userSongInput.artists?.joinToString()}' not found in allDeviceSongs.")
                    Log.w("MetaDataManagerHelper", "Song '${userSongInput.name}' (from JSON) not found in device library for favoriting by path.")
                }
            }

            // Perform DB favoriting for songs marked in cache
            if (songsToFavoriteInDb.isNotEmpty()) {
                coroutineScope.launch {
                    println("[MDM_DEBUG] Coroutine started for batch DB favoriting. Count: ${songsToFavoriteInDb.size}")
                    songsToFavoriteInDb.forEach { song ->
                        val currentDbFavoriteState = MusicUtil.isFavorite(song)
                        println("[MDM_DEBUG] Batch DB Favorite: Song '${song.title}' DB state: $currentDbFavoriteState. Intended: true.")
                        if (!currentDbFavoriteState) { // If it's not currently favorited in DB
                            println("[MDM_DEBUG] Batch DB Favorite: Calling MusicUtil.toggleFavorite for ${song.title} to favorite.")
                            MusicUtil.toggleFavorite(song)
                        } else {
                            println("[MDM_DEBUG] Batch DB Favorite: Song ${song.title} already favorited in DB.")
                        }
                    }
                    println("[MDM_DEBUG] Coroutine finished batch DB favoriting.")
                }
            }

            if (operationsMadeOverallInCache) {
                println("[MDM_DEBUG] operationsMadeOverallInCache is true. Updating main cache and saving.")
                cachedSongMetaDataList = mutableMetaDataList.toList()
                saveSongMetaDataList(cachedSongMetaDataList!!)
                Log.d("MetaDataManagerHelper", "Favorites reset and new list from JSON applied to cache. DB sync launched.")
            } else {
                println("[MDM_DEBUG] No operations made overall in cache. No cache save needed.")
                Log.d("MetaDataManagerHelper", "No changes to favorites cache were necessary after processing JSON input. DB sync for consistency still launched if needed.")
            }
            println("[MDM_DEBUG] resetAndApplyFavoriteList: Releasing cacheLock.")
        }
        println("[MDM_DEBUG] Exiting resetAndApplyFavoriteList")
    }

    fun saveContext(context: Context) {
        println("[MDM_DEBUG] Entering saveContext with context: $context")
        appContext = context.applicationContext
        println("[MDM_DEBUG] appContext initialized: $appContext")
        println("[MDM_DEBUG] Defining myJsonString for favorite songs list (content is very long, not printing full string).")
//        val myJsonString = """
//          []
//        """.trimIndent()
//        println("[MDM_DEBUG] Calling resetAndApplyFavoriteList with predefined JSON string.")
//        resetAndApplyFavoriteList(myJsonString)
        println("[MDM_DEBUG] Original println: [favorited] done favorite songs")
        println("[favorited] done favorite songs")
        println("[MDM_DEBUG] Exiting saveContext")
    }

    private fun getContext(): Context {
        println("[MDM_DEBUG] Entering getContext")
        appContext?.let {
            println("[MDM_DEBUG] appContext exists. Returning: $it")
            println("[MDM_DEBUG] Exiting getContext (early return)")
            return it
        } ?: run {
            println("[MDM_DEBUG] appContext is null. Throwing IllegalStateException.")
            throw IllegalStateException("Context not initialized in MetaDataManagerHelper. Call saveContext first.")
        }
    }

    private fun getOutputFile(): File {
        println("[MDM_DEBUG] Entering getOutputFile")
        val currentContext = getContext()
        val file = File(currentContext.filesDir, OUTPUT_FILE_NAME)
        println("[MDM_DEBUG] Output file path determined: ${file.absolutePath}")
        println("[MDM_DEBUG] Exiting getOutputFile")
        return file
    }

    fun readRawOutputFile(): String {
        println("[MDM_DEBUG] Entering readRawOutputFile")
        val file = getOutputFile()
        println("[MDM_DEBUG] Attempting to read from file: ${file.absolutePath}")
        val content = if (file.exists() && file.canRead()) {
            println("[MDM_DEBUG] File exists and is readable.")
            try {
                println("[MDM_DEBUG] Attempting to read text from file.")
                val text = file.readText()
                println("[MDM_DEBUG] Successfully read file content. Length: ${text.length}")
                text
            } catch (e: IOException) {
                println("[MDM_DEBUG] Error reading output file: ${e.message}")
                Log.e("MetaDataManagerHelper", "Error reading output file", e)
                "[]"
            }
        } else {
            println("[MDM_DEBUG] File does not exist or is not readable. Path: ${file.absolutePath}, Exists: ${file.exists()}, CanRead: ${file.canRead()}")
            "[]"
        }
        println("[MDM_DEBUG] Exiting readRawOutputFile. Returning content length: ${content.length}")
        return content
    }

    private fun internalWriteRawOutputFile(content: String) {
        println("[MDM_DEBUG] Entering internalWriteRawOutputFile. Content length: ${content.length}")
        try {
            val file = getOutputFile()
            println("[MDM_DEBUG] Attempting to write to file: ${file.absolutePath}")
            file.writeText(content)
            println("[MDM_DEBUG] Successfully wrote to file.")
        } catch (e: IOException) {
            println("[MDM_DEBUG] Error writing output file: ${e.message}")
            Log.e("MetaDataManagerHelper", "Error writing output file", e)
        }
        println("[MDM_DEBUG] Exiting internalWriteRawOutputFile")
    }

    private fun normalizeGenreName(genre: String): String {
        val step1ReplaceAmpersand = genre.replace("&", "and")
        val step2ReplaceHyphen = step1ReplaceAmpersand.replace(Regex("-"), " ")
        val step3SplitWords = step2ReplaceHyphen.split(Regex("\\s+"))
        val step4FilteredWords = step3SplitWords.filter { it.isNotBlank() }
        val step5MappedWords = step4FilteredWords.map { word ->
            val lowercasedWord = word.lowercase(Locale.ROOT)
            lowercasedWord.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        val finalResult = step5MappedWords.joinToString("-")
        return finalResult
    }

    fun writeRawOutputFile(content: String) {
        println("[MDM_DEBUG] Entering writeRawOutputFile. Content length: ${content.length}")
        val newMetaDataList = parseSongMetaDataJson(content)
        println("[MDM_DEBUG] Parsed newMetaDataList from content. Count: ${newMetaDataList.size}")
        val normalizedMetaDataList = newMetaDataList.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }
        println("[MDM_DEBUG] Normalized metaDataList for writing. Count: ${normalizedMetaDataList.size}")
        synchronized(cacheLock) {
            println("[MDM_DEBUG] writeRawOutputFile: Acquired cacheLock.")
            cachedSongMetaDataList = normalizedMetaDataList
            println("[MDM_DEBUG] Updated cachedSongMetaDataList. Count: ${cachedSongMetaDataList?.size}")
        }
        val normalizedJsonString = Gson().toJson(normalizedMetaDataList)
        println("[MDM_DEBUG] Converted normalizedMetaDataList to JSON for writing. Length: ${normalizedJsonString.length}")
        internalWriteRawOutputFile(normalizedJsonString)
        println("[MDM_DEBUG] Exiting writeRawOutputFile")
    }

    private fun parseSongMetaDataJson(jsonString: String): List<SongMetaData> {
        println("[MDM_DEBUG] Entering parseSongMetaDataJson. JSON string length: ${jsonString.length}")
        return try {
            val mapListType = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>>? = Gson().fromJson(jsonString, mapListType)
            val resultList = rawList?.mapNotNull { rawMap ->
                try {
                    SongMetaData(
                        title = rawMap["title"] as? String ?: "",
                        artists = (rawMap["artists"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        file = rawMap["file"] as? String ?: "",
                        mood = (rawMap["mood"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        genre = (rawMap["genre"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        playlist = (rawMap["playlist"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        year = rawMap["year"] as? String ?: "",
                        liked = rawMap["liked"] as? Boolean ?: false,
                        favorite = rawMap["favorite"] as? Boolean ?: false,
                        rating = (rawMap["rating"] as? Number)?.toInt() ?: 0,
                        danceability = (rawMap["danceability"] as? Number)?.toDouble(),
                        tempo = (rawMap["tempo"] as? Number)?.toDouble(),
                        energy = (rawMap["energy"] as? Number)?.toDouble(),
                        valence = (rawMap["valence"] as? Number)?.toDouble(),
                        market = (rawMap["market"] as? List<*>)?.mapNotNull { it as? String },
                        skips = (rawMap["skips"] as? Number)?.toInt() ?: 0,
                        bpm = (rawMap["bpm"] as? Number)?.toFloat(),
                        playTimestamps = (rawMap["playTimestamps"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.toMutableList() ?: mutableListOf(),
                        skipTimestamps = (rawMap["skipTimestamps"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.toMutableList() ?: mutableListOf(),
                        likedTimestamp = (rawMap["likedTimestamp"] as? Number)?.toLong()
                    )
                } catch (e: Exception) {
                    println("[MDM_DEBUG] Error parsing individual SongMetaData object from map: $rawMap. Error: ${e.message}")
                    Log.e("MetaDataManagerHelper", "Error parsing individual SongMetaData object from map: $rawMap", e)
                    null
                }
            } ?: emptyList()
            println("[MDM_DEBUG] parseSongMetaDataJson: Successfully parsed. Count: ${resultList.size}")
            resultList
        } catch (e: Exception) {
            println("[MDM_DEBUG] Error parsing SongMetaData list from JSON: ${e.message}")
            Log.e("MetaDataManagerHelper", "Error parsing SongMetaData list from JSON", e)
            emptyList()
        }.also {
            println("[MDM_DEBUG] Exiting parseSongMetaDataJson. Returning list of size: ${it.size}")
        }
    }

    fun getSongMetaDataList(): List<SongMetaData> {
        println("[MDM_DEBUG] Entering getSongMetaDataList")
        synchronized(cacheLock) {
            println("[MDM_DEBUG] getSongMetaDataList: Acquired cacheLock for reading.")
            cachedSongMetaDataList?.let {
                println("[MDM_DEBUG] Returning cachedSongMetaDataList. Count: ${it.size}")
                return it
            }
        }
        println("[MDM_DEBUG] Cache miss in getSongMetaDataList. Reading from file.")
        val jsonString = readRawOutputFile()
        val listFromFile = parseSongMetaDataJson(jsonString)
        val normalizedListFromFile = listFromFile.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }
        synchronized(cacheLock) {
            println("[MDM_DEBUG] getSongMetaDataList: Acquired cacheLock for writing after file read.")
            cachedSongMetaDataList = normalizedListFromFile
            println("[MDM_DEBUG] Updated cachedSongMetaDataList from file. Count: ${cachedSongMetaDataList?.size}")
        }
        println("[MDM_DEBUG] Exiting getSongMetaDataList, returning newly fetched list. Count: ${normalizedListFromFile.size}")
        return normalizedListFromFile
    }

    fun saveSongMetaDataList(dataList: List<SongMetaData>) {
        println("[MDM_DEBUG] Entering saveSongMetaDataList. DataList count: ${dataList.size}")
        synchronized(cacheLock) {
            println("[MDM_DEBUG] saveSongMetaDataList: Acquired cacheLock.")
            cachedSongMetaDataList = dataList
            println("[MDM_DEBUG] Updated cachedSongMetaDataList in save. Count: ${cachedSongMetaDataList?.size}")
        }
        println("[MDM_DEBUG] Launching coroutine to save SongMetaData list to file.")
        coroutineScope.launch {
            println("[MDM_DEBUG] Coroutine for saving started.")
            try {
                val listToSave = synchronized(cacheLock) { cachedSongMetaDataList ?: emptyList() }
                val jsonString = Gson().toJson(listToSave)
                println("[MDM_DEBUG] Converted listToSave to JSON string in coroutine. Length: ${jsonString.length}")
                internalWriteRawOutputFile(jsonString)
                println("[MDM_DEBUG] Coroutine finished saving list to file.")
            } catch (e: Exception) {
                println("[MDM_DEBUG] Error saving SongMetaData list in background coroutine: ${e.message}")
                Log.e("MetaDataManagerHelper", "Error saving SongMetaData list in background", e)
            }
        }
        println("[MDM_DEBUG] Exiting saveSongMetaDataList (coroutine launched for actual save).")
    }

    fun getSongKey(song: Song): String {
        println("[MDM_DEBUG] Entering getSongKey with song ID: ${song.id}, Title: ${song.title}, Path: ${song.data}")
        val key = song.data
        println("[MDM_DEBUG] Exiting getSongKey with key (file path): $key")
        return key
    }

    fun updateSongInteraction(
        filePath: String,
        toggleLike: Boolean = false,
        recordPlay: Boolean = false,
        recordSkip: Boolean = false
    ) {
        println("[MDM_DEBUG] Entering updateSongInteraction. FilePath: '$filePath', toggleLike: $toggleLike, recordPlay: $recordPlay, recordSkip: $recordSkip")
        var listChangedInCache = false
        var songToSyncWithDb: Song? = null
        var desiredDbLikeState: Boolean? = null

        synchronized(cacheLock) {
            println("[MDM_DEBUG] updateSongInteraction: Acquired cacheLock.")
            val currentList = getSongMetaDataList().toMutableList()
            val songIndex = currentList.indexOfFirst { it.file == filePath }
            println("[MDM_DEBUG] Found songIndex for filePath '$filePath': $songIndex")

            if (songIndex != -1) {
                var songMetaData = currentList[songIndex]
                var updatedMetaData = songMetaData

                if (toggleLike) {
                    val newLikedState = !songMetaData.liked
                    println("[MDM_DEBUG] Toggling like status for ${songMetaData.title} to $newLikedState in cache.")
                    updatedMetaData = updatedMetaData.copy(
                        liked = newLikedState,
                        likedTimestamp = if (newLikedState) System.currentTimeMillis() else null
                    )
                    // Prepare for DB sync
                    songToSyncWithDb = get<SongRepository>().songsByFilePath(filePath, ignoreBlacklist = true).firstOrNull()
                    desiredDbLikeState = newLikedState
                }

                if (recordPlay) {
                    println("[MDM_DEBUG] Recording play for ${songMetaData.title}")
                    val updatedPlayTimestamps = updatedMetaData.playTimestamps.toMutableList().apply { add(System.currentTimeMillis()) }
                    updatedMetaData = updatedMetaData.copy(playTimestamps = updatedPlayTimestamps)
                }

                if (recordSkip) {
                    println("[MDM_DEBUG] Recording skip for ${songMetaData.title}")
                    val updatedSkipTimestamps = updatedMetaData.skipTimestamps.toMutableList().apply { add(System.currentTimeMillis()) }
                    updatedMetaData = updatedMetaData.copy(
                        skips = updatedMetaData.skips + 1,
                        skipTimestamps = updatedSkipTimestamps
                    )
                }

                if (updatedMetaData !== songMetaData) {
                    println("[MDM_DEBUG] Metadata was changed for ${songMetaData.title}. Updating in list.")
                    currentList[songIndex] = updatedMetaData
                    cachedSongMetaDataList = currentList.toList()
                    listChangedInCache = true
                } else {
                    println("[MDM_DEBUG] No interaction changes made to metadata values for song: ${songMetaData.title}")
                }
            } else {
                println("[MDM_DEBUG] No metadata found for song with file path: $filePath to update interaction.")
                Log.w("MetaDataManagerHelper", "No metadata found for song with file path: $filePath to update interaction.")
            }
            println("[MDM_DEBUG] updateSongInteraction: Releasing cacheLock.")
        }

        if (listChangedInCache) {
            println("[MDM_DEBUG] List was changed in updateSongInteraction cache, saving cache.")
            cachedSongMetaDataList?.let { saveSongMetaDataList(it) }

            // Sync with DB if a like was toggled
            songToSyncWithDb?.let { song ->
                desiredDbLikeState?.let { makeFavorite ->
                    coroutineScope.launch {
                        println("[MDM_DEBUG] Coroutine started for DB sync in updateSongInteraction for song: ${song.title}")
                        val currentDbFavoriteState = MusicUtil.isFavorite(song)
                        println("[MDM_DEBUG] updateSongInteraction (DB sync): Song '${song.title}' DB state: $currentDbFavoriteState, Desired: $makeFavorite")
                        if (makeFavorite != currentDbFavoriteState) {
                            println("[MDM_DEBUG] updateSongInteraction (DB sync): Calling MusicUtil.toggleFavorite for ${song.title}")
                            MusicUtil.toggleFavorite(song)
                        } else {
                            println("[MDM_DEBUG] updateSongInteraction (DB sync): DB state already matches for ${song.title}")
                        }
                    }
                }
            }
        } else {
            println("[MDM_DEBUG] List was NOT changed in updateSongInteraction cache, no save or DB sync needed from here.")
        }
        println("[MDM_DEBUG] Exiting updateSongInteraction for filePath: '$filePath'")
    }
}
