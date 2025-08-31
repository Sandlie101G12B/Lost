package code.name.monkey.lost.auto

import android.content.Context
import android.content.res.Resources
import android.support.v4.media.MediaBrowserCompat
import code.name.monkey.lost.R
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.model.CategoryInfo
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.Playlist // Assuming Playlist model and its methods like getInfoString are handled
import code.name.monkey.lost.repository.*
import code.name.monkey.lost.service.MusicService
import code.name.monkey.lost.util.MusicUtil
import code.name.monkey.lost.util.PreferenceUtil
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel // Keep for serviceScope if used elsewhere
import kotlinx.coroutines.runBlocking

/**
 * Created by Beesham Sarendranauth (Beesham)
 */
class AutoMusicProvider(
    private val mContext: Context,
    private val songsRepository: SongRepository,
    private val albumsRepository: AlbumRepository,
    private val artistsRepository: ArtistRepository,
    private val genresRepository: GenreRepository,
    private val playlistsRepository: PlaylistRepository,
    private val topPlayedRepository: TopPlayedRepository
) {

    private val serviceJob = SupervisorJob()
    // This scope can still be used for other truly asynchronous tasks in this class
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var mMusicService: WeakReference<MusicService>? = null

    fun setMusicService(service: MusicService) {
        mMusicService = WeakReference(service)
    }

    // getChildren is NOT suspend. It will execute its logic synchronously,
    // using runBlocking for internal suspend calls.
    fun getChildren(mediaId: String?, resources: Resources): List<MediaBrowserCompat.MediaItem> {
        val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()

        // The main logic is now directly part of getChildren's execution flow
        when (mediaId) {
            AutoMediaIDHelper.MEDIA_ID_ROOT -> {
                // getRootChildren will also use runBlocking for its internal repository calls
                mediaItems.addAll(getRootChildren(resources))
            }

            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_PLAYLIST -> {
                val playlists = runBlocking(Dispatchers.IO) {
                    playlistsRepository.playlists()
                }
                for (playlist in playlists) {
                    mediaItems.add(
                        AutoMediaItem.with(mContext)
                            .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_PLAYLIST, playlist.id)
                            .icon(R.drawable.ic_playlist_play)
                            .title(playlist.name)
                            .subTitle(playlist.getInfoString(mContext)) // Ensure getInfoString is not suspend or handles its own blocking
                            .asPlayable()
                            .build()
                    )
                }
            }

            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM -> {
                val albums = runBlocking(Dispatchers.IO) {
                    albumsRepository.albums()
                }
                for (album in albums) {
                    mediaItems.add(
                        AutoMediaItem.with(mContext)
                            .path(mediaId, album.id)
                            .title(album.title)
                            .subTitle(album.albumArtist ?: album.artistName)
                            .icon(MusicUtil.getMediaStoreAlbumCoverUri(album.id))
                            .asPlayable()
                            .build()
                    )
                }
            }

            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST -> {
                val artists = runBlocking(Dispatchers.IO) {
                    artistsRepository.artists()
                }
                for (artist in artists) {
                    mediaItems.add(
                        AutoMediaItem.with(mContext)
                            .asPlayable()
                            .path(mediaId, artist.id)
                            .title(artist.name)
                            .build()
                    )
                }
            }

            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM_ARTIST -> {
                val albumArtists = runBlocking(Dispatchers.IO) {
                    artistsRepository.albumArtists()
                }
                for (artist in albumArtists) {
                    mediaItems.add(
                        AutoMediaItem.with(mContext)
                            .asPlayable()
                            .path(mediaId, artist.safeGetFirstAlbum().id)
                            .title(artist.name)
                            .build()
                    )
                }
            }

            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_GENRE -> {
                val genres = runBlocking(Dispatchers.IO) {
                    genresRepository.genres()
                }
                for (genre in genres) {
                    mediaItems.add(
                        AutoMediaItem.with(mContext)
                            .asPlayable()
                            .path(mediaId, genre.id)
                            .title(genre.name)
                            .build()
                    )
                }
            }

            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_QUEUE ->
                mMusicService?.get()?.playingQueue
                    ?.let {
                        for (song in it) {
                            mediaItems.add(
                                AutoMediaItem.with(mContext)
                                    .asPlayable()
                                    .path(mediaId, song.id)
                                    .title(song.title)
                                    .subTitle(song.artistName)
                                    .icon(MusicUtil.getMediaStoreAlbumCoverUri(song.albumId))
                                    .build()
                            )
                        }
                    }

            else -> {
                // getPlaylistChildren will also use runBlocking for its internal repository calls
                getPlaylistChildren(mediaId, mediaItems)
            }
        }
        // This return is now correct and will have the fully populated list
        return mediaItems
    }

    private fun getPlaylistChildren(
        mediaId: String?,
        mediaItems: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val songs: List<Song> = when (mediaId) {
            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_TOP_TRACKS -> {
                runBlocking(Dispatchers.IO) { topPlayedRepository.topTracks() }
            }
            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_HISTORY -> {
                runBlocking(Dispatchers.IO) { topPlayedRepository.recentlyPlayedTracks() }
            }
            AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_SUGGESTIONS -> {
                runBlocking(Dispatchers.IO) { topPlayedRepository.notRecentlyPlayedTracks() }.take(8)
            }
            else -> {
                emptyList()
            }
        }
        songs.forEach { song ->
            mediaItems.add(
                getPlayableSong(mediaId, song)
            )
        }
    }

    private fun getRootChildren(resources: Resources): List<MediaBrowserCompat.MediaItem> {
        val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()
        val libraryCategories = PreferenceUtil.libraryCategory
        libraryCategories.forEach {
            if (it.visible) {
                when (it.category) {
                    CategoryInfo.Category.Albums -> {
                        mediaItems.add(
                            AutoMediaItem.with(mContext)
                                .asBrowsable()
                                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM)
                                .gridLayout(true)
                                .icon(R.drawable.ic_album)
                                .title(resources.getString(R.string.albums)).build()
                        )
                    }
                    CategoryInfo.Category.Artists -> {
                        if (PreferenceUtil.albumArtistsOnly) {
                            mediaItems.add(
                                AutoMediaItem.with(mContext)
                                    .asBrowsable()
                                    .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM_ARTIST)
                                    .icon(R.drawable.ic_album_artist)
                                    .title(resources.getString(R.string.album_artist)).build()
                            )
                        } else {
                            mediaItems.add(
                                AutoMediaItem.with(mContext)
                                    .asBrowsable()
                                    .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST)
                                    .icon(R.drawable.ic_artist)
                                    .title(resources.getString(R.string.artists)).build()
                            )
                        }
                    }
                    CategoryInfo.Category.Genres -> {
                        mediaItems.add(
                            AutoMediaItem.with(mContext)
                                .asBrowsable()
                                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_GENRE)
                                .icon(R.drawable.ic_guitar)
                                .title(resources.getString(R.string.genres)).build()
                        )
                    }
                    CategoryInfo.Category.Playlists -> {
                        mediaItems.add(
                            AutoMediaItem.with(mContext)
                                .asBrowsable()
                                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_PLAYLIST)
                                .icon(R.drawable.ic_playlist_play)
                                .title(resources.getString(R.string.playlists)).build()
                        )
                    }
                    else -> {
                    }
                }
            }
        }
        mediaItems.add(
            AutoMediaItem.with(mContext)
                .asPlayable()
                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_SHUFFLE)
                .icon(R.drawable.ic_shuffle)
                .title(resources.getString(R.string.action_shuffle_all))
                .subTitle(MusicUtil.getPlaylistInfoString(mContext, runBlocking(Dispatchers.IO) { songsRepository.songs() }))
                .build()
        )
        mediaItems.add(
            AutoMediaItem.with(mContext)
                .asBrowsable()
                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_QUEUE)
                .icon(R.drawable.ic_queue_music)
                .title(resources.getString(R.string.queue))
                .subTitle(MusicUtil.getPlaylistInfoString(mContext, MusicPlayerRemote.playingQueue))
                .asBrowsable().build()
        )
        mediaItems.add(
            AutoMediaItem.with(mContext)
                .asBrowsable()
                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_TOP_TRACKS)
                .icon(R.drawable.ic_trending_up)
                .title(resources.getString(R.string.my_top_tracks))
                .subTitle(
                    MusicUtil.getPlaylistInfoString(
                        mContext,
                        runBlocking(Dispatchers.IO) { topPlayedRepository.topTracks() }
                    )
                )
                .asBrowsable().build()
        )
        mediaItems.add(
            AutoMediaItem.with(mContext)
                .asBrowsable()
                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_SUGGESTIONS)
                .icon(R.drawable.ic_face)
                .title(resources.getString(R.string.suggestion_songs))
                .subTitle(
                    MusicUtil.getPlaylistInfoString(
                        mContext,
                        runBlocking(Dispatchers.IO) { topPlayedRepository.notRecentlyPlayedTracks() }.takeIf {
                            it.size > 9
                        } ?: emptyList()
                    )
                )
                .asBrowsable().build()
        )
        mediaItems.add(
            AutoMediaItem.with(mContext)
                .asBrowsable()
                .path(AutoMediaIDHelper.MEDIA_ID_MUSICS_BY_HISTORY)
                .icon(R.drawable.ic_history)
                .title(resources.getString(R.string.history))
                .subTitle(
                    MusicUtil.getPlaylistInfoString(
                        mContext,
                        runBlocking(Dispatchers.IO) { topPlayedRepository.recentlyPlayedTracks() }
                    )
                )
                .asBrowsable().build()
        )
        return mediaItems
    }

    private fun getPlayableSong(mediaId: String?, song: Song): MediaBrowserCompat.MediaItem {
        return AutoMediaItem.with(mContext)
            .asPlayable()
            .path(mediaId, song.id)
            .title(song.title)
            .subTitle(song.artistName)
            .icon(MusicUtil.getMediaStoreAlbumCoverUri(song.albumId))
            .build()
    }

    // Call this to clean up the SupervisorJob when AutoMusicProvider is no longer needed
    fun onCleared() {
        serviceJob.cancel()
    }
}
