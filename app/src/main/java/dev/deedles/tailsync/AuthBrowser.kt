package dev.deedles.tailsync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens Tailscale interactive login URLs via Chrome Custom Tabs when possible,
 * falling back to a generic [Intent.ACTION_VIEW].
 *
 * Tracks the last **successfully** auto-opened URL so the same login link is
 * not reopened repeatedly (event + status poll + UI composition). Failed opens
 * do not stamp the URL, so a later retry can succeed.
 */
object AuthBrowser {
    private const val TAG = "AuthBrowser"

    @Volatile
    private var lastAutoOpenedUrl: String? = null

    /** True if [openOnce] would attempt a launch for [url]. */
    fun shouldOpenOnce(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.isNotEmpty() && trimmed != lastAutoOpenedUrl
    }

    /**
     * Opens [url] only if it differs from the last successfully opened URL.
     * @return true if a browser/Custom Tab was launched
     */
    fun openOnce(context: Context, url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == lastAutoOpenedUrl) return false
        val ok = launch(context, trimmed)
        if (ok) {
            lastAutoOpenedUrl = trimmed
        }
        return ok
    }

    /**
     * Always attempts to open [url] (explicit user action / notification).
     * @return true if a browser/Custom Tab was launched
     */
    fun open(context: Context, url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val ok = launch(context, trimmed)
        if (ok) {
            // Track so concurrent auto-open of the same URL is a no-op.
            lastAutoOpenedUrl = trimmed
        }
        return ok
    }

    fun clearAutoOpenTracking() {
        lastAutoOpenedUrl = null
    }

    private fun launch(context: Context, url: String): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            Log.w(TAG, "Invalid auth URL")
            return false
        }
        if (uri.scheme != "https" && uri.scheme != "http") {
            Log.w(TAG, "Refusing non-http(s) auth URL")
            return false
        }
        return try {
            val customTabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(context, uri)
            true
        } catch (_: ActivityNotFoundException) {
            openWithViewIntent(context, uri)
        } catch (_: Exception) {
            openWithViewIntent(context, uri)
        }
    }

    private fun openWithViewIntent(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open auth URL: ${e.javaClass.simpleName}")
            false
        }
    }
}
