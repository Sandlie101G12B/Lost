package code.name.monkey.lost.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.lost.*
import code.name.monkey.lost.adapter.base.MediaEntryViewHolder
import code.name.monkey.lost.db.PlaylistWithSongs
import code.name.monkey.lost.glide.LostGlideExtension
import code.name.monkey.lost.glide.LostGlideExtension.albumCoverOptions
import code.name.monkey.lost.glide.LostGlideExtension.artistImageOptions
import code.name.monkey.lost.glide.LostGlideExtension.songCoverOptions
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.helper.menu.SongMenuHelper
import code.name.monkey.lost.model.Album
import code.name.monkey.lost.model.Artist
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.network.InternetConnection
import code.name.monkey.lost.util.MusicUtil
import code.name.monkey.lost.util.YTPlayerUtils.getSimilarContent
import com.bumptech.glide.Glide
import kotlinx.coroutines.runBlocking
import java.util.*

class SearchAdapter(
    private val activity: FragmentActivity,
    private var dataSet: List<Any>
) : RecyclerView.Adapter<SearchAdapter.ViewHolder>() {

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Any>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if (dataSet[position] is Album) return ALBUM
        if (dataSet[position] is Artist) return if ((dataSet[position] as Artist).isAlbumArtist) ALBUM_ARTIST else ARTIST
        if (dataSet[position] is Genre) return GENRE
        if (dataSet[position] is PlaylistWithSongs) return PLAYLIST
        return if (dataSet[position] is Song) SONG else HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return when (viewType) {
            HEADER -> ViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.sub_header,
                    parent,
                    false
                ), viewType
            )

            ALBUM, ARTIST, ALBUM_ARTIST -> ViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.item_list_big,
                    parent,
                    false
                ), viewType
            )

            else -> ViewHolder(
                LayoutInflater.from(activity).inflate(R.layout.item_list, parent, false),
                viewType
            )
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            ALBUM -> {
                holder.imageTextContainer?.isVisible = true
                val album = dataSet[position] as Album
                holder.title?.text = album.title
                holder.text?.text = album.artistName
                Glide.with(activity).asDrawable().albumCoverOptions(album.safeGetFirstSong())
                    .load(LostGlideExtension.getSongModel(album.safeGetFirstSong()))
                    .into(holder.image!!)
            }

            ARTIST -> {
                holder.imageTextContainer?.isVisible = true
                val artist = dataSet[position] as Artist
                holder.title?.text = artist.name
                holder.text?.text = MusicUtil.getArtistInfoString(activity, artist)
                Glide.with(activity).asDrawable().artistImageOptions(artist).load(
                    LostGlideExtension.getArtistModel(artist)
                ).into(holder.image!!)
            }

            SONG -> {
                holder.imageTextContainer?.isVisible = true
                val song = dataSet[position] as Song
                holder.title?.text = song.title
                // For YouTube songs, display artist name as subtext, otherwise album name.
                holder.text?.text = if (song.isYTSong) song.artistName else song.albumName

                // Prepare Glide request with common options
                val glideRequest = Glide.with(activity).asDrawable().songCoverOptions(song)

                if (song.data.startsWith("https")) {
                    // It's a YouTube song, load thumbnail from URL
                    val thumbnailUrl = "https://img.youtube.com/vi/${song.ytID}/mqdefault.jpg" // Medium quality
                    glideRequest.load(thumbnailUrl).into(holder.image!!)
                } else {
                    // It's a local song, load using existing method
                    glideRequest.load(LostGlideExtension.getSongModel(song)).into(holder.image!!)
                }
            }

            GENRE -> {
                val genre = dataSet[position] as Genre
                holder.title?.text = genre.name
                holder.text?.text = String.format(
                    Locale.getDefault(),
                    "%d %s",
                    genre.songCount,
                    if (genre.songCount > 1) activity.getString(R.string.songs) else activity.getString(
                        R.string.song
                    )
                )
            }

            PLAYLIST -> {
                val playlist = dataSet[position] as PlaylistWithSongs
                holder.title?.text = playlist.playlistEntity.playlistName
                //holder.text?.text = MusicUtil.playlistInfoString(activity, playlist.songs)
            }

            ALBUM_ARTIST -> {
                holder.imageTextContainer?.isVisible = true
                val artist = dataSet[position] as Artist
                holder.title?.text = artist.name
                holder.text?.text = MusicUtil.getArtistInfoString(activity, artist)
                Glide.with(activity).asDrawable().artistImageOptions(artist).load(
                    LostGlideExtension.getArtistModel(artist)
                ).into(holder.image!!)
            }

            else -> {
                holder.title?.text = dataSet[position].toString()
                holder.title?.setTextColor(ThemeStore.accentColor(activity))
            }
        }
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    inner class ViewHolder(itemView: View, itemViewType: Int) : MediaEntryViewHolder(itemView) {
        init {
            itemView.setOnLongClickListener(null)
            imageTextContainer?.isInvisible = true
            if (itemViewType == SONG) {
                imageTextContainer?.isGone = true
                menu?.isVisible = true
                menu?.setOnClickListener(object : SongMenuHelper.OnClickSongMenu(activity) {
                    override val song: Song
                        get() = dataSet[layoutPosition] as Song
                })
            } else {
                menu?.isVisible = false
            }

            when (itemViewType) {
                ALBUM -> setImageTransitionName(activity.getString(R.string.transition_album_art))
                ARTIST -> setImageTransitionName(activity.getString(R.string.transition_artist_image))
                else -> {
                    val container = itemView.findViewById<View>(R.id.imageContainer)
                    container?.isVisible = false
                }
            }
        }

        override fun onClick(v: View?) {
            val item = dataSet[layoutPosition]
            when (itemViewType) {
                ALBUM -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.albumDetailsFragment,
                        bundleOf(EXTRA_ALBUM_ID to (item as Album).id)
                    )
                }

                ARTIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.artistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_ID to (item as Artist).id)
                    )
                }

                ALBUM_ARTIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.albumArtistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_NAME to (item as Artist).name)
                    )
                }

                GENRE -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.genreDetailsFragment,
                        bundleOf(EXTRA_GENRE to (item as Genre))
                    )
                }

                PLAYLIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.playlistDetailsFragment,
                        bundleOf(EXTRA_PLAYLIST_ID to (item as PlaylistWithSongs).playlistEntity.playListId)
                    )
                }

                SONG -> {
                    val song = item as Song
                    if(!song.ytID.isNullOrBlank() && InternetConnection.hasInternetConnection(activity)) { // if there is internet connection and the song is not a local song

                        MusicPlayerRemote.clearQueue()
                        runBlocking {
                            getSimilarContent(song.ytID!!)
                                .onSuccess { 
                                    recommendedYtItems ->
                                    for (ytSong in recommendedYtItems){
                                        val actualArtistNameString = ytSong.artists.map { it.name }.joinToString(", ").let {
                                            if (it.isNotBlank()) it else "Unknown Artist"
                                        }
                                        val songToqueue = Song(
                                            id = ytSong.id.hashCode().toLong(), // Using videoId's hashcode as a placeholder ID
                                            title = ytSong.title ?: "Unknown Title",
                                            trackNumber = 0, // Default value
                                            year = 0, // Default value
                                            duration = 0L, // TODO: Parse ytSong.duration (String) to Long (milliseconds) correctly
                                            data = "https://www.youtube.com/watch?v=${ytSong.id}", // YouTube URL as data
                                            dateModified = System.currentTimeMillis(), // Current time for dateModified
                                            albumId = 0L, // Default value
                                            albumName = "Online Songs", // Default album name for online searches
                                            artistId = actualArtistNameString.hashCode().toLong(), // Placeholder artist ID from joined names
                                            artistName = actualArtistNameString, // Joined artist names
                                            composer = null, // No composer info from YouTubeSearchItem
                                            albumArtist = null, // No album artist info
                                            bpm = null, // No BPM info
                                            ytID = ytSong.id,
                                            isYTSong = true
                                        )
                                        MusicPlayerRemote.enqueue(songToqueue)
                                    }
                                }
                            MusicPlayerRemote.playSongAt(-1)
                        }
                    }else{
                        MusicPlayerRemote.playNext(song)
                        MusicPlayerRemote.playNextSong()
                    }
                }
            }
        }
    }

    companion object {
        private const val HEADER = 0
        private const val ALBUM = 1
        private const val ARTIST = 2
        private const val SONG = 3
        private const val GENRE = 4
        private const val PLAYLIST = 5
        private const val ALBUM_ARTIST = 6
    }
}
