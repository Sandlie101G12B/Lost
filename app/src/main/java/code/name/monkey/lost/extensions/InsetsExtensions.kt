package code.name.monkey.lost.extensions

import androidx.core.view.WindowInsetsCompat
import code.name.monkey.lost.util.PreferenceUtil
import code.name.monkey.lost.util.LostUtil

fun WindowInsetsCompat?.getBottomInsets(): Int {
    return if (PreferenceUtil.isFullScreenMode) {
        return 0
    } else {
        this?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: LostUtil.navigationBarHeight
    }
}
