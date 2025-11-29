package code.name.monkey.lost.fragments.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.lost.LANGUAGE_NAME
import code.name.monkey.lost.LAST_ADDED_CUTOFF
import code.name.monkey.lost.R
import code.name.monkey.lost.activities.MainActivity
import code.name.monkey.lost.db.PlaylistDao
import code.name.monkey.lost.db.PlaylistEntity
import code.name.monkey.lost.extensions.installLanguageAndRecreate
import code.name.monkey.lost.fragments.LibraryViewModel
import code.name.monkey.lost.fragments.ReloadType.HomeSections
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.SongRepository
import code.name.monkey.lost.service.SpotifyPlaylistIntergrator
import code.name.monkey.lost.util.DownloadUtil
import code.name.monkey.lost.util.PreferenceUtil
import code.name.monkey.lost.util.YTPlayerUtils
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import timber.log.Timber

@UnstableApi
var downloadUtil: DownloadUtil? = null

class OtherSettingsFragment : AbsSettingsFragment(), MainActivity.OnSpotifyLoginCompleteListener {

    private val libraryViewModel by activityViewModel<LibraryViewModel>()
    private val koinDownloadUtil by inject<DownloadUtil>() // Koin singleton instance
    private val playlistDao by inject<PlaylistDao>()
    private val songRepository by inject<SongRepository>()

    companion object {
        private const val TAG = "SpotifyPlaylist"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize global singleton only once
        if (downloadUtil == null) {
            downloadUtil = koinDownloadUtil
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        Timber.tag(TAG).d("onAttach called")
        if (context is MainActivity) {
            Timber.tag(TAG).d("Setting OnSpotifyLoginCompleteListener")
            context.setOnSpotifyLoginCompleteListener(this)
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

        findPreference<Preference>("spotify_login")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                Timber.tag(TAG).d("spotify_login clicked")
                (requireActivity() as MainActivity).openSpotifyLogin()
                true
            }
        }

        findPreference<Preference>("spotify_import")?.setOnPreferenceClickListener {
            Timber.tag(TAG).d("spotify_import clicked")
            syncSpotifyPlaylists()
            true
        }
    }

    override fun onSpotifyLoginComplete() {
        Timber.tag(TAG).d("onSpotifyLoginComplete called, starting sync")
        syncSpotifyPlaylists()
    }

    private fun sanitize(input: String): String = input //input.replace("[\\\\/:*?\"<>|#%]".toRegex(), "_")

    private fun syncSpotifyPlaylists() {
        val context = context ?: return
        var shouldUpdateUI = true

        val scrollView = android.widget.ScrollView(context)
        val logTextView = android.widget.TextView(context)
        val padding = (16 * resources.displayMetrics.density).toInt()
        logTextView.setPadding(padding, padding, padding, padding)
        logTextView.text = getString(R.string.spotify_preparing)
        scrollView.addView(logTextView)

        val progressDialog = AlertDialog.Builder(context)
            .setTitle(R.string.spotify_importing)
            .setView(scrollView)
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                shouldUpdateUI = false
                dialog.dismiss()
            }
            .create()
        
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val playlists = SpotifyPlaylistIntergrator.getPlaylists()
                if (playlists == null) {
                    withContext(Dispatchers.Main) {
                        if (shouldUpdateUI) {
                            logTextView.append("\nFailed to fetch playlists")
                            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                            delay(2000)
                            progressDialog.dismiss()
                        }
                    }
                    return@launch
                }

                Timber.tag(TAG).d("Found ${playlists.size} playlists")

