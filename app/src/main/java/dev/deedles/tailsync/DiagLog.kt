package dev.deedles.tailsync

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic logging for native/tsnet crashes that kill the process before
 * the in-app event log can help. Writes durable breadcrumbs under app files
 * so the next launch can surface "what happened last time".
 *
 * Filter logcat with: `adb logcat -s TailsyncDiag GoLog Go`
 */
object DiagLog {

    const val TAG = "TailsyncDiag"

    private const val BREADCRUMB_FILE = "tailsync-diag.log"
    private const val MAX_FILE_BYTES = 64 * 1024

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var file: File? = null
    private val installedHandlers = AtomicBoolean(false)

    fun init(context: Context) {
        synchronized(lock) {
            file = File(context.applicationContext.filesDir, BREADCRUMB_FILE)
        }
        installCrashHandlers(context.applicationContext)
        i("diag init pid=${android.os.Process.myPid()}")
    }

    fun i(message: String) {
        val line = format("I", message)
        Log.i(TAG, message)
        append(line)
        // Also mirror into in-app log when runtime is up (best-effort).
        runCatching { TailsyncRuntime.appendLog("diag: $message") }
    }

    fun w(message: String, t: Throwable? = null) {
        val extra = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        val line = format("W", message + extra)
        if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
        append(line)
        runCatching { TailsyncRuntime.appendLog("diag-warn: $message$extra") }
    }

    fun e(message: String, t: Throwable? = null) {
        val extra = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        val line = format("E", message + extra)
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        append(line)
        runCatching { TailsyncRuntime.appendLog("diag-err: $message$extra") }
    }

    /** Mark that a clean start completed (clears "incomplete start" marker). */
    fun markStartSucceeded() {
        i("START_OK")
    }

    fun markStartBegin(gen: Long) {
        i("START_BEGIN gen=$gen")
    }

    fun markStartFailed(reason: String) {
        e("START_FAILED: $reason")
    }

    /**
     * Last breadcrumb lines for UI / support. Empty if none.
     */
    fun readRecent(context: Context, maxLines: Int = 80): String {
        val f = File(context.applicationContext.filesDir, BREADCRUMB_FILE)
        if (!f.isFile) return ""
        return try {
            val lines = f.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (t: Throwable) {
            "failed to read diag log: ${t.message}"
        }
    }

    /**
     * True if the last durable trail ended mid-start without START_OK / START_FAILED.
     * Used to clear sticky serviceWanted after a native abort.
     */
    fun lastStartLookedIncomplete(context: Context): Boolean {
        val text = readRecent(context, 40)
        if (text.isBlank()) return false
        val lastStart = text.lineSequence().map { it.trim() }.lastOrNull {
            it.contains("START_BEGIN") || it.contains("START_OK") || it.contains("START_FAILED")
        } ?: return false
        return lastStart.contains("START_BEGIN")
    }

    private fun format(level: String, message: String): String {
        val ts = timeFmt.format(Date())
        val tid = Thread.currentThread().name
        return "$ts $level [$tid] $message"
    }

    private fun append(line: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                f.appendText(line + "\n")
                if (f.length() > MAX_FILE_BYTES) {
                    val keep = f.readLines().takeLast(200)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Throwable) {
                // Never throw from diagnostics.
            }
        }
    }

    private fun installCrashHandlers(appContext: Context) {
        if (!installedHandlers.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                e(
                    "UNCAUGHT on ${thread.name}: ${error.javaClass.name}: ${error.message}",
                    error,
                )
                error.stackTrace.take(30).forEach { frame ->
                    e("  at $frame")
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
                ?: run {
                    // Ensure process still dies after logging.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
        }
        // Note: Go/native SIGABRT will not hit this handler; breadcrumbs before
        // the JNI call are the main signal for those.
        i("JVM uncaught handlers installed")
    }
}
