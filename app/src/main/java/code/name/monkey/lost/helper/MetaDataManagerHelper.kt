package code.name.monkey.lost.helper

import android.content.Context
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.UserSongInputByNameAndArtists
import code.name.monkey.lost.repository.SongRepository
import code.name.monkey.lost.util.MusicUtil
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

object MetaDataManagerHelper : KoinComponent {
    private const val TAG = "MetaDataManagerHelper"
    private const val OUTPUT_FILE_NAME = outputPath
    private var appContext: Context? = null
    @Volatile
    private var cachedSongMetaDataList: List<SongMetaData>? = null
    private val cacheLock = Any()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun applyFavoriteToggleToCache(songToToggle: Song, makeFavorite: Boolean): Boolean {
        synchronized(cacheLock) {
            val currentList = getSongMetaDataList().toMutableList()
            val songIndex = currentList.indexOfFirst { it.file == songToToggle.data }
            if (songIndex != -1) {
                val metaData = currentList[songIndex]
                if (metaData.liked != makeFavorite) {
                    currentList[songIndex] = metaData.copy(
                        liked = makeFavorite,
                        likedTimestamp = if (makeFavorite) System.currentTimeMillis() else null
                    )
                    cachedSongMetaDataList = currentList.toList()
                    return true
                }
            }
        }
        return false
    }

    fun toggleFavorite(songToToggle: Song, makeFavorite: Boolean) {
        if (cachedSongMetaDataList == null) {
            getSongMetaDataList()
        }
        val cacheChanged = applyFavoriteToggleToCache(songToToggle, makeFavorite)
        if (cacheChanged) {
            coroutineScope.launch {
                val currentDbFavoriteState = MusicUtil.isFavorite(songToToggle)
                if (makeFavorite != currentDbFavoriteState) {
                    MusicUtil.toggleFavorite(songToToggle)
                }
            }
            // Save MetaDataManagerHelper's cache
            cachedSongMetaDataList?.let { saveSongMetaDataList(it) }
        }
    }

    fun resetAndApplyFavoriteList(userSongsJsonString: String) {
        val songRepository: SongRepository = get()
        val allDeviceSongs = songRepository.songs()
        var operationsMadeOverallInCache = false
        val gson = Gson()
        val userSongListType = object : TypeToken<List<UserSongInputByNameAndArtists>>() {}.type
        val userSongsToFavorite: List<UserSongInputByNameAndArtists> = try {
            gson.fromJson<List<UserSongInputByNameAndArtists>>(userSongsJsonString, userSongListType) ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing userSongsJsonString")
            emptyList()
        }
        synchronized(cacheLock) {
            val mutableMetaDataList = getSongMetaDataList().toMutableList()
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
            if (songsToUnfavoriteInDb.isNotEmpty()) {
                coroutineScope.launch {
                    songsToUnfavoriteInDb.forEach { song ->
                        val currentDbFavoriteState = MusicUtil.isFavorite(song)
                        if (currentDbFavoriteState) { // If it's currently favorited in DB
                            MusicUtil.toggleFavorite(song)
                        }
                    }
                }
            }

            val songsToFavoriteInDb = mutableListOf<Song>()

            userSongsToFavorite.reversed().forEach { userSongInput ->
                val matchedDeviceSong = allDeviceSongs.find { deviceSong ->
                    val titleMatches = deviceSong.title.equals(userSongInput.name, ignoreCase = true)
                    if (!titleMatches) false
                    else {
                        val userInputArtistsLower = userSongInput.artists?.mapNotNull { it.lowercase(Locale.ROOT) }?.toSet() ?: emptySet()
                        val deviceSongArtistsLower = deviceSong.artistNames.mapNotNull { it.lowercase(Locale.ROOT) }.toSet()
                        if (userInputArtistsLower.isEmpty() && deviceSongArtistsLower.isEmpty()) true
                        else if (userInputArtistsLower.isEmpty() || deviceSongArtistsLower.isEmpty()) false
                        else userInputArtistsLower.any { it in deviceSongArtistsLower }
                    }
                }

                if (matchedDeviceSong != null) {
                    val targetIndexInCache = mutableMetaDataList.indexOfFirst { it.file == matchedDeviceSong.data }
                    if (targetIndexInCache != -1) {
                        val currentMeta = mutableMetaDataList[targetIndexInCache]
                        if (!currentMeta.liked) { // If not already liked in cache
                            mutableMetaDataList[targetIndexInCache] = currentMeta.copy(
                                liked = true,
                                likedTimestamp = System.currentTimeMillis()
                            )
                            operationsMadeOverallInCache = true
                            songsToFavoriteInDb.add(matchedDeviceSong) // Queue for DB update
                        } else {
                            songsToFavoriteInDb.add(matchedDeviceSong)
                        }
                    } else {
                        songsToFavoriteInDb.add(matchedDeviceSong)
                    }
                } else {
                    Timber.tag(TAG).w("Song '${userSongInput.name}' (from JSON) not found in device library for favoriting by path.")
                }
            }

            // Perform DB favoriting for songs marked in cache
            if (songsToFavoriteInDb.isNotEmpty()) {
                coroutineScope.launch {
                    songsToFavoriteInDb.forEach { song ->
                        val currentDbFavoriteState = MusicUtil.isFavorite(song)
                        if (!currentDbFavoriteState) { // If it's not currently favorited in DB
                            MusicUtil.toggleFavorite(song)
                        }
                    }
                }
            }

            if (operationsMadeOverallInCache) {
                cachedSongMetaDataList = mutableMetaDataList.toList()
                saveSongMetaDataList(cachedSongMetaDataList!!)
                Timber.tag(TAG).d("Favorites reset and new list from JSON applied to cache. DB sync launched.")
            } else {
                Timber.tag(TAG).d("No changes to favorites cache were necessary after processing JSON input. DB sync for consistency still launched if needed.")
            }
        }
    }

