package code.name.monkey.lost.adapter.album

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import code.name.monkey.lost.glide.LostGlideExtension
import code.name.monkey.lost.glide.LostGlideExtension.albumCoverOptions
import code.name.monkey.lost.glide.LostGlideExtension.asBitmapPalette
import code.name.monkey.lost.glide.LostColoredTarget
import code.name.monkey.lost.helper.HorizontalAdapterHelper
import code.name.monkey.lost.interfaces.IAlbumClickListener
import code.name.monkey.lost.model.Album
import code.name.monkey.lost.util.MusicUtil
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide

class HorizontalAlbumAdapter(
    activity: FragmentActivity,
    dataSet: List<Album>,
    albumClickListener: IAlbumClickListener
) : AlbumAdapter(
    activity, dataSet, HorizontalAdapterHelper.LAYOUT_RES, albumClickListener
) {

    override fun createViewHolder(view: View, viewType: Int): ViewHolder {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        HorizontalAdapterHelper.applyMarginToLayoutParams(activity, params, viewType)
        return ViewHolder(view)
    }

    override fun setColors(color: MediaNotificationProcessor, holder: ViewHolder) {
        // holder.title?.setTextColor(ATHUtil.resolveColor(activity, android.R.attr.textColorPrimary))
        // holder.text?.setTextColor(ATHUtil.resolveColor(activity, android.R.attr.textColorSecondary))
    }

    override fun loadAlbumCover(album: Album, holder: ViewHolder) {
        if (holder.image == null) return
        Glide.with(activity)
            .asBitmapPalette()
            .albumCoverOptions(album.safeGetFirstSong())
            .load(LostGlideExtension.getSongModel(album.safeGetFirstSong()))
            .into(object : LostColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    override fun getAlbumText(album: Album): String {
        return MusicUtil.getYearString(album.year)
    }

    override fun getItemViewType(position: Int): Int {
        return HorizontalAdapterHelper.getItemViewType(position, itemCount)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    companion object {
        val TAG: String = AlbumAdapter::class.java.simpleName
    }
}
