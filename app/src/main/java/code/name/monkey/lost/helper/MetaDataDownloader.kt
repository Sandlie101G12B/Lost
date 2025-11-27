package code.name.monkey.lost.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import code.name.monkey.lost.BuildConfig
import code.name.monkey.lost.R
import code.name.monkey.lost.helper.MetaData.reconcileLikedStatusWithLibrary
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.model.SongTMPContainer
import code.name.monkey.lost.network.InternetConnection
import code.name.monkey.lost.repository.RealSongRepository
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.text.replace

const val inputPath = "inputile.txt"
const val outputPath = "outputile.txt"
const val resultantPath = "resultantPath.txt"
const val outputPathBackupConst = "outputile.txt.bak"
private const val ENHANCEMENT_CHANNEL_ID = "song_enhancement_channel"
const val ENHANCEMENT_NOTIFICATION_ID = 1001
const val TAG = "MetaDataDownloader"

fun initialiseMetaDataProcess(context: Context) {
    val songRepository = RealSongRepository(context)
    val songs = songRepository.songs()
    val deviceSongs = songs.map {
        SongTMPContainer(
            title = it.title,
            artistName = it.artistName
                .split(',', '/')
                .map { name -> name.trim() }
                .filter { name -> name.isNotEmpty() },
            data = it.data,
            year = it.year,
            liked = false,
            favorite = false,
            rating = 0
        )
    }

    CoroutineScope(Dispatchers.IO).launch {
        enhanceSongsData(inputPath, outputPath, deviceSongs, context)
        reconcileLikedStatusWithLibrary(songs)
    }
}

// Helper: build the song key (title + artists)
fun songKey(song: JsonObject): Pair<String, List<String>> {
    val title = song.get("title")?.asString?.lowercase() ?: ""
    val artists = song.getAsJsonArray("artists")?.mapNotNull { it.asString } ?: emptyList()
    return title to artists
}

// Helper: extract key from SongTMPContainer object
fun songKeyFromSong(song: SongTMPContainer): Pair<String, List<String>> {
    val title = song.title.lowercase()
    val artists = song.artistName ?: emptyList()
    return title to artists
}

