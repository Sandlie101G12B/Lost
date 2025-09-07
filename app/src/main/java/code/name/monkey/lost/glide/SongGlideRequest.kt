package code.name.monkey.lost.glide

import android.content.Context
import android.net.Uri
import code.name.monkey.lost.extensions.uri
import code.name.monkey.lost.model.Song

class SongGlideRequest {
    class Builder internal constructor(private val context: Context, private val song: Song) {
        /**
         * Prepares the request to be loaded by Glide.
         * Currently, it returns the song's main URI.
         * This might need to be adjusted if your Song model has a specific field for album art.
         */
        fun build(): Uri {
            // Assuming song.uri is the Uri for the media content that Glide should load.
            // If your Song object has a more specific field for album art (e.g., albumArtUri),
            // you should return that here instead.
            return song.uri
        }

        companion object {
            /**
             * Creates a new Builder instance for the given song and context.
             */
            fun from(context: Context, song: Song): Builder {
                // Using applicationContext to avoid potential memory leaks with Activity/Fragment contexts
                return Builder(context.applicationContext, song)
            }
        }
    }
}
