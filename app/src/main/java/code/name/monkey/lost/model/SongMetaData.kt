package code.name.monkey.lost.model

import com.google.gson.annotations.SerializedName

data class SongMetaData(
    val title: String,
    val artists: List<String> = emptyList(),
    var file: String,
    val mood: List<String> = emptyList(),
    val genre: List<String> = emptyList(),
    val playlist: List<String> = emptyList(),
    val year: String = "",
    var liked: Boolean = false,
    var favorite: Boolean = false,
    var rating: Int = 0,
    val danceability: Double? = null,
    val tempo: Double? = null,
    val energy: Double? = null,
    val valence: Double? = null,
    val market: List<String>? = null,
    var skips: Int = 0,
    val bpm: Float? = null,
    var playTimestamps: MutableList<Long> = mutableListOf(),
    var skipTimestamps: MutableList<Long> = mutableListOf(),
    var likedTimestamp: Long? = null,
    val ytID: String? = null,
    val isYTSong: Boolean = false,
    val streamUrl: String? = null,
)

data class SongTMPContainer(
    val title: String,
    val artistName: List<String>?,
    val data: String,
    val year: Int? = null,
    val liked: Boolean? = false,
    val favorite: Boolean? = false,
    val rating: Int? = 0
)

data class SongStatistics(
    val songId: String,
    var playCount: Int = 0,
    var skipCount: Int = 0,
    var rating: Int = 0,
    var lastPlayed: Long = 0L
)

data class DataStatistics(
    val songStats: Map<String, Map<String, Any>>? = null,
    val sequences: List<Pair<String, Int>>? = null,
    val genres: List<Pair<String, Int>>? = null,
    val artists: List<Pair<String, Int>>? = null
)

enum class FlowType { RollerCoaster, WindDown, MoodLift, Pulse, Wave }

// Ensure this data class is defined, e.g., in the same file or a common models package
data class UserSongInputByNameAndArtists(
    val name: String,
    @SerializedName("artist")
    val artists: List<String>?
)