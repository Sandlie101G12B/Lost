package code.name.monkey.lost.glide

import android.graphics.drawable.Drawable
import android.widget.ImageView
import code.name.monkey.lost.App
import code.name.monkey.lost.glide.palette.BitmapPaletteTarget
import code.name.monkey.lost.glide.palette.BitmapPaletteWrapper
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.bumptech.glide.request.transition.Transition

abstract class LostColoredTarget(view: ImageView) : BitmapPaletteTarget(view) {

    abstract fun onColorReady(colors: MediaNotificationProcessor)

    override fun onLoadFailed(errorDrawable: Drawable?) {
        super.onLoadFailed(errorDrawable)
        onColorReady(MediaNotificationProcessor.errorColor(App.getContext()))
    }

    override fun onResourceReady(
        resource: BitmapPaletteWrapper,
        transition: Transition<in BitmapPaletteWrapper>?
    ) {
        super.onResourceReady(resource, transition)
        MediaNotificationProcessor(App.getContext()).getPaletteAsync({
            onColorReady(it)
        }, resource.bitmap)
    }
}
