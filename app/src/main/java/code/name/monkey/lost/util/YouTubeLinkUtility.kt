package code.name.monkey.lost.util

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import androidx.core.content.edit

object YouTubeLinkUtility {

    private const val PREFS_NAME = "YouTubeCache"
    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Load from cache
    private fun getCachedLink(songName: String): String? {
        return prefs.getString(songName.lowercase(), null)
    }

    // Save to cache
    private fun saveLink(songName: String, link: String) {
        prefs.edit { putString(songName.lowercase(), link) }
    }

    // Search YouTube using Data API v3
    private fun searchYouTube(songName: String, apiKey: String): String? {
        val query = URLEncoder.encode(songName, "UTF-8")
        val url =
            "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=$query&key=$apiKey"

        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string()

            val json = JSONObject(body)
            val items = json.getJSONArray("items")
            if (items.length() > 0) {
                val videoId =
                    items.getJSONObject(0).getJSONObject("id").getString("videoId")
                return "https://www.youtube.com/embed/$videoId"
            }
        }
        return null
    }

    // Public function: load (cache → API → store)
    fun getVideoLink(context: Context, songName: String, apiKey: String): String? {
        init(context)

        // 1. Try cache
        val cached = getCachedLink(songName)
        if (cached != null) {
            return cached
        }

        // 2. Fallback to API
        val freshLink = searchYouTube(songName, apiKey)

        // 3. Save for next time
        if (freshLink != null) {
            saveLink(songName, freshLink)
        }

        return freshLink
    }
}


//        USAGE:
//
//        val apiKey = "YOUR_API_KEY"
//
//        // User enters a song name
//        val songName = "Shape of You"
//
//        // Get the link (cache first, API if needed)
//        val link = YouTubeLinkUtility.getVideoLink(this, songName, apiKey)
//
//        if (link != null) {
//            println("Video link: $link")
//            // Example: Load into WebView with start/end times
//            val start = 30
//            val end = 60
//            val loopedUrl = "$link?start=$start&end=$end&loop=1&playlist=${link.substringAfterLast("/")}"
//            myWebView.settings.javaScriptEnabled = true
//            myWebView.loadUrl(loopedUrl)
//        } else {
//            println("No video found for $songName")
//        }
