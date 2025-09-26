package code.name.monkey.lost.di

import code.name.monkey.lost.util.DownloadManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    // Definition for DownloadManager
    // Koin will use the constructor of DownloadManager and provide the application context.
    single { DownloadManager(androidContext()) }

    // TODO: Add definitions for other dependencies here as you migrate them
    // For example:
    // single<YourRepositoryInterface> { YourRepositoryImplementation(get(), get()) }
    // viewModel { YourViewModel(get()) }
}
