package dev.deedles.tailsync

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Configures process environment variables that tsnet / Tailscale expect for
 * log state and cache directories. Without these, `tsnet.Up` panics with
 * "no safe place found to store log state" on Android (no $HOME).
 *
 * Must run **before** any Mobile/tsnet call that starts the control client.
 */
object TsnetAndroidEnv {

    private const val TAG = "TsnetAndroidEnv"

    @Volatile
    private var applied: Boolean = false

    fun apply(context: Context) {
        if (applied) return
        synchronized(this) {
            if (applied) return
            val app = context.applicationContext
            val home = app.filesDir.absolutePath
            val cache = app.cacheDir.absolutePath
            val logs = File(app.filesDir, "ts-logs").apply { mkdirs() }.absolutePath
            val tmp = File(app.cacheDir, "tmp").apply { mkdirs() }.absolutePath

            setEnv("HOME", home)
            setEnv("XDG_CACHE_HOME", cache)
            setEnv("XDG_CONFIG_HOME", home)
            setEnv("TMPDIR", tmp)
            // Tailscale logpolicy prefers TS_LOGS_DIR when set.
            setEnv("TS_LOGS_DIR", logs)

            applied = true
            Log.i(TAG, "tsnet env: HOME=$home TS_LOGS_DIR=$logs TMPDIR=$tmp")
        }
    }

    private fun setEnv(key: String, value: String) {
        try {
            Os.setenv(key, value, /* overwrite = */ true)
        } catch (e: Exception) {
            Log.e(TAG, "Os.setenv($key) failed", e)
        }
    }
}
