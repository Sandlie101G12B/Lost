package code.name.monkey.lost.adapter.song

import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import code.name.monkey.lost.EXTRA_ALBUM_ID
import code.name.monkey.lost.FAVOURITES
import code.name.monkey.lost.R
import code.name.monkey.lost.adapter.base.AbsMultiSelectAdapter
import code.name.monkey.lost.adapter.base.MediaEntryViewHolder
import code.name.monkey.lost.glide.LostGlideExtension
import code.name.monkey.lost.glide.LostGlideExtension.asBitmapPalette
import code.name.monkey.lost.glide.LostGlideExtension.songCoverOptions
import code.name.monkey.lost.glide.LostColoredTarget
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.helper.SortOrder
import code.name.monkey.lost.helper.menu.SongMenuHelper
import code.name.monkey.lost.helper.menu.SongsMenuHelper
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.util.MusicUtil
import code.name.monkey.lost.util.PreferenceUtil
import code.name.monkey.lost.util.LostUtil
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider

open class SongAdapter(
    override val activity: FragmentActivity,
    var dataSet: MutableList<Song>,
    protected var itemLayoutRes: Int,
    showSectionName: Boolean = true,
    private val showLikedSongsShortcut: Boolean = false
) : AbsMultiSelectAdapter<SongAdapter.ViewHolder, Song>(
    activity,
    R.menu.menu_media_selection
), PopupTextProvider {

    private var showSectionName = true

    init {
        this.showSectionName = showSectionName
        this.setHasStableIds(true)
    }

    open fun swapDataSet(dataSet: List<Song>) {
        this.dataSet = ArrayList(dataSet)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (showLikedSongsShortcut && position == 0) LIKED_SONGS_ITEM else SONG_ITEM
    }

    override fun getItemId(position: Int): Long {
        if (showLikedSongsShortcut && position == 0) return -1L
        return dataSet[position - if (showLikedSongsShortcut) 1 else 0].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutRes = if (viewType == LIKED_SONGS_ITEM) {
            R.layout.item_liked_songs
        } else {
            itemLayoutRes
        }
        val view =
            try {
                LayoutInflater.from(activity).inflate(layoutRes, parent, false)
            } catch (e: Resources.NotFoundException) {
                LayoutInflater.from(activity).inflate(R.layout.item_list, parent, false)
            }
        return createViewHolder(view)
    }

    protected open fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == SONG_ITEM) {
            val song = dataSet[position - if (showLikedSongsShortcut) 1 else 0]
            val isChecked = isChecked(song)
            holder.itemView.isActivated = isChecked
            holder.menu?.isGone = isChecked
            holder.title?.text = getSongTitle(song)
            holder.text?.text = getSongText(song)
            holder.text2?.text = getSongText2(song)
            loadAlbumCover(song, holder)
            val landscape = LostUtil.isLandscape
            if ((PreferenceUtil.songGridSize > 2 && !landscape) || (PreferenceUtil.songGridSizeLand > 5 && landscape)) {
                holder.menu?.isVisible = false
            }
        }
    }

    private fun setColors(color: MediaNotificationProcessor, holder: ViewHolder) {
        if (holder.paletteColorContainer != null) {
            holder.title?.setTextColor(color.primaryTextColor)
            holder.text?.setTextColor(color.secondaryTextColor)
            holder.paletteColorContainer?.setBackgroundColor(color.backgroundColor)
            holder.menu?.imageTintList = ColorStateList.valueOf(color.primaryTextColor)
        }
        holder.mask?.backgroundTintList = ColorStateList.valueOf(color.primaryTextColor)
    }

    protected open fun loadAlbumCover(song: Song, holder: ViewHolder) {
        if (holder.image == null) {
            return
        }
        Glide.with(activity)
            .asBitmapPalette()
            .songCoverOptions(song)
            .load(LostGlideExtension.getSongModel(song))
            .into(object : LostColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    private fun getSongTitle(song: Song): String {
        return song.title
    }

    private fun getSongText(song: Song): String {
        return song.artistName
    }

    private fun getSongText2(song: Song): String {
        return song.albumName
    }

    override fun getItemCount(): Int {
        return dataSet.size + if (showLikedSongsShortcut) 1 else 0
    }

    override fun getIdentifier(position: Int): Song? {
        if (showLikedSongsShortcut && position == 0) return null
        return dataSet[position - if (showLikedSongsShortcut) 1 else 0]
    }

    override fun getName(model: Song): String {
        return model.title
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<Song>) {
        SongsMenuHelper.handleMenuClick(activity, selection, menuItem.itemId)
    }

    override fun getPopupText(position: Int): String {
        if (showLikedSongsShortcut && position == 0) return ""
        val adjustedPosition = position - if (showLikedSongsShortcut) 1 else 0
        val sectionName: String? = when (PreferenceUtil.songSortOrder) {
            SortOrder.SongSortOrder.SONG_DEFAULT -> return MusicUtil.getSectionName(
                dataSet[adjustedPosition].title,
                true
            )

            SortOrder.SongSortOrder.SONG_A_Z, SortOrder.SongSortOrder.SONG_Z_A -> dataSet[adjustedPosition].title
            SortOrder.SongSortOrder.SONG_ALBUM -> dataSet[adjustedPosition].albumName
            SortOrder.SongSortOrder.SONG_ARTIST -> dataSet[adjustedPosition].artistName
            SortOrder.SongSortOrder.SONG_YEAR -> return MusicUtil.getYearString(dataSet[adjustedPosition].year)
            SortOrder.SongSortOrder.COMPOSER -> dataSet[adjustedPosition].composer
            SortOrder.SongSortOrder.SONG_ALBUM_ARTIST -> dataSet[adjustedPosition].albumArtist
            else -> {
                return ""
            }
        }
        return MusicUtil.getSectionName(sectionName)
    }

    open inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {
        protected open var songMenuRes = SongMenuHelper.MENU_RES
        protected open val song: Song
            get() = dataSet[layoutPosition - if (showLikedSongsShortcut) 1 else 0]

        init {
            if (itemViewType == SONG_ITEM) {
                menu?.setOnClickListener(object : SongMenuHelper.OnClickSongMenu(activity) {
                    override val song: Song
                        get() = this@ViewHolder.song

                    override val menuRes: Int
                        get() = songMenuRes

                    override fun onMenuItemClick(item: MenuItem): Boolean {
                        return onSongMenuItemClick(item) || super.onMenuItemClick(item)
                    }
                })
            }
        }

        protected open fun onSongMenuItemClick(item: MenuItem): Boolean {
            if (image != null && image!!.isVisible) {
                when (item.itemId) {
                    R.id.action_go_to_album -> {
                        activity.findNavController(R.id.fragment_container)
                            .navigate(
                                R.id.albumDetailsFragment,
                                bundleOf(EXTRA_ALBUM_ID to song.albumId)
                            )
                        return true
                    }
                }
            }
            return false
        }

        override fun onClick(v: View?) {
            if (itemViewType == LIKED_SONGS_ITEM) {
                v?.findNavController()
                    ?.navigate(R.id.detailListFragment, bundleOf("type" to FAVOURITES))
                return
            }
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                MusicPlayerRemote.openQueueKeepShuffleMode(dataSet, layoutPosition - if (showLikedSongsShortcut) 1 else 0, true)
            }
        }

        override fun onLongClick(v: View?): Boolean {
            if (itemViewType == LIKED_SONGS_ITEM) return false
            return toggleChecked(layoutPosition)
        }
    }

    companion object {
        val TAG: String = SongAdapter::class.java.simpleName
        const val LIKED_SONGS_ITEM = 0
        const val SONG_ITEM = 1
    }
}
