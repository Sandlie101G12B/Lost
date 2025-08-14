package code.name.monkey.lost.model.smartplaylist

import code.name.monkey.lost.App
import code.name.monkey.lost.R
import code.name.monkey.lost.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class TopTracksPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.my_top_tracks),
    iconRes = R.drawable.ic_trending_up
) {
    override fun songs(): List<Song> {
        return topPlayedRepository.topTracks()
    }
}