package code.name.monkey.lost.model

import androidx.compose.runtime.Immutable
import com.metrolist.innertube.models.SongItem
import java.io.Serializable
import java.time.LocalDateTime

@Immutable
data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val album: Album? = null,
    val setVideoId: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val inLibrary: LocalDateTime? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
) : Serializable {
    data class Artist(
        val id: String?,
        val name: String,
    ) : Serializable

    data class Album(
        val id: String,
        val title: String,
    ) : Serializable


}

fun Song.toMediaMetadata() =
    MediaMetadata(
        id = id.toString(),
        title = title,
        artists =
        artistNames.zip(artistIds).map { (name, id) ->
            MediaMetadata.Artist(
                id = id.toString(),
                name = name,
            )
        },
        duration = duration.toInt(),
        thumbnailUrl = ytID?.let { "https://img.youtube.com/vi/$it/default.jpg" },
        album =
        if (albumId != -1L && albumName.isNotBlank()) {
            MediaMetadata.Album(
                id = albumId.toString(),
                title = albumName,
            )
        } else {
            null
        },
    )

fun SongItem.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists =
        artists.map {
            MediaMetadata.Artist(
                id = it.id,
                name = it.name,
            )
        },
        duration = duration ?: -1,
        thumbnailUrl = thumbnail,
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.name,
            )
        },
        explicit = explicit,
        setVideoId = setVideoId,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken
    )
