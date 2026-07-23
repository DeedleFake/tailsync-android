package dev.deedles.tailsync

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Configures process environment variables that tsnet / Tailscale expect for
 * log state and cache directories. Without these, `tsnet.Up` panics with
 * "no safe place found to store log state" on Android (no $HOME).
 *
 * Must run **before** any Mobile/tsnet call that starts the control client.
 */
object TsnetAndroidEnv {

    @Volatile
    private var applied: Boolean = false

    /** Paths last applied (for diagnostics). */
    @Volatile
    var lastSummary: String = "(not applied)"
        private set

    fun apply(context: Context) {
        if (applied) {
            DiagLog.i("TsnetAndroidEnv.apply skipped (already applied) $lastSummary")
            return
        }
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

            // Verify what the process actually sees (Go uses C getenv).
            val seen = listOf("HOME", "TS_LOGS_DIR", "TMPDIR", "XDG_CACHE_HOME").joinToString {
                "$it=${runCatching { Os.getenv(it) }.getOrNull() ?: "(null)"}"
            }
            val writable = listOf(home, cache, logs, tmp).joinToString { p ->
                val f = File(p)
                "$p[exists=${f.exists()} write=${f.canWrite()}]"
            }
            lastSummary = "$seen | dirs: $writable"
            applied = true
            DiagLog.i("TsnetAndroidEnv.apply OK: $lastSummary")
        }
    }

    private fun setEnv(key: String, value: String) {
        try {
            Os.setenv(key, value, /* overwrite = */ true)
            DiagLog.i("setenv $key=$value")
        } catch (e: Exception) {
            DiagLog.e("Os.setenv($key) failed", e)
        }
    }
}
