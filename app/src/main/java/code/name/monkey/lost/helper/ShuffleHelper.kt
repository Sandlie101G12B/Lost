package code.name.monkey.lost.helper

import android.content.Context
import android.os.Environment // Added
import android.util.Log // Added
import code.name.monkey.lost.model.FlowType
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongMetaData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException // Added
import java.util.concurrent.TimeUnit // Added for time calculations
import kotlin.random.Random

object SongDataManager {
    var defaultSongsJson = "[]"
    private const val TAG = "SongDataManager" // Added for logging

    fun loadDefaultSongsJson(context: Context) {
        val sourceFile = File(context.filesDir, "outputile.txt")
        defaultSongsJson = if (!sourceFile.exists() || sourceFile.readText().isBlank()) {
            "[]"
        } else {
            sourceFile.readText()
        }

        // Start: Added code for copying the file
        if (sourceFile.exists()) {
            try {
                val destinationDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LostFiles")
                if (!destinationDir.exists()) {
                    if (!destinationDir.mkdirs()) {
                        Log.e(TAG, "Failed to create destination directory: ${destinationDir.absolutePath}")
                        return // Stop if directory creation fails
                    }
                }

                val destinationFile = File(destinationDir, "outputile.txt")

                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully copied outputile.txt to ${destinationFile.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Error copying file: ${e.message}", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Missing WRITE_EXTERNAL_STORAGE permission or other security issue. ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Source file outputile.txt does not exist in app's internal storage. Skipping copy.")
        }
        // End: Added code for copying the file
    }

    var songs: MutableList<SongMetaData> = mutableListOf()
}

object ShuffleHelper {
    private var metadataMap: Map<String, SongMetaData>? = null
    private fun loadMetadataMap(): Map<String, SongMetaData> {
        if (metadataMap != null) return metadataMap!!

        val defaultSongsJson = SongDataManager.defaultSongsJson
        val listType = object : TypeToken<List<SongMetaData>>() {}.type
        val metadataList: List<SongMetaData> = Gson().fromJson(defaultSongsJson, listType)

        val map = metadataList.filter { it.file.isNotBlank() }.associateBy { File(it.file).nameWithoutExtension.lowercase() }
        metadataMap = map
        return map
    }

    fun makeShuffleList(listToShuffle: MutableList<Song>, current: Int) {
        if (listToShuffle.isEmpty() || current !in listToShuffle.indices) return

        val metadata = loadMetadataMap()
        val currentSong = listToShuffle.removeAt(current)
        val currentMeta = metadata[getSongKey(currentSong)]
        val hasAnyMetadata = listToShuffle.any { metadata[getSongKey(it)] != null }
        if (currentMeta == null || !hasAnyMetadata) {
            listToShuffle.shuffle()
            listToShuffle.add(0, currentSong)
            return
        }
        val scoredSongs = scoreSongs(listToShuffle, metadata, currentMeta)
        val originalScored = scoredSongs.toMutableList()
        val selectedFlow = selectFlowType(currentMeta)
        val reordered: List<Pair<Song, Int>> = reorderByFlow(selectedFlow, originalScored, metadata)
        val finalOrdered = enforceMaxMovement(reordered, originalScored, maxMovement = Random.nextInt(5, 11))
        val smartShuffled = finalOrdered
            .groupBy { it.second }
            .toSortedMap(compareByDescending { it })
            .flatMap { (_, group) -> group.shuffled().map { it.first } }
        val extraRandomized = smartShuffled.toMutableList()
        val swapRange = Random.nextInt(2,5)
        val swaps = (extraRandomized.size / 7).coerceAtLeast(1)
        repeat(swaps) {
            val i = (1 until extraRandomized.size).random()
            // Only swap with a song within 3 positions away
            val minJ = (i - swapRange).coerceAtLeast(1)
            val maxJ = (i + swapRange).coerceAtMost(extraRandomized.size - 1)
            if (maxJ > minJ) {
                val j = (minJ..maxJ).filter { it != i }.random()
                val tmp = extraRandomized[i]
                extraRandomized[i] = extraRandomized[j]
                extraRandomized[j] = tmp
            }
        }
        listToShuffle.clear()
        listToShuffle.add(currentSong)
        listToShuffle.addAll(extraRandomized)
    }

    private fun scoreSongs(
        songs: List<Song>,
        metadata: Map<String, SongMetaData>,
        currentMeta: SongMetaData
    ): List<Pair<Song, Int>> {
        return songs.mapNotNull { song ->
            val meta = metadata[getSongKey(song)]
            println(meta)
            if (meta == null || isCorrupted(meta)) {
                null
            } else {
                val score = calculateSimilarity(currentMeta, meta)
                Pair(song, score)
            }
        }
    }

    /**
     * Selects the flow type based on the current song's metadata.
     */
    private fun selectFlowType(currentMeta: SongMetaData): FlowType {
        return when {
            (currentMeta.energy ?: 0.0) > 0.7 && (currentMeta.danceability)!! > 0.7 -> FlowType.Pulse
            (currentMeta.energy ?: 0.0) < 0.4 && (currentMeta.valence ?: 0.0) < 0.4 -> FlowType.WindDown
            (currentMeta.valence ?: 0.0) > 0.7 && (currentMeta.energy ?: 0.0) > 0.4 -> FlowType.MoodLift
            currentMeta.mood.any { it.contains("party", ignoreCase = true) || it.contains("dance", ignoreCase = true) } -> FlowType.RollerCoaster
            (currentMeta.tempo ?: 0.0) > 130.0 -> FlowType.Wave
            else -> FlowType.RollerCoaster
        }
    }

    private fun reorderByFlow(
        flow: FlowType,
        scored: List<Pair<Song, Int>>,
        metadata: Map<String, SongMetaData>
    ): List<Pair<Song, Int>> {
        fun Song.getMeta(): SongMetaData? = metadata[getSongKey(this)]
        return when (flow) {
            FlowType.RollerCoaster -> {
                val sorted = scored.sortedByDescending { it.first.getMeta()?.energy ?: 0.0 }
                val high = sorted.filterIndexed { i, _ -> i % 2 == 0 }
                val low = sorted.filterIndexed { i, _ -> i % 2 != 0 }.reversed()
                (high + low).take(scored.size)
            }
            FlowType.WindDown -> {
                scored.sortedWith(
                    compareByDescending<Pair<Song, Int>> { it.first.getMeta()?.energy ?: 0.0 }
                        .thenByDescending { it.first.getMeta()?.valence ?: 0.0 }
                )
            }
            FlowType.MoodLift -> {
                scored.sortedWith(
                    compareBy<Pair<Song, Int>> { it.first.getMeta()?.valence ?: 0.0 }
                        .thenBy { it.first.getMeta()?.energy ?: 0.0 }
                )
            }
            FlowType.Pulse -> {
                val sorted = scored.sortedByDescending { it.first.getMeta()?.danceability ?: 0.0 }
                val high = sorted.filterIndexed { i, _ -> i % 2 == 0 }
                val low = sorted.filterIndexed { i, _ -> i % 2 != 0 }.reversed()
                (high + low).take(scored.size)
            }
            FlowType.Wave -> {
                val sorted = scored.sortedByDescending { it.first.getMeta()?.energy ?: 0.0 }
                val chunked = sorted.chunked(5).flatMapIndexed { idx, chunk ->
                    if (idx % 2 == 0) chunk else chunk.reversed()
                }
                chunked
            }
        }
    }

    private fun enforceMaxMovement(
        reordered: List<Pair<Song, Int>>,
        originalScored: List<Pair<Song, Int>>,
        maxMovement: Int
    ): MutableList<Pair<Song, Int>> {
        val finalOrdered = MutableList(reordered.size) { reordered[it] }
        for ((originalIdx, pair) in reordered.withIndex()) {
            val origPos = originalScored.indexOf(pair)
            val minPos = (origPos - maxMovement).coerceAtLeast(0)
            val maxPos = (origPos + maxMovement).coerceAtMost(reordered.size - 1)
            val targetPos = originalIdx.coerceIn(minPos, maxPos)
            finalOrdered.remove(pair)
            finalOrdered.add(targetPos, pair)
        }
        return finalOrdered
    }

    private fun getSongKey(song: Song): String {
        return File(song.data).nameWithoutExtension.lowercase()
    }

