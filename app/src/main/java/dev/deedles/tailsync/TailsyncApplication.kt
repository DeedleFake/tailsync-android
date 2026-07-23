package dev.deedles.tailsync

import android.app.Application
import android.content.Context
import go.Seq
import mobile.Mobile

class TailsyncApplication : Application() {

    /** Single app-scoped settings store (shared by UI + service). */
    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * Runs before [onCreate] and before most class loading. Set process env
     * here so it is present if anything loads libgojni early.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Best-effort before DiagLog exists.
        try {
            TsnetAndroidEnv.apply(base)
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        settingsRepository = SettingsRepository(this)
        // If last process died mid-start (native abort), do not auto-restart.
        if (DiagLog.lastStartLookedIncomplete(this) && settingsRepository.isServiceWanted()) {
            DiagLog.w("Clearing service_wanted after incomplete previous start")
            settingsRepository.setServiceWanted(false)
        }
        // Re-apply after DiagLog so env is in the breadcrumb trail.
        DiagLog.i("Application.onCreate: applying tsnet env")
        TsnetAndroidEnv.apply(this)
        // Required gomobile init for Go↔JVM callbacks on Android.
        // Note: Seq static init loads libgojni — env must already be set.
        DiagLog.i("Application.onCreate: Seq.setContext + Mobile.touch")
        Seq.setContext(applicationContext)
        Mobile.touch()
        DiagLog.i("Application.onCreate: Mobile.version=${runCatching { Mobile.version() }.getOrNull()}")
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
