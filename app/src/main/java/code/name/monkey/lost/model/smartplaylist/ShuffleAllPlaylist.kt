package code.name.monkey.lost.model.smartplaylist

import code.name.monkey.lost.App
import code.name.monkey.lost.R
import code.name.monkey.lost.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class ShuffleAllPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.action_shuffle_all),
    iconRes = R.drawable.ic_shuffle
) {
    override fun songs(): List<Song> {
        return songRepository.songs()
    }
}