package code.name.monkey.lost

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.edit
import androidx.preference.PreferenceManager
import cat.ereza.customactivityoncrash.config.CaocConfig
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.lost.activities.ErrorActivity
import code.name.monkey.lost.activities.MainActivity
import code.name.monkey.lost.appshortcuts.DynamicShortcutManager
import code.name.monkey.lost.contants.ContentCountryKey
import code.name.monkey.lost.contants.ContentLanguageKey
import code.name.monkey.lost.contants.CountryCodeToName
import code.name.monkey.lost.contants.DataSyncIdKey
import code.name.monkey.lost.contants.InnerTubeCookieKey
import code.name.monkey.lost.contants.LanguageCodeToName
import code.name.monkey.lost.contants.ProxyEnabledKey
import code.name.monkey.lost.contants.ProxyPasswordKey
import code.name.monkey.lost.contants.ProxyTypeKey
import code.name.monkey.lost.contants.ProxyUrlKey
import code.name.monkey.lost.contants.ProxyUsernameKey
import code.name.monkey.lost.contants.SYSTEM_DEFAULT
import code.name.monkey.lost.contants.UseLoginForBrowse
import code.name.monkey.lost.contants.VisitorDataKey
import code.name.monkey.lost.extensions.toInetSocketAddress
import code.name.monkey.lost.helper.WallpaperAccentManager
import code.name.monkey.lost.util.DownloadUtil
import code.name.monkey.lost.util.YTPlayerUtils
import code.name.monkey.lost.util.dataStore
import code.name.monkey.lost.util.get
import code.name.monkey.lost.util.toEnum
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger // Good for debugging
import org.koin.core.context.startKoin
import org.koin.core.logger.Level // For Koin logger level
import timber.log.Timber
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.Locale
import kotlin.system.exitProcess

class App : Application() {

    //lateinit var billingManager: BillingManager
    private val wallpaperAccentManager = WallpaperAccentManager(this)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) {
            val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra("autoplay", true)
                startActivity(intent)
                exitProcess(0)
            }
        }

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Timber.plant(Timber.DebugTree())
        instance = this
        YTPlayerUtils.giveContext(this)
        val locale = Locale.getDefault()
        val languageTag =
            locale.toLanguageTag().replace("-Hant", "") // replace zh-Hant-* to zh-*

        YouTube.locale = YouTubeLocale(
            gl = dataStore[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
                ?: locale.country.takeIf { it in CountryCodeToName }
                ?: "US",
            hl = dataStore[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
                ?: locale.language.takeIf { it in LanguageCodeToName }
                ?: languageTag.takeIf { it in LanguageCodeToName }
                ?: "en"
        )

        if (dataStore[ProxyEnabledKey] == true) {
            val username = dataStore[ProxyUsernameKey].orEmpty()
            val password = dataStore[ProxyPasswordKey].orEmpty()
            val type = dataStore[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP)

            if (username.isNotEmpty() || password.isNotEmpty()) {
                if (type == Proxy.Type.HTTP) {
                    YouTube.proxyAuth = Credentials.basic(username, password)
                } else {
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication() =
                            PasswordAuthentication(username, password.toCharArray())
                    })
                }
            }
            try {
                YouTube.proxy = Proxy(type, dataStore[ProxyUrlKey]!!.toInetSocketAddress())
            } catch (_: Exception) {
                Toast.makeText(this@App, "Failed to parse proxy url.", LENGTH_SHORT).show()
            }
        }

        if (dataStore[UseLoginForBrowse] != false) {
            YouTube.useLoginForBrowse = true
        }

        applicationScope.launch(IO) {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    YouTube.visitorData = visitorData
                        ?.takeIf { it != "null" } // Previously visitorData was sometimes saved as "null" due to a bug
                        ?: YouTube.visitorData().onFailure {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@App, "Failed to get visitorData.", LENGTH_SHORT).show()
                            }
                        }.getOrNull()?.also { newVisitorData ->
                            dataStore.edit { settings ->
                                settings[VisitorDataKey] = newVisitorData
                            }
                        }
                }
        }

        applicationScope.launch(IO) {
            dataStore.data
                .map { it[DataSyncIdKey] }
                .distinctUntilChanged()
                .collect { dataSyncId ->
                    YouTube.dataSyncId = dataSyncId?.let {
                        it.takeIf { !it.contains("||") }
                            ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                            ?: it.substringAfter("||")
                    }
                }
        }

        applicationScope.launch(IO) {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    try {
                        YouTube.cookie = cookie
                    } catch (e: Exception) {
                        // we now allow user input now, here be the demons. This serves as a last ditch effort to avoid a crash loop
                        Timber.e("Could not parse cookie. Clearing existing cookie. %s", e.message)
                        applicationContext.dataStore.edit { settings ->  // Assuming forgetAccount used to do this with this@App
                            settings.remove(InnerTubeCookieKey)
                            settings.remove(DataSyncIdKey)
                            settings.remove(VisitorDataKey)
                        }
                    }
                }
        }

        startKoin {
            androidLogger(Level.INFO) // Added Koin logger for easier debugging (use Level.ERROR or Level.NONE in release)
            androidContext(this@App)
            modules(appModules) // CORRECTED to use singular 'appModule'
        }

        // Create notification channels
//        DownloadUtil.createNotificationChannel(this, "downloads_manager_channel")

        // default theme
        if (!ThemeStore.isConfigured(this, 3)) {
            ThemeStore.editTheme(this)
                .accentColorRes(code.name.monkey.appthemehelper.R.color.md_deep_purple_A400)
                .coloredNavigationBar(true)
                .commit()
        }
        wallpaperAccentManager.init()

        if (VersionUtils.hasNougatMR())
            DynamicShortcutManager(this).initDynamicShortcuts()

        //billingManager = BillingManager(this)

        // setting Error activity
        CaocConfig.Builder.create().errorActivity(ErrorActivity::class.java)
            .restartActivity(MainActivity::class.java as Class<out Activity>).apply()

        // Set Default values for now playing preferences
        // This will reduce startup time for now playing settings fragment as Preference listener of AbsSlidingMusicPanelActivity won\'t be called
        PreferenceManager.setDefaultValues(this, R.xml.pref_now_playing_screen, false)
    }

    override fun onTerminate() {
        super.onTerminate()
        //billingManager.release()
        wallpaperAccentManager.release()
    }

    companion object {
        private var instance: App? = null

        fun getContext(): App {
            return instance!!
        }

        fun isProVersion(): Boolean {
            return true //BuildConfig.DEBUG || instance?.billingManager!!.isProVersion
        }
    }
}
