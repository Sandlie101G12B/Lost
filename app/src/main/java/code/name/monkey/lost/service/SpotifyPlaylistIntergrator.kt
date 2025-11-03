package code.name.monkey.lost.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import code.name.monkey.lost.App
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import timber.log.Timber
import java.io.IOException

// Creates a DataStore instance for Spotify preferences
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spotify_prefs")

object SpotifyPlaylistIntergrator {

    private const val TAG = "SpotifyPlaylist"
    private const val CLIENT_ID = "f21058e0684c4dd89a4b9c7873bc7d9a"
    private const val REDIRECT_URI = "lost://callback/"
    private var accessToken: String? = null // In-memory cache for the token

    // Define a CoroutineScope for managing background tasks
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Key for storing the access token in DataStore
    private val SPOTIFY_ACCESS_TOKEN_KEY = stringPreferencesKey("spotify_access_token")

    // Data classes for Spotify API
    data class SpotifyUser(val id: String, @SerializedName("display_name") val displayName: String)
    data class SpotifyPlaylist(val id: String, val name: String, val images: List<SpotifyImage>)
    data class SpotifySavedTrack(val track: SpotifyTrack)
    data class SpotifyTrack(val id: String, val name: String, val artists: List<SpotifyArtist>, val album: SpotifyAlbum)
    data class SpotifyArtist(val name: String)
    data class SpotifyAlbum(val images: List<SpotifyImage>)
    data class SpotifyImage(val url: String)
    data class PagedResult<T>(val items: List<T>)
    data class SpotifyPlaylistItem(val track: SpotifyTrack)

    // Retrofit service
    private interface SpotifyApiService {
        @GET("v1/me/playlists")
        suspend fun getMyPlaylists(@Header("Authorization") token: String): PagedResult<SpotifyPlaylist>

        @GET("v1/me/tracks")
        suspend fun getMyLikedSongs(@Header("Authorization") token: String): PagedResult<SpotifySavedTrack>

        @GET("v1/playlists/{playlist_id}/tracks")
        suspend fun getPlaylistTracks(@Header("Authorization") token: String, @Path("playlist_id") playlistId: String): PagedResult<SpotifyPlaylistItem>
    }

    private val spotifyApi: SpotifyApiService by lazy {
        val gson = GsonBuilder().create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        retrofit.create(SpotifyApiService::class.java)
    }

    private fun getAppContext(): Context = App.getContext()

    private suspend fun saveToken(token: String) {
        getAppContext().dataStore.edit { preferences ->
            preferences[SPOTIFY_ACCESS_TOKEN_KEY] = token
        }
        accessToken = token // Update in-memory cache
        Timber.tag(TAG).d("Access token saved to persistent storage.")
    }

    private suspend fun clearToken() {
        getAppContext().dataStore.edit { preferences ->
            preferences.remove(SPOTIFY_ACCESS_TOKEN_KEY)
        }
        accessToken = null
        Timber.tag(TAG).w("Access token cleared from persistent storage.")
    }

    private suspend fun getAccessToken(): String? {
        // 1. Check in-memory cache first
        if (accessToken != null) return accessToken

        // 2. If not in memory, load from DataStore
        accessToken = getAppContext().dataStore.data.map { preferences ->
            preferences[SPOTIFY_ACCESS_TOKEN_KEY]
        }.first()
        Timber.tag(TAG).d("Loaded access token from storage.")
        return accessToken
    }

    fun openLogin(activity: Activity) {
        Timber.tag(TAG).d("openLogin called")
        val builder = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
        builder.setScopes(arrayOf("playlist-read-private", "user-library-read"))
        val request = builder.build()
        AuthorizationClient.openLoginActivity(activity, 1337, request)
    }

    fun onAuthorizationComplete(requestCode: Int, resultCode: Int, data: Intent?, onResult: (Boolean) -> Unit) {
        Timber.tag(TAG).d("onAuthorizationComplete called with requestCode: %d", requestCode)
        if (requestCode == 1337) {
            val response = AuthorizationClient.getResponse(resultCode, data)
            Timber.tag(TAG).d("Spotify response type: %s", response.type)
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    val token = "Bearer ${response.accessToken}"
                    Timber.tag(TAG).d("Spotify login successful.")
                    scope.launch {
                        saveToken(token)
                    }
                    onResult(true)
                }
                AuthorizationResponse.Type.ERROR -> {
                    Timber.tag(TAG).e("Spotify login error: %s", response.error)
                    onResult(false)
                }
                else -> {
                    Timber.tag(TAG).w("Spotify login cancelled or unknown result: %s", response.type)
                    onResult(false)
                }
            }
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var currentDelay = 1000L // Start with a 1-second delay
        repeat(3) { attempt ->
            try {
                return block() // If successful, return the result
            } catch (e: Exception) {
                when (e) {
                    is IOException -> {
                        Timber.tag(TAG).w(e, "API call failed (IOException), attempt #${attempt + 1}")
                    }
                    is HttpException -> {
                        Timber.tag(TAG).w(e, "API call failed (HTTP ${e.code()}), attempt #${attempt + 1}")
                        if (e.code() == 401 || e.code() == 403) {
                            Timber.tag(TAG).e("Authentication error. Clearing token.")
                            clearToken()
                            throw e // Do not retry on auth errors, re-throw immediately
                        }
                        if (e.code() !in 500..599) {
                            throw e // Do not retry for other client-side errors
                        }
                    }
                    else -> throw e // Re-throw unexpected exceptions
                }

                if (attempt < 2) { // Don't delay after the last attempt
                    delay(currentDelay)
                    currentDelay *= 2 // Exponential backoff
                }
            }
        }
        return block() // Final attempt
    }

    suspend fun getPlaylists(): List<SpotifyPlaylist>? {
        Timber.tag(TAG).d("getPlaylists called")
        return try {
            withRetry {
                val token = getAccessToken() ?: throw IOException("Not logged in to Spotify")
                val playlists = spotifyApi.getMyPlaylists(token).items.toMutableList()
                val likedSongs = getLikedSongs()
                if (likedSongs != null) {
                    val likedSongsPlaylist = SpotifyPlaylist("liked_songs", "Liked Songs", emptyList())
                    playlists.add(0, likedSongsPlaylist)
                }
                Timber.tag(TAG).d("Successfully fetched %d playlists", playlists.size)
                playlists
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get playlists after retries.")
            null
        }
    }

    suspend fun getLikedSongs(): List<SpotifyTrack>? {
        Timber.tag(TAG).d("getLikedSongs called")
        return try {
            withRetry {
                val token = getAccessToken() ?: throw IOException("Not logged in to Spotify")
                val tracks = spotifyApi.getMyLikedSongs(token).items.map { it.track }
                Timber.tag(TAG).d("Successfully fetched %d liked songs", tracks.size)
                tracks
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get liked songs after retries.")
            null
        }
    }

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack>? {
        Timber.tag(TAG).d("getPlaylistTracks called for playlistId: %s", playlistId)
        return try {
            withRetry {
                val token = getAccessToken() ?: throw IOException("Not logged in to Spotify")
                if (playlistId == "liked_songs") {
                    return@withRetry getLikedSongs()
                }
                val tracks = spotifyApi.getPlaylistTracks(token, playlistId).items.map { it.track }
                Timber.tag(TAG).d("Successfully fetched %d tracks for playlist %s", tracks.size, playlistId)
                tracks
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get tracks for playlist %s after retries.", playlistId)
            null
        }
    }
}