fun generateTextWithGemini(
    prompt: String,
    apiKey: String,
    modelName: String
): String {
    val message = JsonObject().apply {
        addProperty("role", "user")
        val partsArray = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text",
                    """You are an AI Music Tag Enhancer.
You will be provided with a JSON object that contains metadata about a song.
Your tasks are strictly as follows:
Improve and expand only the moodPercentages and genrePercentages fields.
Each must be a JSON object (Map<String, Double>) where the key is a valid mood or genre from the approved list, and the value is a percentage between 0.0 and 1.0.
The total of all percentages in each map must sum up to approximately 1.0.
You must select keys strictly from the provided approved lists below.
Every key or value is case sensitive.
Do not add values for mood and genre, leave their fields as empty arrays [], only add values for moodPercentages and genrePercentages fields.
Add a danceability field (if it does not exist) with a numeric value between 0.0 and 1.0.
0.0 = Not danceable
1.0 = Very danceable
Add or enhance the market field.
This should be an array of region codes based on relevance to the song’s style or audience.
Valid values include, but are not limited to: "US" (United States), "UK" (United Kingdom), "SA" (South Africa), etc.
Add a tempo field (in beats per minute).
This should be a numeric value (Integer or Double).
Example: 120.0 represents 120 beats per minute.
Add an energy field with a value between 0.0 and 1.0.
This represents the intensity and loudness of the track.
Higher values indicate more energetic or intense songs.
Add a valence field with a value between 0.0 and 1.0.
This measures the musical positivity of the song.
Higher values indicate more positive or cheerful moods.
Add a bpm (beats per minute) - CASE SENSITIVE field.
This should be an Integer value.
Important constraints:
You must not change or remove any existing fields other than moodPercentages, genrePercentages, and market.
Do not rename, reorder, or restructure the JSON object.
Do not rename any keys of the JSON object.
Do not add any new keys unless they are explicitly required (danceability, tempo, energy, valence, market).
Your output must be a valid JSON object.
Do not include explanations, comments, or formatting (such as code blocks or markdown).
Do not wrap the output in quotation marks, backticks, or any additional text.
Approved values:
You must use the case-sensitive values from the provided mood and genre lists only.
Any mood or genre not in the approved list is invalid and must not be used.
Case-sensitive valid options:
Mood (select only from this list):
['Abstract', 'Adventurous', 'Affectionate', 'Aggressive', 'Amber', 'Ambient', 'Ambitious', 'Analytical', 'Angry', 'Angsty', 'Anguished', 'Anthemic', 'Anxious', 'Apologetic', 'Aspirational', 'Atmospheric', 'Authentic', 'Bass-heavy', 'Bittersweet', 'Boastful', 'Bold', 'Bossy', 'Bouncy', 'Braggy', 'Bright', 'Brooding', 'Calm', 'Calming', 'Carefree', 'Catchy', 'Celebratory', 'Ceremonial', 'Chant', 'Charismatic', 'Cheeky', 'Cheerful', 'Chill', 'Chilled', 'Chilling', 'Cinematic', 'Classic', 'Club', 'Clubby', 'Club-ready', 'Collaborative', 'Colorful', 'Comforting', 'Competitive', 'Confidence', 'Conflict', 'Conflicted', 'Confrontational', 'Conscious', 'Cool', 'Cozy', 'Cultural', 'Dance', 'Danceable', 'Dancey', 'Dance-floor', 'Dark', 'Deep', 'Defiant', 'Depressed', 'Detached', 'Determined', 'Devotional', 'Dramatic', 'Dreamy', 'Driven', 'Driving', 'Dynamic', 'Earnest', 'Edgy', 'Elegant', 'Empathetic', 'Empowered', 'Encouraging', 'Energizing', 'Epic', 'Escapist', 'Ethereal', 'Exciting', 'Existential', 'Exotic', 'Experimental', 'Faithful', 'Feel-Good', 'Fierce', 'Fiery', 'Flex', 'Focused', 'Free-Spirited', 'Fresh', 'Friendship', 'Frustrated', 'Fun', 'Funky', 'Funny', 'Futuristic', 'Gentle', 'Grateful', 'Groovy', 'Happy', 'Hard', 'Hard-Hitting', 'Healing', 'Heartbroken', 'Heavy', 'High Energy', 'Hopeful', 'Humorous', 'Hungry', 'Hustler's anthem', 'Hyped', 'Innovative', 'Inspirational', 'Inspiring', 'Intense', 'Intimate', 'Isolation', 'Joyful', 'Late night groove', 'Late-night', 'Legendary', 'Liberated', 'Liberating', 'Lighthearted', 'Live', 'Lively', 'Local Pride', 'Local Vibe', 'Lonely', 'Longing', 'Lounge', 'Love-Struck', 'Loving', 'Loyalty', 'Lyrical', 'Melancholic', 'Melodic', 'Melodramatic', 'Minimalistic', 'Money-focused', 'Morning vibe', 'Motivated', 'Mysterious', 'Mystical', 'Narrative', 'Night Vibe', 'Nonchalant', 'Nostalgic', 'Party', 'Passionate', 'Patriotic', 'Peaceful', 'Pensive', 'Personal', 'Playful', 'Political', 'Positive', 'Powerful', 'Protective', 'Proud', 'Provocative', 'Pumped-up', 'Quirky', 'Raised', 'Raise-the-roof', 'Raw', 'Real', 'Rebellious', 'Refreshing', 'Regretful', 'Relaxed', 'Relaxing', 'Resilient', 'Respectful', 'Lost', 'Reverent', 'Rhythmic', 'Rowdy', 'Sad', 'Sarcastic', 'Sassy', 'Satirical', 'Seductive', 'Serene', 'Serious', 'Sexy', 'Sincere', 'Slow', 'Smooth', 'Soft', 'Somber', 'Sophisticated', 'South African pride', 'Southern vibe', 'Spicy', 'Spiritual', 'Storytelling', 'Strategic', 'Street', 'Street-wise', 'Street-empower', 'Street-vibe', 'Strong', 'Stylish', 'Sultry', 'Supportive', 'Swagger', 'Swaggy', 'Sweet', 'Tender', 'Thankful', 'Thoughtful', 'Togetherness', 'Tough', 'Traditional', 'Tragic', 'Tranquil', 'Trendy', 'Tribal', 'Tribute', 'Trippy', 'Triumphant', 'Turn up', 'Turnt', 'Underground', 'Upbeat', 'Up-tempo', 'Urban', 'Vengeful', 'Vibe', 'Vibey', 'Vibrant', 'Victorious', 'Vulnerable', 'Warm', 'Wavy', 'Whimsical', 'Wild', 'Wistful', 'Witty', 'Worshipful', 'Yearning', 'Young', 'Youthful', 'assertive', 'braggadocious', 'confident', 'contemplative', 'emotional', 'empowering', 'energetic', 'euphoric', 'festive', 'flirty', 'gritty', 'haunting', 'heartbreak', 'heartfelt', 'hype', 'hypnotic', 'independent', 'introspective', 'ironic', 'laid-back', 'lush', 'luxurious', 'melancholy', 'mellow', 'moody', 'motivational', 'optimistic', 'reflective', 'relatable', 'romantic', 'sensual', 'sentimental', 'soothing', 'soulful', 'tense', 'thought-provoking', 'uplifting']
Genre (select only from this list):
['Acapella', 'Bacardi', '3 Step', 'Gwijo', 'Acoustic', 'Adult contemporary', 'African', 'Afro Fusion', 'Afro Hip-Hop', 'Afro Rap', 'Afro Tech', 'Afro pop', 'Afro-House', 'Afro-jazz', 'Afrobeat', 'Afrobeats', 'Alternative', 'Alternative Hip Hop', 'Alternative Pop', 'Alternative R&B', 'Alternative Rap', 'Alternative Rock', 'Ambient', 'Ambient Pop', 'Ambient Rock', 'Anime-inspired', 'Bacardi', 'Bacardi House', 'Ballad', 'Barcadi', 'Baroque Pop', 'Battle Rap', 'Blues', 'Blues Rock', 'Bongo Flava', 'Boom Bap', 'Britpop', 'Broken Beat', 'Chill Rap', 'Chillout', 'Choir', 'Choral', 'Christian', 'Christian Hip-Hop', 'Christian Pop', 'Christian Rap', 'Christian Worship', 'Christmas', 'Cinematic', 'Classic', 'Classic Rock', 'Classical', 'Cloud Rap', 'Club', 'Coleader', 'Comedy Rap', 'Comedy hip hop', 'Conscious Hip-Hop', 'Conscious Rap', 'Contemporary Christian', 'Contemporary R&B', 'Country', 'Crunk', 'Cypher', 'Dance', 'Dance Rock', 'Dance-Pop', 'Dancehall', 'Deep House', 'Detroit House', 'Disco', 'Disney', 'Diss Track', 'Doowop', 'Downtempo', 'Dream Pop', 'Drum & Bass', 'Drum and Bass', 'Dubstep', 'EDM', 'East Coast Hip-Hop', 'Electro', 'Electro House', 'Electronic', 'Electronica', 'Electropop', 'Emo', 'Emo Rap', 'Euro Pop', 'Eurodance', 'Experimental', 'Experimental Hip-Hop', 'Folk', 'Folk House', 'Freestyle', 'French Chanson', 'French Pop', 'Funk', 'Funk Brasileiro', 'Future Bass', 'G-Funk', 'Gangsta Rap', 'Gospel', 'Gospel House', 'Gqom', 'Grime', 'Highlife', 'Indie', 'Indie Dance', 'Indie Folk', 'Indie Pop', 'Indie rock', 'Inspirational', 'Instrumental', 'Intro', 'Jam Band', 'Jazz', 'Jazz Fusion', 'Jazz House', 'Kwaito Fusion', 'Kwaito Rap', 'Kwaito-Influenced', 'Latin', 'Latin House', 'Latin Pop', 'Latin Trap', 'Live', 'Lo-fi Hip Hop', 'Lounge', 'Lo-fi', 'Lyricism', 'Maskandi', 'Maskandi Fusion', 'Melodic Rap', 'Minimalism', 'Motswako', 'Neo Soul', 'Novelty', 'Nu Disco', 'Nu Jazz', 'Old School Hip Hop', 'Opera', 'Orchestral', 'Orchestral Pop', 'Orchestral Rap', 'Party', 'Party Rap', 'Pop Ballad', 'Pop Rock', 'Pop Soul', 'Pop-Rap', 'Progressive House', 'R&B', 'R&B Fusion', 'Rap', 'Reggae', 'Reggaeton', 'Remix', 'Lost', 'RnB', 'Rock', 'Rock and Roll', 'Romantic', 'SA Hip-Hop', 'Singer-Songwriter', 'Slow jam', 'Smooth Jazz', 'Soft Rock', 'Sotho Rap', 'Soul', 'Soulful', 'Soulful Amapiano', 'Soulful House', 'Soulful Piano', 'Soundtrack', 'South African', 'South African Dance', 'South African Hip Hop', 'South African Music', 'South African Rap', 'South African Street', 'South African house', 'Spiritual', 'Spiritual House', 'Spoken Word', 'Street Rap', 'Swing', 'Synthpop', 'Tech House', 'Techno', 'Traditional', 'Traditional Crossover', 'Traditional Zulu', 'Trap Metal', 'Trap Soul', 'Trip-Hop', 'Tsonga Rap', 'UK Hip-Hop', 'Underground Rap', 'Urban', 'West Coast Hip-Hop', 'World', 'World Music', 'Worldbeat', 'Worship', 'Zulu Rap', 'Zulu Traditional', 'afrosoul', 'afrotrap', 'amapiano', 'arena rock', 'art rock', 'drill', 'hip hop', 'house', 'kwaito', 'pop', 'post-Britpop', 'private school', 'soul-pop', 'south african pop', 'southern rap', 'trap', 'trap-pop']
Now, here is the input JSON:
""".trimIndent() + prompt)
            })
        }
        add("parts", partsArray)
    }

    val chatArray = JsonArray().apply { add(message) }
    val payload = JsonObject().apply { add("contents", chatArray) }

    val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(5, TimeUnit.MINUTES)
        .build()


    val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

    val mediaType = "application/json".toMediaTypeOrNull()
    val body = payload.toString().toRequestBody(mediaType)

    val request = Request.Builder()
        .url(apiUrl)
        .post(body)
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return "Error making API request: ${response.code} ${response.message}"
            }
            val responseBodyString = response.body.string()
            val json = JsonParser.parseString(responseBodyString).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val content = candidates[0].asJsonObject.getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    val resultText = parts[0].asJsonObject.get("text").asString
                    return resultText
                }
            }
            "Error: Unexpected response structure"
        }
    } catch (e: Exception) {
        "An unexpected error occurred: $e"
    }
}

