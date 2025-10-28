package code.name.monkey.lost.util

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

