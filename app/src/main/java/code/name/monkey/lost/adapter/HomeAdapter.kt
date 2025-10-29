package code.name.monkey.lost.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.bundleOf
import androidx.fragment.app.findFragment
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.lost.*
import code.name.monkey.lost.adapter.album.AlbumAdapter
import code.name.monkey.lost.adapter.artist.ArtistAdapter
import code.name.monkey.lost.adapter.song.SongAdapter
import code.name.monkey.lost.fragments.home.HomeFragment
import code.name.monkey.lost.interfaces.IAlbumClickListener
import code.name.monkey.lost.interfaces.IArtistClickListener
import code.name.monkey.lost.model.Album
import code.name.monkey.lost.model.Artist
import code.name.monkey.lost.model.Home
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.util.PreferenceUtil

class HomeAdapter(private val activity: AppCompatActivity) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(), IArtistClickListener, IAlbumClickListener {

    private var list = listOf<Home>()

    override fun getItemViewType(position: Int): Int {
        return list[position].homeSection
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout =
            LayoutInflater.from(activity).inflate(R.layout.section_recycler_view, parent, false)
        return when (viewType) {
            TOP_ARTISTS -> ArtistViewHolder(layout)
            FAVOURITES, YOU_MIGHT_LIKE_SONGS, TRY_SOMETHING_NEW, SELECTED_FOR_YOUR_TASTE -> PlaylistViewHolder(layout)
            else -> {
                ArtistViewHolder(layout)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val home = list[position]
        when (getItemViewType(position)) {
            TOP_ARTISTS -> {
                val viewHolder = holder as ArtistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().setSharedAxisXTransitions()
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.detailListFragment,
                        bundleOf("type" to TOP_ARTISTS)
                    )
                }
            }
            FAVOURITES -> {
                val viewHolder = holder as PlaylistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().setSharedAxisXTransitions()
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.detailListFragment,
                        bundleOf("type" to FAVOURITES)
                    )
                }
            }
            YOU_MIGHT_LIKE_SONGS -> {
                val viewHolder = holder as PlaylistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    it.findFragment<HomeFragment>().setSharedAxisXTransitions()
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.detailListFragment,
                        bundleOf("type" to YOU_MIGHT_LIKE_SONGS)
                    )
                }
            }
            TRY_SOMETHING_NEW -> {
                val viewHolder = holder as PlaylistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    val songs = home.arrayList.filterIsInstance<Song>()
                    if (songs.isNotEmpty()) {
                        MusicPlayerRemote.enqueue(songs)
                    }
                }
            }
            SELECTED_FOR_YOUR_TASTE -> {
                val viewHolder = holder as PlaylistViewHolder
                viewHolder.bindView(home)
                viewHolder.clickableArea.setOnClickListener {
                    val songs = home.arrayList.filterIsInstance<Song>()
                    if (songs.isNotEmpty()) {
                        MusicPlayerRemote.enqueue(songs)
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapData(sections: List<Home>) {
        list = sections
        notifyDataSetChanged()
    }

    @Suppress("UNCHECKED_CAST")
    private inner class AlbumViewHolder(view: View) : AbsHomeViewItem(view) {
        fun bindView(home: Home) {
            title.setText(home.titleRes)
            recyclerView.apply {
                adapter = albumAdapter(home.arrayList as List<Album>)
                layoutManager = gridLayoutManager()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inner class ArtistViewHolder(view: View) : AbsHomeViewItem(view) {
        fun bindView(home: Home) {
            title.setText(home.titleRes)
            recyclerView.apply {
                layoutManager = linearLayoutManager()
                adapter = artistsAdapter(home.arrayList as List<Artist>)
            }
        }
    }


    @Suppress("UNCHECKED_CAST")
    private inner class PlaylistViewHolder(view: View) : AbsHomeViewItem(view) {
        fun bindView(home: Home) {
            title.setText(home.titleRes)
            recyclerView.apply {
                val songsList = home.arrayList.filterIsInstance<Song>()

                val itemLayoutId = if (home.homeSection == TRY_SOMETHING_NEW || home.homeSection == SELECTED_FOR_YOUR_TASTE) {
                    R.layout.item_try_new_song // Use new layout for TRY_SOMETHING_NEW
                } else {
                    R.layout.item_favourite_card // Default layout for others
                }

                val songsToShow = if (home.homeSection == TRY_SOMETHING_NEW || home.homeSection == SELECTED_FOR_YOUR_TASTE || home.homeSection == YOU_MIGHT_LIKE_SONGS) {
                    songsList.take(3)
                } else {
                    songsList
                }

                val songAdapter = if (home.homeSection == TRY_SOMETHING_NEW || home.homeSection == SELECTED_FOR_YOUR_TASTE || home.homeSection == YOU_MIGHT_LIKE_SONGS) {
                    object: SongAdapter(activity, songsToShow.toMutableList(), itemLayoutId) {
                        override fun createViewHolder(view: View): ViewHolder {
                            return object : ViewHolder(view) {
                                override fun onClick(v: View?) {
                                    if (isInQuickSelectMode) {
                                        toggleChecked(layoutPosition)
                                    } else {
                                        val song = dataSet[layoutPosition]
                                        val index = songsList.indexOf(song)
                                        if (index != -1) {
                                            MusicPlayerRemote.openQueue(songsList, index, true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    SongAdapter(activity, songsToShow.toMutableList(), itemLayoutId)
                }

                if (home.homeSection == TRY_SOMETHING_NEW || home.homeSection == SELECTED_FOR_YOUR_TASTE) {
                    layoutManager = LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
                } else {
                    layoutManager = linearLayoutManager()
                }
                adapter = songAdapter
            }
        }
    }


    open class AbsHomeViewItem(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val recyclerView: RecyclerView = itemView.findViewById(R.id.recyclerView)
        val title: AppCompatTextView = itemView.findViewById(R.id.title)
        val clickableArea: ViewGroup = itemView.findViewById(R.id.clickable_area)
    }

    private fun artistsAdapter(artists: List<Artist>) =
        ArtistAdapter(activity, artists, PreferenceUtil.homeArtistGridStyle, this)

    private fun albumAdapter(albums: List<Album>) =
        AlbumAdapter(activity, albums, PreferenceUtil.homeAlbumGridStyle, this)

    private fun gridLayoutManager() =
        GridLayoutManager(activity, 1, GridLayoutManager.HORIZONTAL, false)

    private fun linearLayoutManager() =
        LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)

    override fun onArtist(artistId: Long, view: View) {
        activity.findNavController(R.id.fragment_container).navigate(
            R.id.artistDetailsFragment,
            bundleOf(EXTRA_ARTIST_ID to artistId),
            null,
            FragmentNavigatorExtras(
                view to artistId.toString()
            )
        )
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        activity.findNavController(R.id.fragment_container).navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            null,
            FragmentNavigatorExtras(
                view to albumId.toString()
            )
        )
    }
}
