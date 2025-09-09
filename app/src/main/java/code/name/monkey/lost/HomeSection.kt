package code.name.monkey.lost

import androidx.annotation.IntDef

@IntDef(
    TOP_ARTISTS,
    SUGGESTIONS,
    FAVOURITES,
    GENRES,
    PLAYLISTS,
    HISTORY_PLAYLIST,
    LAST_ADDED_PLAYLIST,
    TOP_PLAYED_PLAYLIST,
    YOU_MIGHT_LIKE_SONGS,
    TRY_SOMETHING_NEW,
    SELECTED_FOR_YOUR_TASTE
)
@Retention(AnnotationRetention.SOURCE)
annotation class HomeSection

const val TOP_ARTISTS = 0
const val SUGGESTIONS = 5
const val FAVOURITES = 4
const val GENRES = 6
const val PLAYLISTS = 7
const val HISTORY_PLAYLIST = 8
const val LAST_ADDED_PLAYLIST = 9
const val TOP_PLAYED_PLAYLIST = 10
const val YOU_MIGHT_LIKE_SONGS = 3
const val TRY_SOMETHING_NEW = 2
const val SELECTED_FOR_YOUR_TASTE = 1
