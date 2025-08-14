package code.name.monkey.lost.model

import androidx.annotation.StringRes
import code.name.monkey.lost.HomeSection

data class Home(
    val arrayList: List<Any>,
    @HomeSection
    val homeSection: Int,
    @StringRes
    val titleRes: Int
)