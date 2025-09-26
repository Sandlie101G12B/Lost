package code.name.monkey.lost

import androidx.room.Room
import code.name.monkey.lost.auto.AutoMusicProvider
import code.name.monkey.lost.cast.LostWebServer
import code.name.monkey.lost.db.MIGRATION_23_24
import code.name.monkey.lost.db.MIGRATION_24_25
import code.name.monkey.lost.db.LostDatabase
import code.name.monkey.lost.fragments.LibraryViewModel
import code.name.monkey.lost.fragments.albums.AlbumDetailsViewModel
import code.name.monkey.lost.fragments.artists.ArtistDetailsViewModel
import code.name.monkey.lost.fragments.genres.GenreDetailsViewModel
import code.name.monkey.lost.fragments.playlists.PlaylistDetailsViewModel
import code.name.monkey.lost.helper.MetaDataManagerHelper // Added import
import code.name.monkey.lost.model.Genre
import code.name.monkey.lost.network.provideDefaultCache
import code.name.monkey.lost.network.provideLastFmRest
import code.name.monkey.lost.network.provideLastFmRetrofit
import code.name.monkey.lost.network.provideOkHttp
import code.name.monkey.lost.util.DownloadManager
import code.name.monkey.lost.repository.*
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
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
            .addMigrations(MIGRATION_23_24, MIGRATION_24_25)
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

    single {
        RealRoomRepository(get(), get(), get(), get())
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
        RealGenreRepository(get(),  get())
    } bind GenreRepository::class

    single {
        RealAlbumRepository(get())
    } bind AlbumRepository::class

    single {
        RealArtistRepository(get(), get())
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

    single { MetaDataManagerHelper } // Added MetaDataManagerHelper definition
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

val downloadModule = module {
    single { DownloadManager(androidContext()) }
}

val appModules = listOf(mainModule, dataModule, autoModule, viewModules, networkModule, roomModule, downloadModule)
