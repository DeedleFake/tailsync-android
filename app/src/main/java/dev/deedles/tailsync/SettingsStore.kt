package dev.deedles.tailsync

import java.io.File

/**
 * App-wide settings surface. Prefer a single [SettingsRepository] instance so
 * encrypted-prefs recovery and [consumeAuthKeyResetNotice] are shared between
 * the UI and the foreground service.
 */
interface SettingsStore {
    fun defaultSyncDir(): File
    fun defaultStateDir(): File
    fun hasAuthKey(): Boolean
    fun consumeAuthKeyResetNotice(): Boolean
    fun isServiceWanted(): Boolean
    fun setServiceWanted(wanted: Boolean)
    fun load(): UserSettings
    fun save(settings: UserSettings)
}

/** Starts/stops the sync foreground service (testable seam). */
interface ServiceGateway {
    fun start()
    fun stop()
}
