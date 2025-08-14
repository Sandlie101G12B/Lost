package code.name.monkey.lost.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Genre(
    val id: Long,
    val name: String,
    val songCount: Int
) : Parcelable