    fun saveContext(context: Context) {
        appContext = context.applicationContext
//        val myJsonString = """
//          []
//        """.trimIndent()
//        resetAndApplyFavoriteList(myJsonString)
    }

    fun getContext(): Context {
        appContext?.let {
            return it
        } ?: run {
            throw IllegalStateException("Context not initialized in MetaDataManagerHelper. Call saveContext first.")
        }
    }

    private fun getOutputFile(): File {
        val currentContext = getContext()
        val file = File(currentContext.filesDir, OUTPUT_FILE_NAME)
        return file
    }

    fun readRawOutputFile(): String {
        val file = getOutputFile()
        val content = if (file.exists() && file.canRead()) {
            try {
                val text = file.readText()
                text
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Error reading output file")
                "[]"
            }
        } else {
            "[]"
        }
        return content
    }

    private fun internalWriteRawOutputFile(content: String) {
        try {
            val file = getOutputFile()
            file.writeText(content)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Error writing output file")
        }
    }

    private fun normalizeGenreName(genre: String): String {
        val step1ReplaceAmpersand = genre.replace("&", "and")
        val step2ReplaceHyphen = step1ReplaceAmpersand.replace(Regex("-"), " ")
        val step3SplitWords = step2ReplaceHyphen.split(Regex("\\s+"))
        val step4FilteredWords = step3SplitWords.filter { it.isNotBlank() }
        val step5MappedWords = step4FilteredWords.map { word ->
            val lowercasedWord = word.lowercase(Locale.ROOT)
            lowercasedWord.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
        val finalResult = step5MappedWords.joinToString("-")
        return finalResult
    }

    fun writeRawOutputFile(content: String) {
        val newMetaDataList = parseSongMetaDataJson(content)
        val normalizedMetaDataList = newMetaDataList.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }
        synchronized(cacheLock) {
            cachedSongMetaDataList = normalizedMetaDataList
        }
        val normalizedJsonString = Gson().toJson(normalizedMetaDataList)
        internalWriteRawOutputFile(normalizedJsonString)
    }