// Convert a SongTMPContainer to SongMetaData for JSON export
fun songToSongMetaData(song: SongTMPContainer): SongMetaData {
    return SongMetaData(
        title = song.title,
        artists = song.artistName ?: emptyList(),
        file = song.data,
        moodPercentages = null,
        genrePercentages = null,
        mood = emptyList(),
        genre = emptyList(),
        playlist = emptyList(),
        year = song.year?.toString() ?: "",
        liked = song.liked ?: false,
        favorite = song.favorite ?: false,
        rating = song.rating ?: 0,
        danceability = null, // corrected spelling
        tempo = null,        // in BPM (e.g., 120.0)
        energy = null,       // 0.0 - 1.0 (intensity/loudness)
        valence = null,
        market = null,
        bpm = null,
        skips = 0
    )
}

// Merge new device songs into file if not already present
fun scanAndAddNewDeviceSongs(context: Context, songsDataPath: String, deviceSongs: List<SongTMPContainer>) {
    val gson = Gson()
    val existingSongs: List<JsonObject> = try {
        val arr = JsonParser.parseString(readFileOrCreate(context, songsDataPath, "[]")).asJsonArray
        arr.map { it.asJsonObject }
    } catch (_: Exception) {
        emptyList()
    }

    val existingKeys = existingSongs.map { songKey(it) }.toSet()

    val newDeviceSongs = deviceSongs
        .filter { songKeyFromSong(it) !in existingKeys }
        .map { songToSongMetaData(it) }

    if (newDeviceSongs.isNotEmpty()) {
        val updatedSongs = existingSongs.toMutableList()
        newDeviceSongs.forEach { updatedSongs.add(gson.toJsonTree(it).asJsonObject) }
        val updatedSongsTxt = gson.toJson(updatedSongs)
        writeToInternalStorage(context, songsDataPath, updatedSongsTxt)
    }
}