    private fun calculateSimilarity(
        a: SongMetaData,
        b: SongMetaData,
        favoriteArtists: Set<String> = emptySet(),
        favoriteGenres: Set<String> = emptySet(),
        favoriteMoods: Set<String> = emptySet()
    ): Int {

        // 1. Artist Matching
        val commonArtists = a.artists.intersect(b.artists.toSet())
        val artistScore = commonArtists.size * Random.nextInt(9, 12)

        // 2. Genre Matching
        val commonGenres = a.genre.intersect(b.genre.toSet())
        val genreScore = commonGenres.size * Random.nextInt(8, 20)

        // 3. Mood Matching
        val commonMoods = a.mood.intersect(b.mood.toSet())
        val moodScore = commonMoods.size * Random.nextInt(7, 10)

        // 4. Danceability
        val danceabilityScore = (10 - (kotlin.math.abs(a.danceability?.minus(b.danceability ?: 0.0) ?: 0.0) * 10).coerceAtMost(10.0)).toInt()

        // 5. Market Similarity
        val marketScore = try {
            b.market?.let { a.market?.intersect(it.toSet())?.size ?: 0 }?.times(4) ?: 0
        } catch (_: Exception) { 0 }

        // 6. Year Proximity
        val yearScore = try {
            val aYear = a.year.toIntOrNull()
            val bYear = b.year.toIntOrNull()
            if (aYear != null && bYear != null && aYear > 0 && bYear > 0) {
                (7 - (kotlin.math.abs(aYear - bYear) / 2).coerceAtMost(10)).coerceAtLeast(-7)
            } else 0
        } catch (_: Exception) { 0 }

        // 7. Modern Song Bonus — strong boost for newer songs
        val modernBonus = try {
            val bYear = b.year.toIntOrNull()
            val normalized = (((bYear?.coerceIn(1990, 2025) ?: 0) - 1990) / 35.0)
            (normalized * Random.nextInt(5, 25)).toInt()
        } catch (_: Exception) { 0 }

        // 8. Energy
        val energyScore = if (a.energy != null && b.energy != null) {
            (10 - (kotlin.math.abs(a.energy - b.energy) * 10).coerceAtMost(10.0)).toInt()
        } else 0

        // 9. Valence
        val valenceScore = if (a.valence != null && b.valence != null) {
            (10 - (kotlin.math.abs(a.valence - b.valence) * 10).coerceAtMost(10.0)).toInt()
        } else 0

        // 10. Tempo
        val tempoScore = if (a.tempo != null && b.tempo != null) {
            (10 - (kotlin.math.abs(a.tempo - b.tempo) / 10).coerceAtMost(10.0)).toInt()
        } else 0

        // 11. Genre-Based Artist Similarity
        val genreArtistSimilarity = getGenreBasedArtistSimilarity(a, b)

        // 12. Favorite Preference Boost (optional)
        val favArtistBoost = b.artists.count { it in favoriteArtists } * 15
        val favGenreBoost = b.genre.count { it in favoriteGenres } * 10
        val favMoodBoost = b.mood.count { it in favoriteMoods } * 10

        // 13. Play History Penalty
        val currentTime = System.currentTimeMillis()
        val twoWeeksInMillis = TimeUnit.DAYS.toMillis(14)
        val recentPlays = (b.playTimestamps ?: emptyList()).count { (currentTime - it) < twoWeeksInMillis }
        var playHistoryPenalty = recentPlays * 7

        // 14. Skip History Penalty
        val oneWeekInMillis = TimeUnit.DAYS.toMillis(7)
        val recentSkips = (b.skipTimestamps ?: emptyList()).count { (currentTime - it) < oneWeekInMillis }
        var skipHistoryPenalty = recentSkips * Random.nextInt(9, 12)

        if (b.liked && b.likedTimestamp != null) {
            val likedTimeAgo = currentTime - (b.likedTimestamp ?: currentTime) // milliseconds
            val daysAgo = TimeUnit.MILLISECONDS.toDays(likedTimeAgo)
            val likedValue = (7 - daysAgo).toInt().coerceAtLeast(1)
            playHistoryPenalty -= likedValue
            skipHistoryPenalty -= likedValue
        }

        return artistScore +
                genreScore +
                moodScore +
                danceabilityScore +
                marketScore +
                yearScore +
                energyScore +
                valenceScore +
                tempoScore +
                genreArtistSimilarity +
                favArtistBoost +
                favGenreBoost +
                favMoodBoost +
                modernBonus -
                playHistoryPenalty -
                skipHistoryPenalty
    }

