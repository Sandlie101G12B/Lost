package code.name.monkey.lost.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.lost.R
import code.name.monkey.lost.databinding.ItemGenreBinding
import code.name.monkey.lost.glide.LostGlideExtension
import code.name.monkey.lost.glide.LostGlideExtension.asBitmapPalette
import code.name.monkey.lost.glide.LostGlideExtension.songCoverOptions
import code.name.monkey.lost.glide.LostColoredTarget
import code.name.monkey.lost.interfaces.IGenreClickListener
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.util.MusicUtil
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*

/**
 * @author Hemanth S (h4h13).
 */

class GenreAdapter(
    private val activity: FragmentActivity,
    var dataSet: List<Genre>,
    private val listener: IGenreClickListener
) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

    init {
        this.setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        // Ensure position is valid to prevent IndexOutOfBoundsException
        return if (position >= 0 && position < dataSet.size) {
            dataSet[position].id
        } else {
            RecyclerView.NO_ID
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGenreBinding.inflate(LayoutInflater.from(activity), parent, false)
        return ViewHolder(binding, listener)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val genre = dataSet[position]
        holder.bind(genre)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelJob() // Cancel the coroutine job when ViewHolder is recycled
        // It's also a good practice to clear Glide loads here if not handled by Glide itself
        Glide.with(activity).clear(holder.binding.image)
    }

    private suspend fun loadGenreImage(genre: Genre, holder: GenreAdapter.ViewHolder) {
        // It's good practice for MusicUtil.songByGenre to be a suspend function
        // or to run on a background thread if it involves I/O.
        // For now, assuming it's quick or already optimized.
        val genreSong = MusicUtil.songByGenre(genre.id)
        Glide.with(activity)
            .asBitmapPalette()
            .songCoverOptions(genreSong)
            .load(LostGlideExtension.getSongModel(genreSong))
            .into(object : LostColoredTarget(holder.binding.image) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    // Ensure holder is still bound to the correct item
                    // This check is implicitly handled by Glide's target lifecycle
                    // and coroutine cancellation if the view is recycled.
                    holder.setColors(colors)
                }
            })
        // Just for a bit of shadow around image
        holder.binding.image.outlineProvider = ViewOutlineProvider.BOUNDS
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(list: List<Genre>) {
        dataSet = list
        notifyDataSetChanged() // Consider using DiffUtil for better performance
    }

    inner class ViewHolder(
        val binding: ItemGenreBinding,
        private val clickListener: IGenreClickListener // Pass listener here
        ) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

        private var job: Job? = null
        // Scope for launching coroutines, tied to Dispatchers.Main for UI updates
        // Glide handles its own background threading for image loading.
        private val coroutineScope = CoroutineScope(Dispatchers.Main)

        init {
            itemView.setOnClickListener(this)
        }

        fun bind(genre: Genre) {
            binding.title.text = genre.name
            binding.text.text = String.format(
                Locale.getDefault(),
                "%d %s",
                genre.songCount,
                if (genre.songCount > 1) itemView.context.getString(R.string.songs) else itemView.context.getString(R.string.song)
            )
            // Cancel any previous job before starting a new one
            job?.cancel()
            job = coroutineScope.launch {
                loadGenreImage(genre, this@ViewHolder)
            }
        }

        fun setColors(color: MediaNotificationProcessor) {
            binding.imageContainerCard.setCardBackgroundColor(color.backgroundColor)
            binding.title.setTextColor(color.primaryTextColor)
            binding.text.setTextColor(color.secondaryTextColor)
        }

        fun cancelJob() {
            job?.cancel()
            job = null
        }

        override fun onClick(v: View?) {
            // Use adapterPosition to safely get the item's position
            val position = adapterPosition
            if (position != RecyclerView.NO_POSITION) {
                clickListener.onClickGenre(dataSet[position], itemView)
            }
        }
    }
}
