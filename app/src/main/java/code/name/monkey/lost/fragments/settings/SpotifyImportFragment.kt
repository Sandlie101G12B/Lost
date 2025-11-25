package code.name.monkey.lost.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import code.name.monkey.lost.databinding.FragmentSpotifyImportBinding
import code.name.monkey.lost.db.PlaylistDao
import code.name.monkey.lost.db.PlaylistEntity
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.repository.SongRepository
import code.name.monkey.lost.service.SpotifyPlaylistIntergrator
import code.name.monkey.lost.util.DownloadUtil
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber
import androidx.media3.common.util.UnstableApi

class SpotifyImportFragment : Fragment() {

    private var _binding: FragmentSpotifyImportBinding? = null
    private val binding get() = _binding!!

    private val playlistDao by inject<PlaylistDao>()
    private val songRepository by inject<SongRepository>()
    private val downloadUtil by inject<DownloadUtil>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSpotifyImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }

        startImport()
    }

    private fun appendLog(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val currentText = binding.logText.text.toString()
            val newText = if (currentText.isEmpty()) message else "$currentText\n$message"
            binding.logText.text = newText
            // Auto scroll could be added here if Log text view was a ScrollView or wrapped in one
        }
    }

    private fun setStatus(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.statusText.text = message
        }
    }

    private fun setProgress(progress: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.progressBar.progress = progress
        }
    }

    private fun sanitize(input: String): String = input.replace("[\\\\/:*?\"<>|#%]".toRegex(), "_")

    private fun startImport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                setStatus("Fetching playlists...")
                val playlists = SpotifyPlaylistIntergrator.getPlaylists()
                if (playlists == null) {
                    setStatus("Failed to fetch playlists or no playlists found.")
                    return@launch
                }
                Timber.tag("SpotifyImport").d("Found ${playlists.size} playlists")
                appendLog("Found ${playlists.size} playlists")

                val totalPlaylists = playlists.size
                var processedPlaylists = 0

                playlists.forEachIndexed { index, spotifyPlaylist ->
                    setStatus("Processing playlist: ${spotifyPlaylist.name} (${index + 1}/$totalPlaylists)")
                    appendLog("Processing '${spotifyPlaylist.name}'...")

                    try {
                        if (playlistDao.getPlaylistByName(spotifyPlaylist.name) != null) {
                            appendLog("Playlist '${spotifyPlaylist.name}' already exists. Skipping.")
                            processedPlaylists++
                            setProgress((processedPlaylists * 100) / totalPlaylists)
                            return@forEachIndexed
                        }

                        val allLocalSongs = songRepository.songs()
                        val tracks = SpotifyPlaylistIntergrator.getPlaylistTracks(spotifyPlaylist.id) ?: emptyList()
                        val localMap = allLocalSongs.associateBy { sanitize("${it.title}-${it.artistName}").lowercase() }

                        val songsToAddLocally = mutableListOf<Song>()
                        val videosToDownload = mutableListOf<String>()
                        val seenKeys = mutableSetOf<String>()

                        appendLog("Found ${tracks.size} tracks in '${spotifyPlaylist.name}'")

                        tracks.forEach { track ->
                            val key = sanitize("${track.name}-${track.artists.firstOrNull()?.name}").lowercase()

                            if (!seenKeys.contains(key)) {
                                seenKeys.add(key)

                                val localSong = localMap[key]
                                if (localSong != null) {
                                    songsToAddLocally.add(localSong)
                                } else {
                                    val query = "${track.name} ${track.artists.firstOrNull()?.name}"
                                    val videoId = YouTube
                                        .search(query, YouTube.SearchFilter.FILTER_SONG)
                                        .getOrNull()?.items?.firstOrNull()?.id

                                    if (videoId != null) {
                                        videosToDownload.add(videoId)
                                    }
                                }
                            }
                        }

                        appendLog("Local songs: ${songsToAddLocally.size}, To download: ${videosToDownload.size}")

                        if (songsToAddLocally.isNotEmpty() || videosToDownload.isNotEmpty()) {
                            val playlistEntity = PlaylistEntity(playlistName = spotifyPlaylist.name)
                            val playlistId = playlistDao.createPlaylist(playlistEntity)

                            songsToAddLocally.forEach {
                                try{
                                    @UnstableApi
                                    downloadUtil.addLocalSongToPlaylist(it, playlistId)
                                }catch(e: Exception){
                                    Timber.tag("SpotifyImport").e(e, "Failed to add local song to playlist")
                                }
                            }

                            // Batch downloading: 2 songs, then 5 seconds delay
                            val batchSize = 2
                            val chunks = videosToDownload.chunked(batchSize)
                            val totalChunks = chunks.size
                            
                            chunks.forEachIndexed { chunkIndex, batch ->
                                batch.forEach { videoId ->
                                    try{
                                        @UnstableApi
                                        downloadUtil.addDownload(videoId, "https://www.youtube.com/watch?v=$videoId".toUri(), playlistId)
                                    }catch (e:Exception){
                                        Timber.tag("SpotifyImport").e(e, "Failed to add download to playlist")
                                    }
                                }
                                appendLog("Queued batch ${chunkIndex + 1}/$totalChunks for '${spotifyPlaylist.name}'")
                                
                                // Delay after each batch (except the last one if you want, but keeping it consistent is fine)
                                if (chunkIndex < totalChunks - 1) {
                                    delay(5000)
                                }
                            }

                            appendLog("Created playlist '${spotifyPlaylist.name}'")
                        } else {
                            appendLog("No songs found to add for '${spotifyPlaylist.name}'")
                        }

                    } catch (e: Exception) {
                        Timber.tag("SpotifyImport").e(e, "Failed to process playlist ${spotifyPlaylist.name}")
                        appendLog("Error processing '${spotifyPlaylist.name}': ${e.message}")
                    }

                    processedPlaylists++
                    setProgress((processedPlaylists * 100) / totalPlaylists)
                }

                setStatus("Import Complete!")
                appendLog("All done.")
                withContext(Dispatchers.Main) {
                    binding.btnCancel.text = "Close"
                }

            } catch (e: Exception) {
                Timber.tag("SpotifyImport").e(e, "Import failed")
                setStatus("Import failed: ${e.message}")
                appendLog("Critical error: ${e.message}")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
