package code.name.monkey.lost.helper

import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import code.name.monkey.lost.network.InternetConnection
import code.name.monkey.lost.repository.Repository
import code.name.monkey.lost.util.YTPlayerUtils.getSimilarContent
import code.name.monkey.lost.util.YTPlayerUtils.searchVideos
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object ShuffleHelper : KoinComponent {
    private const val TAG = "ShuffleHelper"
    private var metadataMap: Map<String, SongMetaData>? = null

    // Injected by Koin. Assumes Repository is defined in your Koin modules.
    private val repository: Repository by inject()

    private const val CACHE_EXPIRY_MS = 5000L // 5 seconds
    private data class CachedShuffle(val list: List<Song>, val timestamp: Long)
    private val shuffleCache = mutableMapOf<String, CachedShuffle>()


    // === START: Emotion Wheel and Genre Preference Constants and Helpers ===

    /**
     * Map to group synonymous mood keywords into a single canonical term (Normalization).
     * All keys and values must be lowercase.
     */
    private val MOOD_NORMALIZATION_MAP = mapOf(
        // Joy/Positive Group -> JOY
        "joyful" to "joy", "happy" to "joy", "cheerful" to "joy", "upbeat" to "joy", "euphoric" to "joy",
        "celebratory" to "joy", "fun" to "joy", "feel-good" to "joy", "victorious" to "joy", "triumphant" to "joy",

        // Sadness/Melancholy Group -> SADNESS
        "sad" to "sadness", "grief" to "sadness", "melancholic" to "sadness", "somber" to "sadness", "wistful" to "sadness",
        "longing" to "sadness", "regretful" to "sadness", "anguished" to "sadness", "depressed" to "sadness",
        "heartbreak" to "sadness", "heartfelt" to "sadness", "melancholy" to "sadness", "nostalgic" to "sadness",
        "bittersweet" to "sadness", "yearning" to "sadness", "wistful" to "sadness", "grieving" to "sadness",

        // Aggression/Intensity Group -> AGGRESSION
        "aggressive" to "aggression", "fierce" to "aggression", "hard" to "aggression", "tough" to "aggression",
        "hard-hitting" to "aggression", "conflict" to "aggression", "confrontational" to "aggression",
        "angry" to "aggression", "vengeful" to "aggression", "tense" to "aggression", "gritty" to "aggression",

        // Calm/Relaxation Group -> CALMNESS
        "calm" to "calmness", "relaxed" to "calmness", "serene" to "calmness", "mellow" to "calmness", "soothing" to "calmness",
        "tranquil" to "calmness", "laid-back" to "calmness", "gentle" to "calmness", "soft" to "calmness", "cozy" to "calmness",
        "chilled" to "calmness", "chill" to "calmness", "ambient" to "calmness", "peaceful" to "calmness", "smooth" to "calmness",

        // Confidence/Swagger Group -> CONFIDENCE
        "confident" to "confidence", "bold" to "confidence", "assertive" to "confidence", "swagger" to "confidence",
        "swaggy" to "confidence", "boastful" to "confidence", "braggy" to "confidence", "empowered" to "confidence",
        "empowering" to "confidence", "driven" to "confidence", "ambitious" to "confidence",

        // Excitement/Hype Group -> EXCITEMENT
        "hype" to "excitement", "energetic" to "excitement", "high energy" to "excitement", "pumped-up" to "excitement",
        "exciting" to "excitement", "dynamic" to "excitement", "lively" to "excitement", "driving" to "excitement",
        "turn up" to "excitement", "turnt" to "excitement", "anthemic" to "excitement",

        // Reflection/Introspection Group -> REFLECTION
        "introspective" to "reflection", "pensive" to "reflection", "contemplative" to "reflection",
        "thoughtful" to "reflection", "reflective" to "reflection", "serious" to "reflection", "brooding" to "reflection",
        "analytical" to "reflection", "existential" to "reflection", "intimate" to "reflection",

        // Love/Affection Group -> LOVE
        "loving" to "love", "affectionate" to "love", "tender" to "love", "romantic" to "love", "sensual" to "love", "flirty" to "love",
        "supportive" to "love", "togetherness" to "love", "friendship" to "love", "loyal" to "love"
    )

    /**
     * Defines the angular position for the 8 core canonical moods on the emotion wheel (in degrees).
     * The degrees are used for calculating the emotional "Center of Gravity" (COG) of a song.
     */
    private val MOOD_ANGULAR_POSITIONS = mapOf(
        "joy" to 0.0,
        "confidence" to 45.0, // Joy -> Excitement
        "excitement" to 90.0,
        "aggression" to 135.0, // Excitement -> Sadness/Anger
        "sadness" to 180.0,
        "reflection" to 225.0, // Sadness -> Calmness/Introspection
        "calmness" to 270.0,
        "love" to 315.0 // Calmness -> Joy/Affection
    )

    /**
     * Defines the canonical moods that constitute a "feel-good" or generally positive/upbeat track.
     * Used exclusively by getFeelGoodBonus.
     */
    private val POSITIVE_MOODS = setOf(
        "joy",
        "confidence",
        "excitement",
        "love",
        "calmness" // Calmness is included as it represents positive relaxation.
    )

    /**
     * Defines the angular position for core canonical genres on a wheel (in degrees) for smooth transition.
     * These mappings are subjective, designed to group similar genres (e.g., urban/pop together, rock/metal together).
     */
    private val GENRE_ANGULAR_POSITIONS = mapOf(
        "hiphop" to 0.0,
        "rnb" to 45.0,
        "pop" to 90.0,
        "electronic" to 135.0,
        "rock" to 180.0,
        "metal" to 225.0,
        "jazz" to 270.0,
        "gospel" to 315.0 // Close to Hip Hop (Urban/Vocal)
    )

    /* FULL_GENRE_ANGULAR_POSITIONS: Expanded map including all user-defined genres
     * Placement ensures smooth angular transitions, preventing "scattering" jumps.
     * 0°: Urban/Hiphop/Amapiano (Rhythmic)
     * 45°: R&B/Soul (Vocal/Smooth)
     * 90°: Pop/Alternative (Commercial)
     * 135°: House/Electronic (Dance/Rave)
     * 180°: Rock/Alternative Rock (Guitar/Band)
     * 270°: Jazz/World (Instrumental/Folk)
     * 315°: Gospel/Worship (Spiritual/Choir)
     */
    private val FULL_GENRE_ANGULAR_POSITIONS = GENRE_ANGULAR_POSITIONS + mapOf(
        // === Urban/Hiphop/Amapiano Sector (0° - 35°) ===
        "amapiano" to 0.0,
        "afro hip-hop" to 2.0, "afro rap" to 2.0, "sa hip-hop" to 3.0, "south african hip hop" to 3.0, "south african rap" to 3.0,
        "afrotrap" to 5.0, "trap" to 6.0, "trap-pop" to 7.0, "drill" to 8.0, "trap metal" to 9.0,
        "hip hop" to 10.0, "rap" to 10.0, "underground rap" to 11.0, "conscious hip-hop" to 12.0, "conscious rap" to 12.0,
        "afrobeat" to 15.0, "afrobeats" to 15.0, "afro fusion" to 16.0, "urban" to 17.0,
        "west coast hip-hop" to 19.0, "east coast hip-hop" to 20.0, "g-funk" to 21.0, "gangsta rap" to 22.0, "street rap" to 23.0,
        "boom bap" to 25.0, "lo-fi hip hop" to 26.0, "chill rap" to 27.0, "emo rap" to 28.0, "cloud rap" to 29.0,
        "motswako" to 30.0, "kwaito" to 31.0, "kwaito fusion" to 32.0, "kwaito rap" to 33.0, "kwaito-influenced" to 33.0,
        "bacardi" to 34.0, "barcadi" to 34.0, // Grouping Bacardi closer to Hiphop/Kwaito

        // === R&B/Soul Sector (35° - 60°) ===
        "soul" to 40.0, "neo soul" to 41.0, "r&b" to 42.0, "rnB" to 42.0, "contemporary r&b" to 43.0, "alternative r&b" to 44.0,
        "soulful" to 46.0, "afrosoul" to 47.0, "slow jam" to 48.0, "smooth jazz" to 50.0, "soul-pop" to 52.0,
        "doowop" to 55.0, "romantic" to 56.0,

        // === Pop/Dance-Pop Sector (60° - 110°) ===
        "pop" to 90.0, "alternative pop" to 91.0, "pop ballad" to 92.0, "pop soul" to 93.0, "south african pop" to 94.0,
        "dance-pop" to 95.0, "synthpop" to 96.0, "electropop" to 97.0, "euro pop" to 98.0, "eurodance" to 99.0,
        "adult contemporary" to 100.0, "french pop" to 101.0, "baroque pop" to 102.0, "orchestral pop" to 103.0,
        "dance" to 110.0,

        // === House/Electronic Sector (110° - 160°) ===
        "electronic" to 135.0, "electronica" to 136.0, "edm" to 137.0, "electro" to 138.0, "electro house" to 139.0,
        "house" to 140.0, "deep house" to 141.0, "tech house" to 142.0, "detroit house" to 143.0, "latin house" to 144.0,
        "bacardi house" to 145.0, "soulful house" to 146.0, "spiritual house" to 147.0, "gospel house" to 148.0,
        "afro-house" to 149.0, "afro tech" to 150.0, "gqom" to 151.0,
        "drum & bass" to 155.0, "drum and bass" to 156.0, "dubstep" to 157.0, "future bass" to 158.0,

        // === Rock/Alternative Sector (160° - 240°) ===
        "rock" to 180.0, "classic rock" to 181.0, "arena rock" to 182.0, "soft rock" to 183.0, "pop rock" to 184.0,
        "alternative rock" to 185.0, "indie rock" to 186.0, "blues rock" to 187.0, "dance rock" to 188.0,
        "alternative" to 190.0, "indie" to 191.0, "indie pop" to 192.0, "dream pop" to 193.0, "ambient pop" to 194.0,
        "emo" to 200.0, "metal" to 225.0, "trap metal" to 226.0,

        // === Jazz/Folk/World Sector (240° - 310°) ===
        "jazz" to 270.0, "jazz house" to 271.0, "jazz fusion" to 272.0, "nu jazz" to 273.0, "afro-jazz" to 274.0,
        "folk" to 275.0, "indie folk" to 276.0, "folk house" to 277.0, "singer-songwriter" to 278.0, "ambient" to 280.0,
        "world" to 290.0, "world music" to 291.0, "worldbeat" to 292.0, "african" to 293.0,
        "traditional" to 295.0, "traditional zulu" to 296.0, "maskandi" to 297.0, "maskandi fusion" to 298.0, "gwijo" to 299.0,
        "classical" to 305.0, "orchestral" to 306.0, "cinematic" to 307.0, "soundtrack" to 308.0,

        // === Gospel/Worship Sector (310° - 359°) ===
        "gospel" to 315.0, "worship" to 316.0, "christian" to 317.0, "christian worship" to 318.0,
        "inspirational" to 320.0, "spiritual" to 321.0, "choir" to 322.0, "choral" to 323.0
    )

    /**
     * Normalizes a mood map by grouping synonyms using the MOOD_NORMALIZATION_MAP.
     */
    private fun normalizeMoodMap(moodMap: Map<String, Double>?): Map<String, Double>? {
        if (moodMap.isNullOrEmpty()) return null
        val normalized = mutableMapOf<String, Double>()
        for ((mood, percentage) in moodMap) {
            val normalizedMood = MOOD_NORMALIZATION_MAP[mood.lowercase()] ?: mood.lowercase()
            // Only include moods that have an angular position defined for the COG calculation
            if (MOOD_ANGULAR_POSITIONS.containsKey(normalizedMood)) {
                // Sum percentages for the canonical mood group
                normalized[normalizedMood] = normalized.getOrDefault(normalizedMood, 0.0) + percentage
            }
        }
        return normalized.filterValues { it > 0.0 }
    }

    /**
     * Defines primary opposite mood groups for exclusion (heavy penalty).
     * Keys must use the canonical terms.
     */
    private val OPPOSITE_MOODS = mapOf(
        // Core Joy/Sadness Axis (180 degrees apart)
        "joy" to setOf("sadness", "reflection"),
        "sadness" to setOf("joy", "confidence", "excitement"),

        // Core Aggression/Calmness Axis (135 degrees apart)
        "aggression" to setOf("calmness", "love"),
        "calmness" to setOf("aggression", "excitement", "confidence"),

        // Other High-Contrast Mappings
        "reflection" to setOf("excitement"),
        "excitement" to setOf("calmness", "reflection")
    )

    /**
     * Checks if the dominant moods of two songs are emotional opposites using **normalized** moods.
     * @return A heavy negative score if conflict is found, otherwise 0.
     */
    private fun checkEmotionalConflict(a: SongMetaData, b: SongMetaData): Int {
        val aNormalized = normalizeMoodMap(a.moodPercentages)
        val bNormalized = normalizeMoodMap(b.moodPercentages)
        if (aNormalized.isNullOrEmpty() || bNormalized.isNullOrEmpty()) return 0

        val aDominantMoods = aNormalized
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }
            .toSet()

        val bDominantMoods = bNormalized
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }
            .toSet()

        for (aMood in aDominantMoods) {
            val opposites = OPPOSITE_MOODS[aMood] ?: continue
            if (bDominantMoods.any { it in opposites }) {
                Timber.tag(TAG).w("Emotional conflict detected: $aMood vs ${bDominantMoods.intersect(opposites).first()}. Applying heavy penalty.")
                return -500
            }
        }
        return 0
    }

    /**
     * Calculates the Mood Center of Gravity (COG) angle for a song's emotional moods.
     * Uses normalized mood percentages on the 8-point emotion wheel.
     */
    private fun getSongAngularCenter(meta: SongMetaData): Double? {
        // 1. Normalize and filter moods based on the wheel positions
        val moodMap = normalizeMoodMap(meta.moodPercentages)
        if (moodMap.isNullOrEmpty()) return null

        var xSum = 0.0
        var ySum = 0.0
        var totalWeight = 0.0

        for ((mood, percentage) in moodMap) {
            val angleDegrees = MOOD_ANGULAR_POSITIONS[mood]
            if (angleDegrees != null) {
                // Convert angle to radians for trigonometric functions
                val angleRad = Math.toRadians(angleDegrees)
                val weight = percentage.coerceAtMost(1.0)

                xSum += weight * cos(angleRad)
                ySum += weight * sin(angleRad)
                totalWeight += weight
            }
        }

        if (totalWeight == 0.0) return null

        // Calculate the resultant angle (in radians)
        val resultantAngleRad = atan2(ySum, xSum)

        // Convert the resultant angle back to degrees (0 to 360)
        var resultantAngleDegrees = Math.toDegrees(resultantAngleRad)
        if (resultantAngleDegrees < 0) {
            resultantAngleDegrees += 360.0
        }
        return resultantAngleDegrees
    }

    /**
     * Calculates the Genre Center of Gravity (COG) angle for a song's genres.
     * This uses the comprehensive FULL_GENRE_ANGULAR_POSITIONS map.
     */
    private fun getSongGenreAngularCenter(meta: SongMetaData): Double? {
        val genreMap = meta.genrePercentages
        if (genreMap.isNullOrEmpty()) return null

        var xSum = 0.0
        var ySum = 0.0
        var totalWeight = 0.0

        for ((genre, percentage) in genreMap) {
            // Use the expanded map to include user's custom genres
            val angleDegrees = FULL_GENRE_ANGULAR_POSITIONS[genre.lowercase()]
            if (angleDegrees != null) {
                val angleRad = Math.toRadians(angleDegrees)
                val weight = percentage.coerceAtMost(1.0)

                xSum += weight * cos(angleRad)
                ySum += weight * sin(angleRad)
                totalWeight += weight
            }
        }

        if (totalWeight == 0.0) return null

        val resultantAngleRad = atan2(ySum, xSum)
        var resultantAngleDegrees = Math.toDegrees(resultantAngleRad)
        if (resultantAngleDegrees < 0) {
            resultantAngleDegrees += 360.0
        }
        return resultantAngleDegrees
    }

    /**
     * Calculates a similarity score based on the shortest angular distance on the emotion wheel.
     * Penalizes songs that are further away (too far left/right/opposite).
     */
    private fun angularSimilarity(
        aAngle: Double?,
        bAngle: Double?,
        weight: Int,
        rand: Random
    ): Int {
        if (aAngle == null || bAngle == null) return 0

        // 1. Calculate the raw difference
        val diff = abs(aAngle - bAngle)

        // 2. Adjust for the circular nature (find the shortest path)
        val angularDistance = (diff).coerceAtMost(360.0 - diff)

        // The maximum possible distance is 180 degrees (opposite sides)
        val maxDistance = 180.0

        // Similarity is 1 - (Distance / MaxDistance)
        // 0 distance = 1.0 similarity (max score)
        // 180 distance = 0.0 similarity (min score)
        val similarity = 1.0 - (angularDistance / maxDistance)

        val baseScore = (similarity * weight).toInt()

        // 🔸 Add ±10% random variation to smooth comparisons
        val factor = rand.nextDouble(0.9, 1.1)
        return (baseScore * factor).toInt()
    }

    /**
     * Calculates a similarity score based on the shortest angular distance on the genre wheel.
     * This is used for Stage 2 (when no dominant genre match is found).
     */
    private fun genreAngularSimilarity(
        aAngle: Double?,
        bAngle: Double?,
        weight: Int,
        rand: Random
    ): Int {
        return angularSimilarity(aAngle, bAngle, weight, rand)
    }

    /**
     * Calculates a bonus based on the candidate song's overall "feel-good" score.
     * Rewards songs with high percentages in positive emotional categories (Joy, Confidence, etc.).
     */
    private fun getFeelGoodBonus(b: SongMetaData, rand: Random): Int {
        val bNormalized = normalizeMoodMap(b.moodPercentages)
        if (bNormalized.isNullOrEmpty()) {
            return 0
        }

        // Sum the percentages of all positive canonical moods in the candidate song (b)
        val totalPositiveScore = bNormalized.filterKeys { it in POSITIVE_MOODS }.values.sum()

        // The maximum possible sum is 1.0 (100%). We scale this up.
        // A base weight of 30 means a 100% positive song gets a bonus of up to 30.
        val baseBonus = (totalPositiveScore * 30).toInt()

        // Apply a high random factor to make this bonus feel 'moody' and not deterministic
        return randomWeight(baseBonus, rand, variation = 0.5)
    }

    // === END: Emotion Wheel and Genre Preference Constants and Helpers ===


    // === START: Custom File Logging Logic (Now internal to ShuffleHelper) ===

    // Use a unique tag to trigger file logging (prevents all other logs from going to the file)
    private const val FILE_LOGGER_TAG = "JSON_DUMP"

    /**
     * A custom Timber.Tree that specifically captures logs with the tag "JSON_DUMP"
     * and writes their content to a dedicated file on the device.
     */
    private class JsonFileLoggingTree(private val logFile: File) : Timber.DebugTree() {

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only capture logs intended for the file
            if (tag != FILE_LOGGER_TAG) return

            // Format the log entry: Timestamp | Message Content
            val logEntry = message

            try {
                // Use FileWriter with 'true' to append
                FileWriter(logFile, true).use { writer ->
                    writer.append(logEntry)
                }
            } catch (e: IOException) {
                // CRITICAL: If file logging fails, use the standard system log to report the error
                Timber.tag("JsonFileLoggingTree").e(e, "Failed to write JSON log to file")
            }
        }
    }

    private var isFileTreePlanted = false

    /**
     * Plants the custom log tree, ensuring it only runs once.
     */
    private fun plantJsonFileLoggingTree() {
        if (isFileTreePlanted) return

        // Assuming MetaDataManagerHelper.getContext() provides a valid Context
        val context = MetaDataManagerHelper.getContext()

        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                Timber.tag(TAG).e("Failed to create log directory for JSON dump.")
                return
            }
        }

        // The file where the complete JSON will be dumped
        val logFile = File(logDir, "corrupt_json_dump.json")
        Timber.plant(JsonFileLoggingTree(logFile))
        Timber.tag(TAG).d("JsonFileLoggingTree planted. Dump path: ${logFile.absolutePath}")
        isFileTreePlanted = true
    }
    // === END: Custom File Logging Logic ===

    // === START: Custom TypeAdapter to handle mixed JSON types in List<String> ===

    /**
     * Custom TypeAdapter for List<String> fields (like 'genre') that sometimes contain
     * JSON objects instead of strings, causing a JsonSyntaxException.
     * It skips the objects and only reads the strings, preventing the crash.
     */
    private class ListStringTypeAdapter : TypeAdapter<List<String>>() {
        override fun read(reader: JsonReader): List<String> {
            val list = mutableListOf<String>()
            // Ensure we start with an array
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue() // Skip if it's not an array
                return list
            }

            reader.beginArray()
            while (reader.hasNext()) {
                when (reader.peek()) {
                    // This is what Gson expects: a string
                    JsonToken.STRING -> {
                        val genre = reader.nextString()
                        if (genre.isNotBlank()) list.add(genre)
                    }
                    // This is the source of the crash: an object instead of a string
                    JsonToken.BEGIN_OBJECT -> {
                        Timber.tag(TAG).w("Skipped unexpected JSON object in genre list to prevent crash.")
                        reader.skipValue() // Consume the entire object safely
                    }
                    // Handle nulls and other primitives by skipping them
                    else -> reader.skipValue()
                }
            }
            reader.endArray()
            return list
        }

        override fun write(out: JsonWriter, value: List<String>?) {
            // This is not strictly needed for reading but required by the interface.
            if (value == null) {
                out.nullValue()
                return
            }
            out.beginArray()
            for (item in value) {
                out.value(item)
            }
            out.endArray()
        }
    }

    // === END: Custom TypeAdapter ===

    private fun loadMetadataMap(): Map<String, SongMetaData> {
        if (metadataMap != null) return metadataMap!!

        // Ensure the special file logger is active before attempting to parse
        plantJsonFileLoggingTree()

        val defaultSongsJson = SongDataManager.defaultSongsJson

        // The original log, which may still be helpful for general debugging (goes to Logcat)
        Timber.tag(TAG).d("JSON size: ${defaultSongsJson.length}")

        val listType = object : TypeToken<List<SongMetaData>>() {}.type
        val metadataList: List<SongMetaData>

        // Build Gson with the custom TypeAdapter for the List<String> type
        val gson = GsonBuilder()
            .registerTypeAdapter(
                object : TypeToken<List<String>>() {}.type,
                ListStringTypeAdapter()
            )
            .create()

        try {
            // Use the customized gson instance
            metadataList = gson.fromJson(defaultSongsJson, listType)
        } catch (e: JsonSyntaxException) {
            // 🚨 We caught the error! Log it to Logcat and dump the JSON to the file.
            Timber.tag(TAG).e(e, "FATAL JSON PARSING ERROR! Dumping full JSON to file for inspection.")

            // ➡️ THIS LOG DIRECTS THE MASSIVE STRING TO THE FILE LOGGER ⬅️
            Timber.tag(FILE_LOGGER_TAG).e(defaultSongsJson)

            // Re-throw the exception so the app still crashes, but you now have the file.
            throw e
        }

        val map = metadataList.filter { it.file.isNotBlank() }.associateBy { File(it.file).nameWithoutExtension.lowercase() }
        metadataMap = map
        return map
    }

    fun makeShuffleList(listToShuffle: MutableList<Song>, current: Int) {
        if (listToShuffle.isEmpty() || current !in listToShuffle.indices) return

        val songForCacheKey = listToShuffle[current]
        val cacheKey = getSongKey(songForCacheKey)
        val currentTime = System.currentTimeMillis()

        shuffleCache[cacheKey]?.let {
            if ((currentTime - it.timestamp) < CACHE_EXPIRY_MS) {
                Timber.tag(TAG).d("Cache hit for key '$cacheKey'. Using cached shuffle list.")
                listToShuffle.clear()
                listToShuffle.addAll(it.list)
                return
            }
        }

        val metadata = loadMetadataMap()
        val currentSong = listToShuffle.removeAt(current)

        if (listToShuffle.isEmpty()) {
            listToShuffle.add(0, currentSong)
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            Timber.tag(TAG).d("Cache updated for key '$cacheKey' after list became empty.")
            return
        }

        val currentMeta = metadata[getSongKey(currentSong)]
        val hasAnyMetadata = listToShuffle.any { metadata[getSongKey(it)] != null }

        if (currentMeta == null || !hasAnyMetadata) {
            Timber.tag(TAG).d("Current meta is null or no metadata in listToShuffle. Performing simple shuffle.")
            listToShuffle.shuffle()
            listToShuffle.add(0, currentSong)
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            Timber.tag(TAG).d("Cache updated for key '$cacheKey' after simple shuffle.")
            return
        }

        fun offlineshuffle(){
            Timber.tag(TAG).d("Performing offline shuffle for key '$cacheKey'.")
            val currentArtistsSet = currentMeta.artists.toSet()
            val scoredSongs = scoreSongs(listToShuffle, metadata, currentMeta)
            val originalScored = scoredSongs.toMutableList()

            // Filter out songs that have a massive negative penalty (emotional conflict)
            val filteredScored = originalScored.filter { it.score > -500 }.toMutableList()
            val removedCount = originalScored.size - filteredScored.size
            if (removedCount > 0) {
                Timber.tag(TAG).d("$removedCount songs removed due to emotional conflict.")
            }

            if (filteredScored.isNotEmpty()) {
                val topSongsForArtistCheck = filteredScored.take(7)
                var songsByCurrentArtistInTop = 0
                for (scoredSong in topSongsForArtistCheck) {
                    val songMeta = metadata[getSongKey(scoredSong.song)]
                    if (songMeta != null && songMeta.artists.any { it in currentArtistsSet }) {
                        songsByCurrentArtistInTop++
                    }
                }
                Timber.tag(TAG).d("Songs by current artist in top 7 for de-concentration: $songsByCurrentArtistInTop")
                if (songsByCurrentArtistInTop >= 7) {
                    Timber.tag(TAG).d("Applying artist de-concentration penalty.")
                    for (i in filteredScored.indices) {
                        val scoredSong = filteredScored[i]
                        val songMeta = metadata[getSongKey(scoredSong.song)]
                        if (songMeta != null && songMeta.artists.any { it in currentArtistsSet }) {
                            val basePenalty = Random.nextInt(0, 15)
                            val numArtistsOnTrack = songMeta.artists.size.coerceAtLeast(1)
                            val adjustedPenalty = basePenalty / numArtistsOnTrack
                            filteredScored[i] = scoredSong.copy(score = scoredSong.score - adjustedPenalty)
                        }
                    }
                }
            }
            listToShuffle.clear()
            listToShuffle.add(currentSong)
            listToShuffle.addAll(filteredScored.sortedByDescending { it.score }.map { it.song })
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            Timber.tag(TAG).d("Offline shuffle complete. New list size: ${listToShuffle.size}")
        }

        suspend fun onlineShuffle() {
            var allArtists = ""
            currentMeta.artists.forEach { artist ->
                allArtists += " ${URLDecoder.decode(artist, StandardCharsets.UTF_8.toString())}"
            }
            val query = "${URLDecoder.decode(currentMeta.title, StandardCharsets.UTF_8.toString())} -$allArtists"
            Timber.tag(TAG).d("Starting onlineShuffle for key '$cacheKey'. Decoded Query with hyphen: '$query'")

            searchVideos(query)
                .onSuccess { searchResults ->
                    Timber.tag(TAG).d("searchVideos success. Found ${searchResults.size} results.")
                    val firstVideoId = searchResults.firstOrNull()?.videoId
                    if (firstVideoId == null) {
                        Timber.tag(TAG).d("No videoId found from search. Falling back to offline shuffle.")
                        offlineshuffle()
                        shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                        Timber.tag(TAG).d("Cache updated for key '$cacheKey' after fallback to offline shuffle (no videoId).")
                    } else {
                        Timber.tag(TAG).d("Found videoId: $firstVideoId. Getting similar content.")
                        getSimilarContent(firstVideoId)
                            .onSuccess { recommendedYtItems ->
                                Timber.tag(TAG).d("getSimilarContent success. Found ${recommendedYtItems.size} recommended YT items.")
                                val orderedLocalSongs = mutableListOf<Song>()
                                val songsToConsiderForMatching = listToShuffle.toMutableList()
                                Timber.tag(TAG).d("Initial songsToConsiderForMatching size: ${songsToConsiderForMatching.size}")

                                for (ytSong in recommendedYtItems) {
                                    val ytTitle = ytSong.title.trim().lowercase()
                                    val ytArtistNames = ytSong.artists
                                        .map { artist -> artist.name.trim().lowercase() }
                                        .filter { it.isNotEmpty() }.toSet()
                                    // ... (rest of matching logic) ...
                                    var matchedLocalSong: Song? = null
                                    val iterator = songsToConsiderForMatching.iterator()
                                    while (iterator.hasNext()) {
                                        val localSong = iterator.next()
                                        val localSongMeta = metadata[getSongKey(localSong)]

                                        if (localSongMeta != null && !isCorrupted(localSongMeta)) {
                                            val localTitle = localSongMeta.title.trim().lowercase()
                                            val localArtistNames = localSongMeta.artists
                                                .map { artist -> artist.trim().lowercase() }
                                                .filter { it.isNotEmpty() }.toSet()

                                            val titleMatches = localTitle.contains(ytTitle) || ytTitle.contains(localTitle)
                                            val artistsMatch = ytArtistNames.isEmpty() ||
                                                    (localArtistNames.isNotEmpty() && ytArtistNames.intersect(localArtistNames).isNotEmpty())

                                            if (titleMatches && artistsMatch) {
                                                matchedLocalSong = localSong
                                                iterator.remove()
                                                break
                                            }
                                        }
                                    }
                                    matchedLocalSong?.let { song -> orderedLocalSongs.add(song) }
                                }

                                Timber.tag(TAG).d("Finished processing YT recommendations. orderedLocalSongs size: ${orderedLocalSongs.size}, songsToConsiderForMatching (remaining) size: ${songsToConsiderForMatching.size}")
                                songsToConsiderForMatching.shuffle()
                                Timber.tag(TAG).d("Shuffled remaining ${songsToConsiderForMatching.size} songs.")

                                listToShuffle.clear()
                                listToShuffle.add(currentSong)
                                listToShuffle.addAll(orderedLocalSongs)
                                listToShuffle.addAll(songsToConsiderForMatching)
                                Timber.tag(TAG).d("Online shuffle complete. Final listToShuffle size: ${listToShuffle.size}")

                                // START: Add similar songs logic (uses the injected repository)
                                Timber.tag(TAG).d("Starting to add similar songs based on online shuffle results.")
                                for (originalSong in orderedLocalSongs) {
                                    val existingSimilarSongs = repository.getSimilarSongsList(originalSong.id)
                                    if (existingSimilarSongs.isEmpty()) {
                                        var similarSongsAddedCount = 0
                                        val potentialSimilars = listToShuffle.filter { it.id != originalSong.id }
                                        for (candidateSong in potentialSimilars) {
                                            if (similarSongsAddedCount >= 10) break
                                            if (!repository.isSongSimilar(originalSong.id, candidateSong.id)) {
                                                repository.addSimilarSong(originalSong.id, candidateSong)
                                                similarSongsAddedCount++
                                            }
                                        }
                                    }
                                }
                                Timber.tag(TAG).d("Finished process of adding similar songs.")
                                // END: Add similar songs logic

                                shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                                Timber.tag(TAG).d("Cache updated for key '$cacheKey' after online shuffle & adding similar songs.")
                            }
                            .onFailure { exception ->
                                Timber.tag(TAG).d("getSimilarContent failed: ${exception.message}")
                                offlineshuffle()
                                shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                                Timber.tag(TAG).d("Cache updated for key '$cacheKey' after fallback (getSimilarContent failure).")
                            }
                    }
                }
                .onFailure { exception ->
                    Timber.tag(TAG).d("searchVideos failed: ${exception.message}")
                    offlineshuffle()
                    shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
                    Timber.tag(TAG).d("Cache updated for key '$cacheKey' after fallback (searchVideos failure).")
                }
        }

        if (InternetConnection.hasInternetConnection(MetaDataManagerHelper.getContext())) {
            Timber.tag(TAG).d("Internet connection available. Attempting online shuffle for key '$cacheKey'.")
//            runBlocking { onlineShuffle() }
            offlineshuffle()
        } else {
            Timber.tag(TAG).d("No internet connection. Performing offline shuffle for key '$cacheKey'.")
            offlineshuffle()
            shuffleCache[cacheKey] = CachedShuffle(listToShuffle.toList(), System.currentTimeMillis())
            Timber.tag(TAG).d("Cache updated for key '$cacheKey' after offline shuffle (no internet).")
        }
    }

    data class ScoredSong(val song: Song, val score: Int)

    private fun scoreSongs(
        songs: List<Song>,
        metadata: Map<String, SongMetaData>,
        currentMeta: SongMetaData
    ): List<ScoredSong> {
        val rand = Random.Default
        return songs.map { song ->
            val meta = metadata[getSongKey(song)]
            val baseScore = if (meta == null || isCorrupted(meta))
                rand.nextInt(-5, 0)
            else
                calculateSimilarity(currentMeta, meta, rand)
            // Add a small amount of random noise to prevent ties in sorting
            val noisyScore = baseScore + rand.nextInt(-3, 4)
            ScoredSong(song, noisyScore)
        }
    }

    private fun getSongKey(song: Song) =
        File(song.data).nameWithoutExtension.lowercase()

    private fun calculateSimilarity(a: SongMetaData, b: SongMetaData, rand: Random): Int {
        Timber.tag(TAG).d("Calculating similarity between '${a.title}' and '${b.title}'")

        // 1. Emotion Wheel Conflict Check (Exclusion Rule)
        // This check now uses normalized moods internally.
        val emotionalConflictPenalty = checkEmotionalConflict(a, b)
        if (emotionalConflictPenalty < 0) {
            return emotionalConflictPenalty // Return massive negative score immediately to exclude
        }

        // 2. Similarity Scoring Components
        val aAngle = getSongAngularCenter(a)
        val bAngle = getSongAngularCenter(b)

        val artistWeight = randomWeight(4, rand)
        val artistScore = overlapScore(a.artists, b.artists, artistWeight)
        Timber.tag(TAG).d("  - Artist Score: $artistScore (Weight: $artistWeight)")

        // --- Angular Mood Scoring (Core emotional drift logic) ---
        val moodWeight = randomWeight(25, rand)
        val moodScore = angularSimilarity(aAngle, bAngle, moodWeight, rand)
        Timber.tag(TAG).d("  - Mood Score (Angular): $moodScore (Weight: $moodWeight, Angle A: ${aAngle?.roundToInt()}, Angle B: ${bAngle?.roundToInt()})")

        // --- Conditional Genre Scoring Logic (NEW PRIORITY SYSTEM) ---
        val aDominantGenres = a.genrePercentages?.filterValues { it >= 0.20 }?.keys ?: emptySet()
        val bDominantGenres = b.genrePercentages?.filterValues { it >= 0.20 }?.keys ?: emptySet()
        val genreOverlap = aDominantGenres.intersect(bDominantGenres)

        val finalGenreScore: Int

        if (genreOverlap.isNotEmpty()) {
            // Stage 1: Dominant Genre Match Found
            // Give a massive, non-random bonus to ensure genre continuity is heavily favored.
            val genreMatchBonus = 500

            // Add a minor component of the old cosine similarity to break ties *within* the same dominant genre.
            val minorGenreSimilarityScore = mapSimilarity(a.genrePercentages, b.genrePercentages, randomWeight(10, rand))

            finalGenreScore = genreMatchBonus + minorGenreSimilarityScore

            Timber.tag(TAG).d("  - Genre Score (Stage 1 - Match): $finalGenreScore (MASSIVE BONUS applied)")

        } else {
            // Stage 2: No Dominant Genre Match Found - Enforce Directional Commitment
            val aGenreAngle = getSongGenreAngularCenter(a)
            val bGenreAngle = getSongGenreAngularCenter(b)

            val genreWeightAngular = randomWeight(50, rand) // High weight to make angular proximity important
            var baseAngularScore = genreAngularSimilarity(aGenreAngle, bGenreAngle, genreWeightAngular, rand)

            // --- NEW DIRECTIONAL COHERENCE CHECK: Prevent wide-angle jumps (scattering) ---
            var directionalPenalty: Int
            if (aGenreAngle != null && bGenreAngle != null) {
                // Calculate the angular distance (shortest path, 0-180 degrees)
                val diff = abs(aGenreAngle - bGenreAngle)
                val angularDistance = (diff).coerceAtMost(360.0 - diff)

                // If the transition is greater than 90 degrees, it means the song is more than a quarter-wheel away.
                // This is considered a "scatter" jump and is heavily penalized to force movement to the immediate neighbors.
                if (angularDistance > 90.0) {
                    // Penalty increases the further away the song is past the 90-degree threshold.
                    // Penalty magnitude: 5 points per degree past 90.
                    val degreesPastThreshold = angularDistance - 90.0
                    directionalPenalty = -(degreesPastThreshold * 5).toInt()
                    baseAngularScore += directionalPenalty
                    Timber.tag(TAG).d("  - Directional Penalty: $directionalPenalty (Jump > 90 degrees)")
                }
            }
            // Note: True directional commitment (CW only) requires keeping track of state
            // from the previous song, which is not supported by this function's architecture.
            // This penalty achieves the non-scattering requirement by only allowing immediate neighbors.
            // --- END NEW DIRECTIONAL COHERENCE CHECK ---

            finalGenreScore = baseAngularScore

            Timber.tag(TAG).d("  - Genre Score (Stage 2 - Angular): $finalGenreScore (Weight: $genreWeightAngular)")
        }
        // --- End Genre Scoring ---

        val tempoWeight = randomWeight(10, rand)
        val tempoScore = numericSimilarity(a.tempo, b.tempo, tempoWeight, maxDiff = 50.0, rand)
        Timber.tag(TAG).d("  - Tempo Score: $tempoScore (Weight: $tempoWeight)")

        val energyWeight = randomWeight(15, rand)
        val energyScore = numericSimilarity(a.energy, b.energy, energyWeight, maxDiff = 0.3, rand)
        Timber.tag(TAG).d("  - Energy Score: $energyScore (Weight: $energyWeight)")

        // 3. User Preference Bonuses/Penalties
        val feelGoodBonus = getFeelGoodBonus(b, rand)
        Timber.tag(TAG).d("  - Feel Good Bonus: $feelGoodBonus")

        val recencyPenalty = recencyPenalty(b)
        Timber.tag(TAG).d("  - Recency Penalty: $recencyPenalty")

        val ratingBonus = ratingBonus(b, rand)
        Timber.tag(TAG).d("  - Rating Bonus: $ratingBonus")

        val finalScore = (artistScore + finalGenreScore + moodScore + tempoScore + energyScore + ratingBonus + feelGoodBonus - recencyPenalty)

        Timber.tag(TAG).d("Final Similarity Score for '${b.title}': $finalScore (Conflict Penalty: $emotionalConflictPenalty)")

        return finalScore
    }

    private fun mapSimilarity(
        a: Map<String, Double>?,
        b: Map<String, Double>?,
        weight: Int
    ): Int {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return 0

        // Compute dot product and magnitudes for similarity (Cosine Similarity)
        var dotProduct = 0.0
        var aMagnitude = 0.0
        var bMagnitude = 0.0

        val allKeys = (a.keys + b.keys).toSet()

        for (key in allKeys) {
            val aValue = a[key] ?: 0.0
            val bValue = b[key] ?: 0.0

            aMagnitude += aValue * aValue
            bMagnitude += bValue * bValue
            dotProduct += aValue * bValue
        }

        if (aMagnitude == 0.0 || bMagnitude == 0.0) return 0

        val similarity = dotProduct / (sqrt(aMagnitude) * sqrt(bMagnitude))

        // Penalize the score if there are large non-overlapping components.
        val nonOverlapPenalty = run {
            var penalty = 0.0
            for ((key, aValue) in a) {
                if (!b.containsKey(key)) penalty += aValue
            }
            for ((key, bValue) in b) {
                if (!a.containsKey(key)) penalty += bValue
            }
            penalty * 0.5
        }

        val score = (similarity - nonOverlapPenalty).coerceAtLeast(0.0) * weight

        return score.roundToInt()
    }

    private fun overlapScore(
        aList: List<String>?, bList: List<String>?,
        weight: Int, maxItems: Int = Int.MAX_VALUE
    ): Int {
        val common = (aList?.take(maxItems) ?: emptyList()).intersect(bList?.toSet() ?: emptySet())
        return common.size * weight
    }

    private fun numericSimilarity(a: Double?, b: Double?, scale: Int, maxDiff: Double, rand: Random): Int {
        if (a == null || b == null) return 0
        val diff = (abs(a - b) / maxDiff).coerceAtMost(1.0)
        val baseScore = ((1 - diff) * scale).toInt()
        // 🔸 Add ±10% random variation to smooth numeric comparisons
        val factor = rand.nextDouble(0.9, 1.1)
        return (baseScore * factor).toInt()
    }

    private fun randomWeight(base: Int, rand: Random, variation: Double = 0.2): Int {
        // 🔸 Produce a random multiplier between (1 - variation) and (1 + variation)
        val factor = rand.nextDouble(1.0 - variation, 1.0 + variation)
        return (base * factor).toInt()
    }

    private fun recencyPenalty(b: SongMetaData): Int {
        val currentTime = System.currentTimeMillis()
        val twoWeeks = TimeUnit.DAYS.toMillis(14)
        val oneWeek = TimeUnit.DAYS.toMillis(7)
        val recentPlays = b.playTimestamps.count { (currentTime - it) < twoWeeks }
        val recentSkips = b.skipTimestamps.count { (currentTime - it) < oneWeek }
        return (recentPlays-5) + recentSkips
    }

    private fun ratingBonus(b: SongMetaData, rand: Random): Int {
        var bonus = 0
        if (b.liked) bonus += rand.nextInt(5, 12)
        if (b.favorite) bonus += rand.nextInt(10, 20)
        bonus += when {
            b.rating >= 4 -> rand.nextInt(5, 15)
            b.rating == 3 -> rand.nextInt(0, 5)
            b.rating in 1..1 -> -rand.nextInt(5, 15)
            else -> 0
        }
        return bonus
    }

    private fun isCorrupted(meta: SongMetaData): Boolean {
        val hasNoTitle = meta.title.isBlank()
        val hasNoArtists = meta.artists.isEmpty() || meta.artists.all { it.isBlank() }
        return hasNoTitle && hasNoArtists
    }
}
