package code.name.monkey.lost.fragments.settings

import android.os.Bundle
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import code.name.monkey.lost.fragments.ReloadType.HomeSections
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.lost.LANGUAGE_NAME
import code.name.monkey.lost.LAST_ADDED_CUTOFF
import code.name.monkey.lost.R
import code.name.monkey.lost.db.PlaylistDao
import code.name.monkey.lost.db.PlaylistEntity
import code.name.monkey.lost.extensions.installLanguageAndRecreate
import code.name.monkey.lost.fragments.LibraryViewModel
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.SongRepository
import code.name.monkey.lost.service.SpotifyPlaylistIntergrator
import code.name.monkey.lost.util.DownloadUtil
import code.name.monkey.lost.util.PreferenceUtil
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import timber.log.Timber

// At the top level of a file (outside the class)
@UnstableApi
var downloadUtil: DownloadUtil? = null

class OtherSettingsFragment : AbsSettingsFragment() {

    private val libraryViewModel by activityViewModel<LibraryViewModel>()
    private val playlistDao by inject<PlaylistDao>()
    private val songRepository by inject<SongRepository>()
    private val koinDownloadUtil by inject<DownloadUtil>() // Koin singleton instance
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize global singleton only once
        if (downloadUtil == null) {
            downloadUtil = koinDownloadUtil
        }
    }

    override fun invalidateSettings() {
        val languagePreference: ATEListPreference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { _, _ ->
            restartActivity()
            true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceUtil.languageCode =
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "auto" }
        addPreferencesFromResource(R.xml.pref_advanced)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findPreference<Preference>(LAST_ADDED_CUTOFF)?.setOnPreferenceChangeListener { lastAdded, newValue ->
            setSummary(lastAdded, newValue)
            libraryViewModel.forceReload(HomeSections)
            true
        }

        findPreference<Preference>(LANGUAGE_NAME)?.setOnPreferenceChangeListener { prefs, newValue ->
            setSummary(prefs, newValue)
            if (newValue as? String == "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                requireActivity().installLanguageAndRecreate(newValue.toString()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newValue as? String))
                }
            }
            true
        }

        findPreference<Preference>("spotify_login")?.isVisible = true

        syncSpotifyPlaylists()
    }

    private fun sanitize(input: String): String = input.replace("[\\\\/:*?\"<>|#%]".toRegex(), "_")

    @OptIn(UnstableApi::class)
    private fun syncSpotifyPlaylists() {
        lifecycleScope.launch {
            val playlists = SpotifyPlaylistIntergrator.getPlaylists() ?: return@launch
            Timber.tag("SpotifyPlaylist").d("Found ${playlists.size} playlists")

            playlists.forEach { spotifyPlaylist ->
                try {
                    // Skip if playlist already exists
                    if (playlistDao.getPlaylistByName(spotifyPlaylist.name) != null) return@forEach

                    val allLocalSongs = songRepository.songs()
                    val tracks = SpotifyPlaylistIntergrator.getPlaylistTracks(spotifyPlaylist.id) ?: emptyList()
                    val localMap = allLocalSongs.associateBy { sanitize("${it.title}-${it.artistName}").lowercase() }
                    
                    val songsToAddLocally = mutableListOf<Song>()
                    val videosToDownload = mutableListOf<String>()
                    val seenKeys = mutableSetOf<String>()

                    tracks.forEach { track ->
                        val key = sanitize("${track.name}-${track.artists.firstOrNull()?.name}").lowercase()
                        
                        if (seenKeys.contains(key)) return@forEach
                        seenKeys.add(key)
                        
                        val localSong = localMap[key]
                        if (localSong != null) {
                            songsToAddLocally.add(localSong)
                        } else {
                            // Lookup on YouTube
                            val query = "${track.name} ${track.artists.firstOrNull()?.name}"
                            val videoId = YouTube
                                .search(query, YouTube.SearchFilter.FILTER_SONG)
                                .getOrNull()?.items?.firstOrNull()?.id
                            
                            if (videoId != null) {
                                videosToDownload.add(videoId)
                            }
                        }
                    }

                    Timber.tag("SpotifyPlaylist")
                        .d("${songsToAddLocally.size} found locally, ${videosToDownload.size} to download")

                    if (songsToAddLocally.isNotEmpty() || videosToDownload.isNotEmpty()) {
                        val playlistEntity = PlaylistEntity(playlistName = spotifyPlaylist.name)
                        val playlistId = playlistDao.createPlaylist(playlistEntity)
                        
                        songsToAddLocally.forEach { 
                            downloadUtil?.addLocalSongToPlaylist(it, playlistId) 
                        }
                        
                        videosToDownload.forEach { videoId ->
                            downloadUtil?.addDownload(videoId, "https://www.youtube.com/watch?v=$videoId".toUri(), playlistId)
                        }
                        
                        Timber.tag("SpotifyPlaylist").d("Created playlist '${spotifyPlaylist.name}' with ID $playlistId")
                    }

                } catch (e: Exception) {
                    Timber.tag("SpotifyPlaylist").e(e, "Failed to process playlist ${spotifyPlaylist.name}")
                }
            }
        }
    }
}
