package code.name.monkey.lost.helper

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import code.name.monkey.lost.model.SongStatistics

object SongStatisticsManager {

    private const val FILE_NAME = "song_statistics.json"
    private var statisticsMap: MutableMap<String, SongStatistics> = mutableMapOf()

    private lateinit var appContext: Context

    fun load(context: Context) {
        appContext = context.applicationContext
        val file = getFile()
        if (file.exists()) {
            val json = file.readText()
            val type = object : TypeToken<MutableMap<String, SongStatistics>>() {}.type
            statisticsMap = Gson().fromJson(json, type) ?: mutableMapOf()
        }
    }

    fun save() {
        val file = getFile()
        val json = Gson().toJson(statisticsMap)
        file.writeText(json)
    }

    private fun getFile(): File {
        check(::appContext.isInitialized) {
            "SongStatisticsManager not initialized. Call load(context) first."
        }
        return File(appContext.filesDir, FILE_NAME)
    }
}