// Secure API key storage/retrieval
fun saveApiKeys(context: Context, apiKeys: List<String>) {
    val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    sharedPreferences.edit { putString("api_keys", apiKeys.joinToString(",")) }
}

fun getApiKeys(context: Context): List<String> {
    try {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secure_api_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val keys = sharedPreferences.getString("api_keys", "") ?: ""
        return keys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }catch (_: Exception) {
        try {
            if(
                context.deleteSharedPreferences("secure_api_keys")
            ){
                return emptyList()
            }
        } catch (_: Exception) {}
    }
    return emptyList()
}

fun addApiKey(context: Context): Boolean {
    return try {
        val keysString = BuildConfig.GEMINI_API_KEYS
        val companyApiKeys = keysString
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        saveApiKeys(context, companyApiKeys)

        getApiKeys(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

private fun createNotificationChannel(context: Context) {
    val name = "Song Enhancement Progress"
    val descriptionText = "Shows the progress of song metadata enhancement"
    val importance = NotificationManager.IMPORTANCE_LOW
    val channel = NotificationChannel(ENHANCEMENT_CHANNEL_ID, name, importance).apply {
        description = descriptionText
    }
    val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)
}

suspend fun enhanceSongsData(inputPath: String, outputPath: String, deviceSongs: List<SongTMPContainer>, context: Context) {
    val gson = Gson()
    createNotificationChannel(context)
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notificationBuilder = NotificationCompat.Builder(context, ENHANCEMENT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Enhancing Songs")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setProgress(0, 0, true)

    notificationManager.notify(ENHANCEMENT_NOTIFICATION_ID, notificationBuilder.build())

    scanAndAddNewDeviceSongs(context, inputPath, deviceSongs)
    fixMissingSongMetaFields(context, outputPath, outputPathBackupConst)
    mergeSongDataFiles(context)
    InternetConnection.waitForConnection(context, notificationBuilder, notificationManager)

    val songs = JsonParser.parseString(readFileOrCreate(context, inputPath, "[]")).asJsonArray
    val enhancedSongs: MutableList<JsonObject> = try {
        val arr = JsonParser.parseString(readFileOrCreate(context, outputPath, "[]")).asJsonArray
        arr.map { it.asJsonObject }.toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }

    val processedPaths = enhancedSongs.mapNotNull { it.get("file")?.asString }.toSet()
    val newEntries = songs.map { it.asJsonObject }
        .filter { it.get("file")?.asString !in processedPaths }

    if (newEntries.isNotEmpty()) {
        val myApiKeys = getApiKeys(context)
        if (myApiKeys.isEmpty()) {
            notificationManager.cancel(ENHANCEMENT_NOTIFICATION_ID)
            return
        }

        val modelNames = mutableListOf(
            "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite"
        )
        var modelIndex = 0
        var apiKeyIndex = 0
        var songsProcessedCount = 0
        val totalSongsToProcess = newEntries.size

        notificationBuilder
            .setContentText("Processing 0 of $totalSongsToProcess songs.")
            .setProgress(totalSongsToProcess, 0, false)
        notificationManager.notify(ENHANCEMENT_NOTIFICATION_ID, notificationBuilder.build())


        for (song in newEntries) {
            var processedThisSong = false
            while (modelIndex < myApiKeys.size && !processedThisSong) {
                val apiKey = myApiKeys[modelIndex]
                while (apiKeyIndex < modelNames.size && !processedThisSong) {
                    val modelName = modelNames[apiKeyIndex]
                    val prompt = song.toString()
                    val result = generateTextWithGemini(prompt, apiKey, modelName)
                        .replace("```json", "")
                        .replace("```", "")
                        .replace("\n", "")
                        .replace("\"artist\"", "\"artists\"")
                        .replace("\"moods\"", "\"mood\"")
                        .replace("\"genres\"", "\"genre\"")
                        .replace("\"markets\"", "\"market\"")
                        
                        .trim()
                    try {
                        val enhancedSong = JsonParser.parseString(result).asJsonObject
                        enhancedSongs.add(enhancedSong)
                        // Write to backup first, then to main output file
                        val currentDataToWrite = gson.toJson(enhancedSongs)
                        writeToInternalStorage(context, outputPath, currentDataToWrite)
                        fixMissingSongMetaFields(context, outputPath, outputPathBackupConst)
                        SongDataManager.loadDefaultSongsJson(context)
                        processedThisSong = true
                        songsProcessedCount++
                        notificationBuilder
                            .setContentText("Processing $songsProcessedCount of $totalSongsToProcess songs.")
                            .setProgress(totalSongsToProcess, songsProcessedCount, false)
                        notificationManager.notify(ENHANCEMENT_NOTIFICATION_ID, notificationBuilder.build())

                    } catch (_: Exception) {
                        if (!InternetConnection.hasInternetConnection(context)){
                            processedThisSong = false
                        }else{
                            apiKeyIndex++
                        }
                    }
                }
                if (!processedThisSong) {
                    if (!InternetConnection.hasInternetConnection(context)){
                                InternetConnection.waitForConnection(context, notificationBuilder, notificationManager)
                    }else{
                        modelIndex++
                        apiKeyIndex = 0
                    }
                }
            }
            mergeSongDataFiles(context)
            if (!processedThisSong) {
                if (!InternetConnection.hasInternetConnection(context)) {
                            InternetConnection.waitForConnection(context, notificationBuilder, notificationManager)
                }else{
                    initialiseMetaDataProcess(context)
                    return
                }
            }
        }
        notificationBuilder
            .setContentTitle("Enhancement Complete")
            .setContentText("$totalSongsToProcess songs enhanced.")
            .setOngoing(false)
            .setProgress(0, 0, false)
        notificationManager.notify(ENHANCEMENT_NOTIFICATION_ID, notificationBuilder.build())

    } else {
        notificationManager.cancel(ENHANCEMENT_NOTIFICATION_ID)
    }
}

fun writeToInternalStorage(context: Context, filename: String, content: String) {
    try {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use {
            it.write(content.toByteArray())
        }
    }catch (_: Exception) {}
}

fun readFileOrCreate(context: Context, filename: String, defaultContent: String = "[]"): String {
    val file = File(context.filesDir, filename)
    return try {
        if (file.exists()) {
            file.readText()
        } else {
            file.writeText(defaultContent)
            defaultContent
        }
    } catch (_: IOException) {
        defaultContent // Return default content on error to avoid null
    }
}

private fun validateJsonContent(jsonString: String?): JsonArray {
    if (jsonString.isNullOrEmpty()) return JsonArray()
    return try {
        val arr = JsonParser.parseString(jsonString).asJsonArray
        val filteredArr = JsonArray()
        arr.forEach { element ->
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                fun isPresentAndNotNullPrimitive(fieldName: String): Boolean {
                    return obj.has(fieldName) && obj.get(fieldName).isJsonPrimitive && !obj.get(fieldName).isJsonNull
                }
                fun isPresentAndNotNullArray(fieldName: String): Boolean {
                    return obj.has(fieldName) && obj.get(fieldName).isJsonArray
                }
                fun isPresentAndNonEmptyString(fieldName: String): Boolean {
                    if (!obj.has(fieldName)) return false
                    val jsonElement = obj.get(fieldName)
                    return jsonElement.isJsonPrimitive && jsonElement.asJsonPrimitive.isString && jsonElement.asString.isNotEmpty()
                }
                fun isPresentAndLegal(fieldName: String): Boolean {
                    if (!obj.has(fieldName)) return true
                    val jsonElement = obj.get(fieldName)
                    if (!jsonElement.isJsonArray) {
                        obj.add(fieldName, JsonArray())
                        return true
                    }
                    val jsonArray = jsonElement.asJsonArray
                    if (jsonArray.size() == 0) return true
                    val rawString = jsonArray.toString()
                    if (rawString.contains("{") || rawString.contains("}")) {
                        obj.add(fieldName, JsonArray())
                        return true
                    }
                    for (element in jsonArray) {
                        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                            obj.add(fieldName, JsonArray())
                            return true
                        }
                    }
                    return true
                }

                if (isPresentAndLegal("mood") &&
                    isPresentAndLegal("genre") &&
                    isPresentAndNonEmptyString("file") &&
                    isPresentAndNonEmptyString("title") &&
                    isPresentAndNotNullArray("artists") &&
                    isPresentAndNotNullArray("market") &&
                    isPresentAndNotNullPrimitive("danceability") &&
                    isPresentAndNotNullPrimitive("tempo") &&
                    isPresentAndNotNullPrimitive("energy") &&
                    isPresentAndNotNullPrimitive("valence")) {
                    filteredArr.add(obj)
                }
            }
        }
        filteredArr
    } catch (_: Exception) {
        JsonArray() // Return empty array if parsing or validation fails
    }
}

fun fixMissingSongMetaFields(context: Context, outputPath: String, outputPathBackup: String) {
    val gson = Gson()

    // 1. Read main output file
    val mainFileContent = readFileOrCreate(context, outputPath, "[]")
        
    var validatedData = validateJsonContent(mainFileContent)
    val mainFileWasCorrupted = mainFileContent.isNotEmpty() && mainFileContent != "[]" && validatedData.isEmpty

    // 2. If main file was corrupted, try to restore from backup
    if (mainFileWasCorrupted) {
        val backupFileContent = readFileOrCreate(context, outputPathBackup, "[]")
        val validatedBackupData = validateJsonContent(backupFileContent)
        if (validatedBackupData.size() > 0) {
            validatedData = validatedBackupData // Use backup data
        }
        // If backup is also empty/corrupt, validatedData remains empty (from initial main file attempt or if backup also yields empty)
    }

    // 3. If validatedData is still empty (both original and backup were bad or empty), ensure it's "[]"
    val finalJsonString = if (validatedData.size() > 0) {
        val validatedDataString = gson.toJson(validatedData)
        writeToInternalStorage(context, outputPathBackup, validatedDataString)
        validatedDataString
    } else {
        readFileOrCreate(context, outputPathBackup, "[]")
    }

    // 4. Write the final (potentially restored or reset) data to backup first, then to main output file.
    writeToInternalStorage(context, outputPath, finalJsonString)
}

fun mergeSongDataFiles(
    context: Context,
    inputFileName: String = inputPath,
    outputFileName: String = outputPath,
    resultFileName: String = resultantPath,
    includeUnmatchedOutputSongs: Boolean = true
) {
    val gson = Gson()

    fun readJsonArrayFromFile(fileName: String): List<JsonObject> {
        val content = readFileOrCreate(context, fileName, "[]")
        return try {
            JsonParser.parseString(content)
                .asJsonArray
                .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val inputSongsList = readJsonArrayFromFile(inputFileName)
    val outputSongsList = readJsonArrayFromFile(outputFileName)

    val outputSongsMap = outputSongsList.mapNotNull { song ->
        song["file"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { it to song }
    }.toMap()

    val mergedSongs = mutableListOf<JsonObject>()

    for (inputSong in inputSongsList) {
        val merged = inputSong.deepCopy()
        val fileKey = inputSong["file"]?.asString

        if (fileKey != null && outputSongsMap.containsKey(fileKey)) {
            val outputSong = outputSongsMap[fileKey]!!
            for ((key, value) in outputSong.entrySet()) {
                // Only overwrite if output value is not null or empty
                if (!merged.has(key) || !value.isJsonNull) {
                    merged.add(key, value)
                }
            }
        }
        mergedSongs.add(merged)
    }

    // Add unmatched output songs if requested
    if (includeUnmatchedOutputSongs) {
        val inputFiles = inputSongsList.mapNotNull { it["file"]?.asString }.toSet()
        val unmatched = outputSongsList.filter {
            val key = it["file"]?.asString
            key != null && key !in inputFiles
        }
        mergedSongs.addAll(unmatched)
    }

    writeToInternalStorage(context, resultFileName, gson.toJson(mergedSongs))
}

