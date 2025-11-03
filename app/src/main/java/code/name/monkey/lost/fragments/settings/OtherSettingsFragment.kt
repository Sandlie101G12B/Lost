package code.name.monkey.lost.fragments.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.lost.LANGUAGE_NAME
import code.name.monkey.lost.LAST_ADDED_CUTOFF
import code.name.monkey.lost.R
import code.name.monkey.lost.db.PlaylistDao
import code.name.monkey.lost.db.PlaylistEntity
import code.name.monkey.lost.db.SongEntity
import code.name.monkey.lost.extensions.installLanguageAndRecreate
import code.name.monkey.lost.fragments.LibraryViewModel
import code.name.monkey.lost.fragments.ReloadType.HomeSections
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.SongRepository
import code.name.monkey.lost.service.SpotifyPlaylistIntergrator
import code.name.monkey.lost.util.DownloadManager
import code.name.monkey.lost.util.DownloadResult
import code.name.monkey.lost.util.DownloadService
import code.name.monkey.lost.util.PreferenceUtil
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import timber.log.Timber

class OtherSettingsFragment : AbsSettingsFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()
    private val playlistDao by inject<PlaylistDao>()
    private val songRepository by inject<SongRepository>()
    private val downloadManager by inject<DownloadManager>()

    override fun invalidateSettings() {
        val languagePreference: ATEListPreference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { _, _ ->
            restartActivity()
            return@setOnPreferenceChangeListener true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceUtil.languageCode =
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "auto" }
        addPreferencesFromResource(R.xml.pref_advanced)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preference: Preference? = findPreference(LAST_ADDED_CUTOFF)
        preference?.setOnPreferenceChangeListener { lastAdded, newValue ->
            setSummary(lastAdded, newValue)
            libraryViewModel.forceReload(HomeSections)
            true
        }
        val languagePreference: Preference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { prefs, newValue ->
            setSummary(prefs, newValue)
            if (newValue as? String == "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                // Install the languages from Play Store first and then set the application locale
                requireActivity().installLanguageAndRecreate(newValue.toString()) {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            newValue as? String
                        )
                    )
                }
            }
            true
        }

        val spotifyLogin: Preference? = findPreference("spotify_login")
        spotifyLogin?.isVisible = false

        syncSpotifyPlaylists()
    }
    private fun sanitize(input: String): String {
        // Increased set of illegal characters
        return input.replace("[\\/:*?\"<>|#%]", "_")
    }
    private fun syncSpotifyPlaylists() {
        lifecycleScope.launch {
            val playlists = SpotifyPlaylistIntergrator.getPlaylists()
            Timber.tag("SpotifyPlaylist").d("Found ${playlists?.size} playlists")

            if (playlists != null) {
                for (spotifyPlaylist in playlists) {
                    try {
                        // Check if a playlist with the same name already exists to avoid duplicates
                        val existingPlaylist = playlistDao.getPlaylistByName(spotifyPlaylist.name)
                        if (existingPlaylist != null) {
                            Timber.tag("SpotifyPlaylist")
                                .d("Playlist ${spotifyPlaylist.name} already exists, skipping")
                            // Optionally, you could update the existing playlist instead of skipping
                            continue
                        }

                        Timber.tag("SpotifyPlaylist").d("Processing playlist: ${spotifyPlaylist.name}")
                        val allLocalSongs = songRepository.songs()
                        val tracks = SpotifyPlaylistIntergrator.getPlaylistTracks(spotifyPlaylist.id)
                        Timber.tag("SpotifyPlaylist").d("Found ${tracks?.size} tracks in ${spotifyPlaylist.name}")

                        val uniqueTracks = tracks?.distinctBy { it.name + it.artists.firstOrNull()?.name } ?: emptyList()

                        val songsFoundLocally = mutableListOf<Song>()
                        val songsToDownload = mutableMapOf<String, Song>() // videoId -> Song

                        // Robust artist splitting
                        val artistDelimiters = Regex("(?i)[,/\\\\&]|\\s(?:ft\\.|feat\\.?|and)\\s")

                        fun normalizeTitle(title: String): String {
                            return title.lowercase()
                                .replace(Regex("[^a-z0-9]+"), "") // remove punctuation/spaces
                                .trim()
                        }

                        fun normalizeArtists(artist: String): Set<String> {
                            return artist
                                .split(artistDelimiters)
                                .map { it.trim().lowercase() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                        }

                        // Pre-process local songs into a more efficient structure for searching.
                        val artistToSongsMap = mutableMapOf<String, MutableList<Pair<Song, String>>>()
                        allLocalSongs.forEach { song ->
                            val normalizedTitle = normalizeTitle(song.title)
                            normalizeArtists(song.artistName).forEach { artist ->
                                artistToSongsMap.getOrPut(artist) { mutableListOf() }.add(song to normalizedTitle)
                            }
                        }

                        uniqueTracks.forEach { track ->
                            val spotifyTitle = track.name
                            val spotifyArtist = track.artists.firstOrNull()?.name

                            var songToAdd: Song? = null

                            if (spotifyArtist != null) {
                                val normalizedSpotifyTitle = normalizeTitle(spotifyTitle)
                                val normalizedSpotifyArtists = normalizeArtists(spotifyArtist)

                                // Efficiently find potential matches from the pre-processed map.
                                val potentialMatches = normalizedSpotifyArtists
                                    .flatMap { artist -> artistToSongsMap[artist] ?: emptyList() }
                                    .distinct()

                                songToAdd = potentialMatches.find { (song, normalizedLocalTitle) ->
                                    normalizedLocalTitle == normalizedSpotifyTitle ||
                                            normalizedLocalTitle.contains(normalizedSpotifyTitle) ||
                                            normalizedSpotifyTitle.contains(normalizedLocalTitle) ||
                                            song.data.contains("${sanitize(spotifyTitle)} - ${sanitize(spotifyArtist)}") ||
                                            song.title.contains("${sanitize(spotifyArtist)} - ${sanitize(spotifyTitle)}")
                                }?.first
                            }

                            if (songToAdd != null) {
                                songsFoundLocally.add(songToAdd)
                                Timber.tag("SpotifyPlaylist").d("Found local song: ${songToAdd.title}")
                            } else {
                                // 3. Handle Download
                                val query = "$spotifyTitle $spotifyArtist"
                                val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                val videoId = searchResult?.items?.firstOrNull()?.id

                                if (videoId != null) {
                                    if (!songsToDownload.containsKey(videoId)) {
                                        val songToDownload = Song(
                                            id = 0,
                                            title = spotifyTitle,
                                            artistName = spotifyArtist ?: "",
                                            albumName = "",
                                            duration = 0,
                                            data = "https://www.youtube.com/watch?v=$videoId",
                                            trackNumber = 0,
                                            year = 0,
                                            dateModified = 0,
                                            albumId = 0,
                                            artistId = 0,
                                            composer = "",
                                            albumArtist = ""
                                        )
                                        songsToDownload[videoId] = songToDownload
                                        Timber.tag("SpotifyPlaylist").d("Song to download: ${songToDownload.title}")
                                    }
                                } else {
                                    Timber.tag("SpotifyPlaylist").w("Could not find video for: $spotifyTitle")
                                }
                            }
                        }
                        Timber.tag("SpotifyPlaylist")
                            .d("${songsFoundLocally.size} songs found locally, ${songsToDownload.size} songs to download")


                        val downloadedSongs = mutableListOf<Song>()
                        if (songsToDownload.isNotEmpty()) {
                            val videoIdsToWaitFor = songsToDownload.keys
                            val downloadedSongUris = mutableMapOf<String, Uri>()

                            // Launch a collector in the background to listen for download completions.
                            // This is started *before* the downloads to prevent a race condition
                            // where downloads could complete before the collector is attached.
                            val collectorJob = lifecycleScope.launch {
                                downloadManager.downloadResult
                                    .filter { result -> result.videoId in videoIdsToWaitFor }
                                    .take(videoIdsToWaitFor.size)
                                    .collect { result ->
                                        when (result) {
                                            is DownloadResult.Success -> {
                                                downloadedSongUris[result.videoId] = result.uri
                                                Timber.tag("SpotifyPlaylist").d("Download complete for ${result.videoId}")
                                            }
                                            is DownloadResult.Failure -> {
                                                Timber.tag("SpotifyPlaylist").e("Download failed for ${result.videoId}: ${result.reason}")
                                            }
                                        }
                                    }
                            }

                            Timber.tag("SpotifyPlaylist").d("Waiting for ${videoIdsToWaitFor.size} downloads to complete")

                            // Now, start all downloads concurrently.
                            songsToDownload.values.forEach { songToDownload ->
                                val intent = Intent(requireContext(), DownloadService::class.java).apply {
                                    action = DownloadService.ACTION_START_DOWNLOAD
                                    putExtra(DownloadService.EXTRA_SONG, songToDownload)
                                }
                                requireContext().startService(intent)
                            }

                            // Wait for the collector job to finish, which happens when .take() is satisfied.
                            collectorJob.join()

                            // Force the library to reload to find the new songs
                            delay(1000) // A small delay to ensure MediaStore is updated
                            val newlyDownloadedSongs = songRepository.songs()

                            // Find the full Song objects for the tracks that were just downloaded
                            downloadedSongUris.keys.forEach { videoId ->
                                val originalSong = songsToDownload[videoId]
                                if (originalSong != null) {
                                    val foundSong = newlyDownloadedSongs.find {
                                        it.title.equals(originalSong.title, ignoreCase = true) &&
                                                it.artistName.equals(originalSong.artistName, ignoreCase = true)
                                    }
                                    if (foundSong != null) {
                                        downloadedSongs.add(foundSong)
                                        Timber.tag("SpotifyPlaylist").d("Found newly downloaded song: ${foundSong.title}")
                                    } else {
                                        Timber.tag("SpotifyPlaylist").w("Could not find ${originalSong.title} in library after download.")
                                    }
                                }
                            }
                        }

                        // Combine local and downloaded songs
                        val allSongsForPlaylist = (songsFoundLocally.distinct() + downloadedSongs).distinctBy { it.id }

                        if (allSongsForPlaylist.isNotEmpty()) {
                            // Create the playlist entity
                            val newPlaylistEntity = PlaylistEntity(playlistName = spotifyPlaylist.name)
                            // FIX: Use the correct function 'createPlaylist' from PlaylistDao
                            val newPlaylistId = playlistDao.createPlaylist(newPlaylistEntity)

                            // Create song entities for the playlist
                            val songEntities = allSongsForPlaylist.map { song ->
                                // FIX: Use the correct parameter 'playlistCreatorId' for SongEntity
                                SongEntity(
                                    playlistCreatorId = newPlaylistId,
                                    id = song.id,
                                    title = song.title,
                                    trackNumber = song.trackNumber,
                                    year = song.year,
                                    duration = song.duration,
                                    data = song.data,
                                    dateModified = song.dateModified,
                                    albumId = song.albumId,
                                    albumName = song.albumName,
                                    artistId = song.artistId,
                                    artistName = song.artistName,
                                    composer = song.composer,
                                    albumArtist = song.albumArtist
                                )
                            }
                            // FIX: Use the correct function 'insertSongsToPlaylist' from PlaylistDao
                            playlistDao.insertSongsToPlaylist(songEntities)
                            Timber.tag("SpotifyPlaylist")
                                .d("Created playlist '${spotifyPlaylist.name}' with ${allSongsForPlaylist.size} songs.")
                        } else {
                            Timber.tag("SpotifyPlaylist")
                                .d("No songs found or downloaded for playlist '${spotifyPlaylist.name}', not creating.")
                        }
                    } catch (e: Exception) {
                        Timber.tag("SpotifyPlaylist").e(e, "Failed to process playlist ${spotifyPlaylist.name}. Skipping.")
                    }
                }
            }
        }
    }
}