    private fun isCorrupted(meta: SongMetaData): Boolean {
        return meta.file.isBlank()
                || meta.artists.isEmpty()
                || meta.danceability?.isNaN() == true
    }

    private val similarArtistGroups = listOf(
        listOf("Drake", "Lil Wayne", "Future", "Kanye West", "21 Savage", "Travis Scott", "Young Thug", "Gunna", "DaBaby", "Pop Smoke", "ASAP Rocky", "Meek Mill", "Lil Baby", "Lil Durk", "Tyga","2 Chains"),
        listOf("Kendrick Lamar", "J. Cole", "Big Sean", "Joey BadaSS", "Logic", "Mac Miller", "Wale", "Denzel Curry", "NF", "Cordae", "Mick Jenkins", "IDK", "Isaiah Rashad", "Russ", "Bas"),
        listOf("Cardi B", "Nicki Minaj", "Megan Thee Stallion", "Doja Cat", "Latto", "Iggy Azalea", "Saweetie", "Remy Ma", "City Girls", "Coi Leray", "BIA", "Rico Nasty", "Chika", "Kash Doll", "CupcakKe"),
        listOf("Taylor Swift", "Selena Gomez", "Demi Lovato", "Olivia Rodrigo", "Katy Perry", "Billie Eilish", "Ava Max", "Sabrina Carpenter", "Tate McRae", "Charli XCX", "Hailee Steinfeld", "Halsey", "Bea Miller", "Bebe Rexha", "Anne-Marie"),
        listOf("Ariana Grande", "Dua Lipa", "Camila Cabello", "Rita Ora", "Zara Larsson", "Tinashe", "Alessia Cara", "Tove Lo", "Madison Beer", "Ellie Goulding", "Jessie J", "Sia", "Lady Gaga", "Lorde", "Britney Spears"),
        listOf("Justin Bieber", "Shawn Mendes", "Charlie Puth", "Troye Sivan", "Lauv", "Conan Gray", "Niall Horan", "ZAYN", "Jonas Brothers", "Ed Sheeran", "James Arthur", "Dean Lewis", "Lewis Capaldi", "Jason Derulo", "AJ Mitchell"),
        listOf("Metallica", "Slayer", "Megadeth", "Anthrax", "Pantera", "Iron Maiden", "Judas Priest", "Lamb of God", "Slipknot", "Korn", "Disturbed", "System of a Down", "Tool", "Avenged Sevenfold", "Ghost"),
        listOf("Nirvana", "Pearl Jam", "Soundgarden", "Alice in Chains", "Stone Temple Pilots", "Smashing Pumpkins", "Bush", "Temple of the Dog", "Silverchair", "Radiohead", "The Offspring", "Green Day", "Blink-182", "My Chemical Romance", "Fall Out Boy"),
        listOf("Imagine Dragons", "OneRepublic", "Coldplay", "Bastille", "X Ambassadors", "The Script", "Walk the Moon", "American Authors", "Foster the People", "AWOLNATION", "Arctic Monkeys", "The Killers", "Muse", "Thirty Seconds to Mars", "Kings of Leon"),
        listOf("The Weeknd", "Frank Ocean", "Miguel", "Chris Brown", "Trey Songz", "Bryson Tiller", "Giveon", "6LACK", "Khalid", "Daniel Caesar", "Tory Lanez", "PARTYNEXTDOOR", "Brent Faiyaz", "Ty Dolla Sign", "Eric Bellinger"),
        listOf("Bruno Mars", "Anderson .Paak", "Ne-Yo", "John Legend", "Usher", "Tank", "Robin Thicke", "Mario", "Ginuwine", "Maxwell", "Babyface", "Charlie Wilson", "Raheem DeVaughn", "Joe", "Lloyd"),
        listOf("Burna Boy", "Wizkid", "Davido", "Rema", "Tems", "Omah Lay", "Ayra Starr", "Fireboy DML", "Joeboy", "Tiwa Savage", "Yemi Alade", "Mr Eazi", "CKay", "Patoranking", "Tekno"),
        listOf("Bad Bunny", "J Balvin", "Ozuna", "Anuel AA", "Maluma", "Karol G", "Nicky Jam", "Daddy Yankee", "Farruko", "Becky G", "Myke Towers", "Sech", "Rauw Alejandro", "Manuel Turizo", "Feid"),
        listOf("Calvin Harris", "David Guetta", "Zedd", "Martin Garrix", "Kygo", "Avicii", "Alesso", "Steve Aoki", "Marshmello", "The Chainsmokers", "Alan Walker", "Tiesto", "Dillon Francis", "Illenium", "Don Diablo"),
        listOf("Luke Bryan", "Blake Shelton", "Jason Aldean", "Thomas Rhett", "Morgan Wallen", "Kane Brown", "Dierks Bentley", "Chris Stapleton", "Zac Brown Band", "Florida Georgia Line", "Tim McGraw", "Keith Urban", "Eric Church", "Sam Hunt", "Jake Owen"),
        listOf("Kabza De Small", "DJ Maphorisa", "Young Stunna", "Daliwonga", "Focalistic", "Sha Sha", "Mr JazziQ", "DBN Gogo", "Busta 929", "Mellow & Sleazy", "Uncle Waffles", "Boohle", "Zuma", "Reece Madlisa", "Tyler ICU", "Scotts Maphuma", "CowBoii", "Aymos"),
        listOf("Nasty C", "AKA", "Cassper Nyovest", "A-Reece", "Blxckie", "Emtee", "Kwesta", "Shane Eagle", "Big Zulu", "K.O", "Maglera Doe Boy", "Boity", "Nadia Nakai", "Priddy Ugly", "Reason"),
        listOf("Mandoza", "Arthur Mafokate", "Trompies", "Zola", "Chicco Twala", "Brickz", "Mzekezeke", "Mapaputsi", "Professor", "Spikiri", "DJ Cleo", "Oskido", "Big Nuz", "Thebe", "Boom Shaka"),
        listOf("Babes Wodumo", "Distuction Boyz", "DJ Tira", "Mampintsha", "RudeBoyz", "Dlala Thukzin", "Busiswa", "Tipcee", "Heavy-K", "Moonchild Sanelly", "Patoranking", "Zodwa Wabantu", "Goldmax", "Que", "Mr Thela"),
        listOf("Black Coffee", "Culoe De Song", "DJ Zinhle", "Heavy-K", "Prince Kaybee", "Sun-El Musician", "Master KG", "Samthing Soweto", "Msaki", "Da Capo", "DJ Kent", "DJ Sbu", "Lady Zamar", "Holly Rey", "DJ Merlon", "Nomcebo"),
        listOf("Shekhinah", "Elaine", "Ami Faku", "Simmy", "Lloyiso", "Manana", "Brenda Fassie", "Zonke", "Judith Sephuma", "Sjava", "Berita", "Nathi", "Amanda Black", "Azana", "Ntando"),
        listOf("Mi Casa", "Tresor", "Mafikizolo", "Jeremy Loops", "GoodLuck", "Danny K", "Majozi", "Locnville", "Matthew Mole", "Mellisa Allison", "Dr Victor", "Lira", "TKZee", "Bongo Maffin", "Micasa"),
        listOf("Joyous Celebration", "Rebecca Malope", "Winnie Mashaba", "Dr Tumi", "Sfiso Ncwane", "Solly Mahlangu", "Dumi Mkokstad", "Ntokozo Mbambo", "Benjamin Dube", "Lebo Sekgobela", "Sipho Makhabane", "Zaza", "Lundi Tyamara", "Kholeka", "Sechaba"),
        listOf("Lucky Dube", "Johnny Clegg", "Miriam Makeba", "Yvonne Chaka Chaka", "Brenda Fassie", "Busi Mhlongo", "Soweto Gospel Choir", "Thandiswa Mazwai", "Simphiwe Dana", "Oliver Mtukudzi", "Ringo Madlingozi", "Caiphus Semenya", "Letta Mbulu", "Judith Sephuma", "Sipho Hotstix Mabuse")
    )

    private fun getGenreBasedArtistSimilarity(a: SongMetaData, b: SongMetaData): Int {
        for (group in similarArtistGroups) {
            val groupSet = group.toSet()
            val aMatch = a.artists.any { it in groupSet }
            val bMatch = b.artists.any { it in groupSet }
            if (aMatch && bMatch) {
                return 20
            }
        }
        return 0
    }
}
