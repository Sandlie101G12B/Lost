package code.name.monkey.lost.glide

import android.content.Context
import android.graphics.Bitmap
import code.name.monkey.lost.glide.artistimage.ArtistImage
import code.name.monkey.lost.glide.artistimage.Factory
import code.name.monkey.lost.glide.audiocover.AudioFileCover
import code.name.monkey.lost.glide.audiocover.AudioFileCoverLoader
import code.name.monkey.lost.glide.palette.BitmapPaletteTranscoder
import code.name.monkey.lost.glide.palette.BitmapPaletteWrapper
import code.name.monkey.lost.glide.playlistPreview.PlaylistPreview
import code.name.monkey.lost.glide.playlistPreview.PlaylistPreviewLoader
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import java.io.InputStream

@GlideModule
class LostGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.prepend(
            PlaylistPreview::class.java,
            Bitmap::class.java,
            PlaylistPreviewLoader.Factory(context)
        )
        registry.prepend(
            AudioFileCover::class.java,
            InputStream::class.java,
            AudioFileCoverLoader.Factory()
        )
        registry.prepend(ArtistImage::class.java, InputStream::class.java, Factory(context))
        registry.register(
            Bitmap::class.java,
            BitmapPaletteWrapper::class.java, BitmapPaletteTranscoder()
        )
    }

    override fun isManifestParsingEnabled(): Boolean {
        return false
    }
}