    private fun parseSongMetaDataJson(jsonString: String): List<SongMetaData> {
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
                    Timber.tag(TAG).e(e, "Error parsing individual SongMetaData object from map: $rawMap")
                    null
                }
            } ?: emptyList()
            resultList
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing SongMetaData list from JSON")
            emptyList()
        }
    }

    fun getSongMetaDataList(): List<SongMetaData> {
        synchronized(cacheLock) {
            cachedSongMetaDataList?.let {
                return it
            }
        }
        val jsonString = readRawOutputFile()
        val listFromFile = parseSongMetaDataJson(jsonString)
        val normalizedListFromFile = listFromFile.map { songMetaData ->
            val normalizedGenres = songMetaData.genre.map { genreName ->
                normalizeGenreName(genreName)
            }.distinct()
            songMetaData.copy(genre = normalizedGenres)
        }
        synchronized(cacheLock) {
            cachedSongMetaDataList = normalizedListFromFile
        }
        return normalizedListFromFile
    }

    fun saveSongMetaDataList(dataList: List<SongMetaData>) {
        synchronized(cacheLock) {
            cachedSongMetaDataList = dataList
        }
        coroutineScope.launch {
            try {
                val listToSave = synchronized(cacheLock) { cachedSongMetaDataList ?: emptyList() }
                val jsonString = Gson().toJson(listToSave)
                internalWriteRawOutputFile(jsonString)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving SongMetaData list in background")
            }
        }
    }

    fun getSongKey(song: Song): String {
        val key = song.data
        return key
    }

    fun updateSongInteraction(
        filePath: String,
        toggleLike: Boolean = false,
        recordPlay: Boolean = false,
        recordSkip: Boolean = false
    ) {
        var listChangedInCache = false
        var songToSyncWithDb: Song? = null
        var desiredDbLikeState: Boolean? = null

        synchronized(cacheLock) {
            val currentList = getSongMetaDataList().toMutableList()
            val songIndex = currentList.indexOfFirst { it.file == filePath }

            if (songIndex != -1) {
                val songMetaData = currentList[songIndex]
                var updatedMetaData = songMetaData

                if (toggleLike) {
                    val newLikedState = !songMetaData.liked
                    updatedMetaData = updatedMetaData.copy(
                        liked = newLikedState,
                        likedTimestamp = if (newLikedState) System.currentTimeMillis() else null
                    )
                    // Prepare for DB sync
                    songToSyncWithDb = get<SongRepository>().songsByFilePath(filePath, ignoreBlacklist = true).firstOrNull()
                    desiredDbLikeState = newLikedState
                }

                if (recordPlay) {
                    val updatedPlayTimestamps = updatedMetaData.playTimestamps.toMutableList().apply { add(System.currentTimeMillis()) }
                    updatedMetaData = updatedMetaData.copy(playTimestamps = updatedPlayTimestamps)
                }

                if (recordSkip) {
                    val updatedSkipTimestamps = updatedMetaData.skipTimestamps.toMutableList().apply { add(System.currentTimeMillis()) }
                    updatedMetaData = updatedMetaData.copy(
                        skips = updatedMetaData.skips + 1,
                        skipTimestamps = updatedSkipTimestamps
                    )
                }

                if (updatedMetaData !== songMetaData) {
                    currentList[songIndex] = updatedMetaData
                    cachedSongMetaDataList = currentList.toList()
                    listChangedInCache = true
                }
            }
        }

        if (listChangedInCache) {
            cachedSongMetaDataList?.let { saveSongMetaDataList(it) }

            // Sync with DB if a like was toggled
            songToSyncWithDb?.let { song ->
                desiredDbLikeState?.let { makeFavorite ->
                    coroutineScope.launch {
                        val currentDbFavoriteState = MusicUtil.isFavorite(song)
                        if (makeFavorite != currentDbFavoriteState) {
                            MusicUtil.toggleFavorite(song)
                        }
                    }
                }
            }
        }
    }
}
