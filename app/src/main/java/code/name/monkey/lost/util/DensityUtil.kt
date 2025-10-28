package code.name.monkey.lost.util

import android.content.Context

/**
 * Created by hefuyi on 16/7/30.
 */
object DensityUtil {

    @JvmStatic
    fun dip2px(context: Context, dpVale: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpVale * scale + 0.5f).toInt()
    }
}