package dev.deedles.tailsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * All-files access ([android.Manifest.permission.MANAGE_EXTERNAL_STORAGE]) helpers.
 *
 * Sync roots are absolute filesystem paths for the Go engine; scoped storage alone
 * cannot provide that for arbitrary user folders. State/index remains app-private.
 */
object StorageAccess {

    /** Whether the app currently has "All files access". */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Intent that opens the system screen to grant all-files access for this app.
     * Prefer the package-specific action; fall back to the general list if needed.
     */
    fun manageAllFilesIntent(context: Context): Intent {
        val pkg = context.packageName
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$pkg"),
        ).takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
