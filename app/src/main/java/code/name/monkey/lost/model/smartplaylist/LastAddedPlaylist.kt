package code.name.monkey.lost.model.smartplaylist

import code.name.monkey.lost.App
import code.name.monkey.lost.R
import code.name.monkey.lost.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class LastAddedPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.last_added),
    iconRes = R.drawable.ic_library_add
) {
    override fun songs(): List<Song> {
        return lastAddedRepository.recentSongs()
    }
}