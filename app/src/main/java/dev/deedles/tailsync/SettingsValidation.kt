package dev.deedles.tailsync

/** Pure helpers for form / config validation (unit-testable). */
object SettingsValidation {

    /** Clamp port to the valid TCP range; 0 means “daemon default”. */
    fun clampPort(raw: Int): Int = when {
        raw < 0 -> 0
        raw > 65535 -> 65535
        else -> raw
    }

    fun clampPort(raw: String): Int {
        val digits = raw.filter { it.isDigit() }.take(5)
        return clampPort(digits.toIntOrNull() ?: 0)
    }

    fun nonNegativeLong(raw: String): Long {
        val n = raw.filter { it.isDigit() }.toLongOrNull() ?: 0L
        return if (n < 0L) 0L else n
    }

    fun nonNegativeInt(raw: String): Int {
        val n = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
        return if (n < 0) 0 else n
    }
}
