package code.name.monkey.lost.util

import code.name.monkey.lost.BuildConfig
import timber.log.Timber

fun Any.logD(message: Any?) {
    logD(message.toString())
}

fun Any.logD(message: String) {
    if (BuildConfig.DEBUG) {
        Timber.tag(name).d(message)
    }
}

fun Any.logE(message: String) {
    Timber.tag(name).e(message)
}

fun Any.logE(e: Exception) {
    Timber.tag(name).e(e.message ?: "Error")
}

private val Any.name: String get() = this::class.java.simpleName