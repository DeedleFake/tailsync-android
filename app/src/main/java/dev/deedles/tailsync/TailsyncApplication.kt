package dev.deedles.tailsync

import android.app.Application
import go.Seq
import mobile.Mobile

class TailsyncApplication : Application() {

    /** Single app-scoped settings store (shared by UI + service). */
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        // tsnet needs HOME / TS_LOGS_DIR before Up (else panic + process abort).
        TsnetAndroidEnv.apply(this)
        // Required gomobile init for Go↔JVM callbacks on Android.
        Seq.setContext(applicationContext)
        Mobile.touch()
    }

    companion object {
        /**
         * Returns the process-wide settings store. Fails fast if the manifest
         * does not use [TailsyncApplication] or [onCreate] has not run — never
         * silently constructs a second repository (which would split secure prefs).
         */
        fun settingsOf(application: Application): SettingsStore {
            val app = application as? TailsyncApplication
                ?: error(
                    "Application must be TailsyncApplication " +
                        "(android:name in AndroidManifest). " +
                        "Got: ${application.javaClass.name}",
                )
            check(app::settingsRepository.isInitialized) {
                "SettingsRepository not initialized; Application.onCreate has not run"
            }
            return app.settingsRepository
        }
    }
}
