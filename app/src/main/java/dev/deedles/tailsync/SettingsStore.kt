package dev.deedles.tailsync

import java.io.File

/**
 * App-wide settings surface. Prefer a single [SettingsRepository] instance so
 * encrypted-prefs recovery and [consumeAuthKeyResetNotice] are shared between
 * the UI and the foreground service.
 */
interface SettingsStore {
    /** App-private state/index + tsnet dir (not the user sync root). */
    fun defaultStateDir(): File
    fun hasAuthKey(): Boolean
    /** Removes any stored auth key (browser login becomes the path). */
    fun clearAuthKey()
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
