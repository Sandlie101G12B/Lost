package code.name.monkey.lost.util

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import kotlin.properties.ReadOnlyProperty

inline fun <reified T : Enum<T>> String?.toEnum(defaultValue: T): T =
    if (this == null) {
        defaultValue
    } else {
        try {
            enumValueOf(this)
        } catch (_: IllegalArgumentException) {
            defaultValue
        }
    }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }
