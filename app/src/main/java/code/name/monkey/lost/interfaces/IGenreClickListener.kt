package code.name.monkey.lost.interfaces

import android.view.View
import code.name.monkey.lost.model.Genre

interface IGenreClickListener {
    fun onClickGenre(genre: Genre, view: View)
}