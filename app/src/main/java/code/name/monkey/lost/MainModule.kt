package code.name.monkey.lost

import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import code.name.monkey.lost.auto.AutoMusicProvider
import code.name.monkey.lost.cast.LostWebServer
import code.name.monkey.lost.contants.MaxSongCacheSizeKey
import code.name.monkey.lost.db.MIGRATION_24_25
import code.name.monkey.lost.db.MIGRATION_25_26
import code.name.monkey.lost.db.MIGRATION_26_27
import code.name.monkey.lost.db.LostDatabase
import code.name.monkey.lost.db.MIGRATION_27_28
import code.name.monkey.lost.db.MIGRATION_28_29
import code.name.monkey.lost.db.MIGRATION_29_30
import code.name.monkey.lost.db.MIGRATION_30_31
import code.name.monkey.lost.fragments.LibraryViewModel
import code.name.monkey.lost.fragments.albums.AlbumDetailsViewModel
import code.name.monkey.lost.fragments.artists.ArtistDetailsViewModel
import code.name.monkey.lost.fragments.genres.GenreDetailsViewModel
import code.name.monkey.lost.fragments.playlists.PlaylistDetailsViewModel
import code.name.monkey.lost.helper.MetaData
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.network.provideDefaultCache
import code.name.monkey.lost.network.provideLastFmRest
import code.name.monkey.lost.network.provideLastFmRetrofit
import code.name.monkey.lost.network.provideOkHttp
import code.name.monkey.lost.repository.*
import code.name.monkey.lost.util.DownloadUtil
import code.name.monkey.lost.util.dataStore
import code.name.monkey.lost.util.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val networkModule = module {

    factory {
        provideDefaultCache()
    }
    factory {
        provideOkHttp(get(), get())
    }
    single {
        provideLastFmRetrofit(get())
    }
    single {
        provideLastFmRest(get())
    }
}

private val roomModule = module {

    single {
        Room.databaseBuilder(androidContext(), LostDatabase::class.java, "playlist.db")
            .addMigrations(MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31)
            .build()
    }

    factory {
        get<LostDatabase>().playlistDao()
    }

    factory {
        get<LostDatabase>().playCountDao()
    }

    factory {
        get<LostDatabase>().historyDao()
    }

    factory {
        get<LostDatabase>().similarSongDao()
    }
    
    factory {
        get<LostDatabase>().songsDao()
    }

    single {
        RealRoomRepository(get(), get(), get(), get(), get())
    } bind RoomRepository::class
}

private val autoModule = module {
    single {
        AutoMusicProvider(
            androidContext(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}

private val mainModule = module {
    single {
        androidContext().contentResolver
    }
    single {
        LostWebServer(get())
    }
}

private val dataModule = module {
    single {
        RealRepository(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    } bind Repository::class

    single {
        RealSongRepository(get())
    } bind SongRepository::class

    single {
        RealGenreRepository(get(), get())
    } bind GenreRepository::class

    single {
        RealAlbumRepository(get())
    } bind AlbumRepository::class

    single {
        RealArtistRepository(get())
    } bind ArtistRepository::class

    single {
        RealPlaylistRepository(get())
    } bind PlaylistRepository::class

    single {
        RealTopPlayedRepository(get(), get(), get(), get())
    } bind TopPlayedRepository::class

    single {
        RealLastAddedRepository(
            get(),
            get(),
            get()
        )
    } bind LastAddedRepository::class

    single {
        RealSearchRepository(
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    single {
        RealLocalDataRepository(get())
    } bind LocalDataRepository::class

    single { MetaData } // Added MetaData definition
}

private val viewModules = module {

    viewModel {
        LibraryViewModel(get())
    }

    viewModel { (albumId: Long) ->
        AlbumDetailsViewModel(
            get(),
            albumId
        )
    }

    viewModel { (artistId: Long?, artistName: String?) ->
        ArtistDetailsViewModel(
            get(),
            artistId,
            artistName
        )
    }

    viewModel { (playlistId: Long) ->
        PlaylistDetailsViewModel(
            get(),
            playlistId
        )
    }

    viewModel { (genre: Genre) ->
        GenreDetailsViewModel(
            get(),
            genre
        )
    }
}

@UnstableApi
val cacheModule = module {

    single<DatabaseProvider> {
        StandaloneDatabaseProvider(androidContext())
    }

    single(named("playerCache")) {
        val context = androidContext()
        val databaseProvider = get<DatabaseProvider>()

        val constructor = {
            SimpleCache(
                context.filesDir.resolve("exoplayer"),
                when (val cacheSize = context.dataStore[MaxSongCacheSizeKey] ?: 1024) {
                    -1 -> NoOpCacheEvictor()
                    else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024L * 1024L)
                },
                databaseProvider
            )
        }

        val tmp = constructor()
        tmp.release()
        constructor()
    }

    single(named("downloadCache")) {
        val context = androidContext()
        val databaseProvider = get<DatabaseProvider>()

        val constructor = {
            SimpleCache(
                context.filesDir.resolve("download"),
                NoOpCacheEvictor(),
                databaseProvider
            )
        }

        val tmp = constructor()
        tmp.release()
        constructor()
    }
}

@UnstableApi
val downloadModule = module {
    single {
        DownloadUtil(androidContext(), get(), get(), get(named("downloadCache")), get(named("playerCache")))
    }
}

@UnstableApi
val appModules = listOf(
    mainModule,
    dataModule,
    autoModule,
    viewModules,
    networkModule,
    roomModule,
    cacheModule,   // ← make sure cacheModule is registered BEFORE downloadModule
    downloadModule // ← downloadModule depends on caches
)