                val total = playlists.size
                playlists.forEachIndexed { index, spotifyPlaylist ->
                    withContext(Dispatchers.Main) {
                        if (shouldUpdateUI) {
                            val message = "Processing ${spotifyPlaylist.name} (${index + 1}/$total)"
                            logTextView.append(message)
                            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                        }
                    }

                    try {
                        if (playlistDao.getPlaylistByName(spotifyPlaylist.name) != null) return@forEachIndexed

                        val allLocalSongs = songRepository.songs()
                        val tracks = SpotifyPlaylistIntergrator.getPlaylistTracks(spotifyPlaylist.id) ?: emptyList()
                        
                        val localSongsByTitle = allLocalSongs.groupBy { sanitize(it.title).lowercase() }
                        
                        val songsToAddLocally = mutableListOf<Song>()
                        val videosToDownload = mutableListOf<String>()
                        val seenKeys = mutableSetOf<String>()

                        tracks.forEach { track ->
                            val trackName = sanitize(track.name).lowercase()
                            val spotifyArtists = track.artists.map { sanitize(it.name).lowercase() }
                            val key = "$trackName-${spotifyArtists.sorted().joinToString(",")}"
                            
                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key)
                                
                                val localSong = localSongsByTitle[trackName]?.find { song ->
                                    val localArtists = song.artistNames.map { sanitize(it).lowercase() }
                                    localArtists.any { localArtist ->
                                        spotifyArtists.any { spotifyArtist ->
                                            localArtist == spotifyArtist || localArtist.contains(spotifyArtist, ignoreCase = true) || spotifyArtist.contains(localArtist, ignoreCase = true)
                                        }
                                    }
                                }
                                
                                if (localSong != null) {
                                    songsToAddLocally.add(localSong)
                                } else {
                                    val query = "${track.name} ${track.artists.firstOrNull()?.name}"
                                    withContext(Dispatchers.Main) {
                                        if (shouldUpdateUI) {
                                            logTextView.append("\n-- Querying for $query")
                                            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                                        }
                                    }
                                    val videoId = YouTube
                                        .search(query, YouTube.SearchFilter.FILTER_SONG)
                                        .getOrNull()?.items?.firstOrNull()?.id
                                    
                                    if (videoId != null) {
                                        videosToDownload.add(videoId)
                                        withContext(Dispatchers.Main) {
                                            if (shouldUpdateUI) {
                                                logTextView.append("\n-- Song found: $videoId\n")
                                                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                                            }
                                        }
                                    }else{
                                        withContext(Dispatchers.Main) {
                                            if (shouldUpdateUI) {
                                                logTextView.append("\n-- Song not found\n")
                                                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (songsToAddLocally.isNotEmpty() || videosToDownload.isNotEmpty()) {
                            val playlistEntity = PlaylistEntity(playlistName = spotifyPlaylist.name)
                            val playlistId = playlistDao.createPlaylist(playlistEntity)
                            
                            songsToAddLocally.forEach {
                                @UnstableApi
                                downloadUtil?.addLocalSongToPlaylist(it, playlistId)
                                Timber.tag(TAG).d("Adding ${it.title} to playlist")
                            }

                            withContext(Dispatchers.Main) {
                                if (shouldUpdateUI) {
                                    logTextView.append("\n# Added local songs to playlist")
                                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                                }
                            }
                            
                            // Batch downloading: 2 songs per batch, then 5 seconds delay
                            val chunks = videosToDownload.chunked(2)
                            chunks.forEachIndexed { chunkIndex, batch ->
                                batch.forEach { videoId ->
                                    @UnstableApi
                                    YTPlayerUtils.initiateVideoDownload(videoId, playlistId)
                                }
                                // Delay after each batch, but not after the last one
                                if (chunkIndex < chunks.size - 1) {
                                    delay(2000)
                                }
                            }
                        }

                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to process playlist ${spotifyPlaylist.name}")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (shouldUpdateUI) {
                        logTextView.append("\n\n\nImport Complete!")
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                        delay(1000)
                        progressDialog.dismiss()
                    }
                }

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Sync failed")
                withContext(Dispatchers.Main) {
                    if (shouldUpdateUI) {
                        logTextView.append("\n\n\nError: ${e.message}")
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                        delay(2000)
                        progressDialog.dismiss()
                    }
                }
            }
        }
    }
}
