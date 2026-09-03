package dev.deedles.tailsync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.GeneralSecurityException

/**
 * Persists non-secret node settings in ordinary SharedPreferences and the
 * Tailscale auth key in EncryptedSharedPreferences (Keystore-backed).
 *
 * The auth key is never written to logs from this class.
 */
class SettingsRepository(context: Context) : SettingsStore {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val secureLock = Any()

    @Volatile
    private var securePrefs: SharedPreferences? = null

    @Volatile
    private var authKeyWasReset: Boolean = false

    override fun defaultStateDir(): File = File(appContext.filesDir, DEFAULT_STATE_SUBDIR)

    /** True if a non-blank auth key is present in encrypted storage. */
    override fun hasAuthKey(): Boolean = try {
        !getSecurePrefs().getString(KEY_AUTH_KEY, null).isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    override fun clearAuthKey() {
        writeAuthKey("")
    }

    /**
     * One-shot flag set when encrypted prefs had to be wiped due to
     * Keystore/crypto failure. Cleared when read.
     */
    override fun consumeAuthKeyResetNotice(): Boolean {
        val was = authKeyWasReset
        authKeyWasReset = false
        return was
    }

    override fun isServiceWanted(): Boolean = prefs.getBoolean(KEY_SERVICE_WANTED, false)

    override fun setServiceWanted(wanted: Boolean) {
        prefs.edit { putBoolean(KEY_SERVICE_WANTED, wanted) }
    }

    override fun load(): UserSettings {
        // Sync root is unset until the user picks a folder (no app-private product default).
        val syncDir = prefs.getString(KEY_SYNC_DIR, null)?.takeIf { it.isNotBlank() } ?: ""
        val stateDir = prefs.getString(KEY_STATE_DIR, null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultStateDir().absolutePath
        return UserSettings(
            syncDir = syncDir,
            stateDir = stateDir,
            hostname = prefs.getString(KEY_HOSTNAME, "") ?: "",
            authKey = readAuthKey(),
            port = SettingsValidation.clampPort(prefs.getInt(KEY_PORT, 0)),
            peers = prefs.getString(KEY_PEERS, "") ?: "",
            scanIntervalMs = prefs.getLong(KEY_SCAN_MS, 0L).coerceAtLeast(0L),
            syncIntervalMs = prefs.getLong(KEY_SYNC_MS, 0L).coerceAtLeast(0L),
            blockSize = prefs.getInt(KEY_BLOCK_SIZE, 0).coerceAtLeast(0),
            treeUri = prefs.getString(KEY_TREE_URI, null),
        )
    }

    override fun save(settings: UserSettings) {
        val previousSync = prefs.getString(KEY_SYNC_DIR, null)?.takeIf { it.isNotBlank() }
        val previousState = prefs.getString(KEY_STATE_DIR, null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultStateDir().absolutePath
        val newSync = settings.syncDir.trim()
        // Sync-root change with a reused state dir: drop index so the engine does
        // not treat the old tree as offline deletions (peer mass-delete).
        if (previousSync != null && newSync.isNotEmpty() && previousSync != newSync) {
            val stateForIndex = settings.stateDir.trim().ifBlank { previousState }
            // Prefer clearing the prior state dir (where the stale index lives).
            SyncIndexGuard.deleteIndexFiles(File(previousState))
            if (stateForIndex != previousState) {
                SyncIndexGuard.deleteIndexFiles(File(stateForIndex))
            }
            Log.i(TAG, "Cleared sync index after sync dir change")
        }
        prefs.edit {
            putString(KEY_SYNC_DIR, settings.syncDir)
            putString(KEY_STATE_DIR, settings.stateDir)
            putString(KEY_HOSTNAME, settings.hostname)
            putInt(KEY_PORT, SettingsValidation.clampPort(settings.port))
            putString(KEY_PEERS, settings.peers)
            putLong(KEY_SCAN_MS, settings.scanIntervalMs.coerceAtLeast(0L))
            putLong(KEY_SYNC_MS, settings.syncIntervalMs.coerceAtLeast(0L))
            putInt(KEY_BLOCK_SIZE, settings.blockSize.coerceAtLeast(0))
            // Drop legacy preferences removed from the engine / UI.
            remove(KEY_NET_MODE)
            remove(KEY_SERVICE_NAME)
            if (settings.treeUri.isNullOrBlank()) {
                remove(KEY_TREE_URI)
            } else {
                putString(KEY_TREE_URI, settings.treeUri)
            }
        }
        // Blank authKey in a settings write means "leave stored key unchanged".
        // Call [clearAuthKey] to remove it for browser-only login.
        if (settings.authKey.isNotBlank()) {
            writeAuthKey(settings.authKey)
        }
    }

    private fun readAuthKey(): String = try {
        getSecurePrefs().getString(KEY_AUTH_KEY, "") ?: ""
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Failed to read secure prefs; resetting encrypted store")
        recreateSecurePrefs()
        try {
            getSecurePrefs().getString(KEY_AUTH_KEY, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    } catch (e: Exception) {
        // Transient IO / disk errors: do not wipe the auth key store.
        Log.w(TAG, "Failed to read secure prefs (not resetting): ${e.javaClass.simpleName}")
        ""
    }

    private fun writeAuthKey(authKey: String) {
        try {
            getSecurePrefs().edit {
                if (authKey.isBlank()) {
                    remove(KEY_AUTH_KEY)
                } else {
                    putString(KEY_AUTH_KEY, authKey)
                }
            }
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Failed to write secure prefs; resetting encrypted store")
            recreateSecurePrefs()
            if (authKey.isNotBlank()) {
                try {
                    getSecurePrefs().edit { putString(KEY_AUTH_KEY, authKey) }
                } catch (_: Exception) {
                    Log.e(TAG, "Unable to store auth key after secure prefs reset")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write secure prefs (not resetting): ${e.javaClass.simpleName}")
        }
    }

    private fun getSecurePrefs(): SharedPreferences {
        synchronized(secureLock) {
            securePrefs?.let { return it }
            val opened = openSecurePrefsFresh()
            securePrefs = opened
            return opened
        }
    }

    private fun recreateSecurePrefs() {
        synchronized(secureLock) {
            deleteSecurePrefsFiles()
            authKeyWasReset = true
            securePrefs = openSecurePrefsFresh()
        }
    }

    private fun openSecurePrefsFresh(): SharedPreferences {
        return try {
            createEncryptedPrefs()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Encrypted prefs unreadable; recreating")
            deleteSecurePrefsFiles()
            authKeyWasReset = true
            createEncryptedPrefs()
        }
        // Other failures (IO, disk) must not wipe the encrypted store — that
        // permanently drops the auth key. Let callers surface the error.
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        @Suppress("DEPRECATION")
        return EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun deleteSecurePrefsFiles() {
        val dir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        File(dir, "$SECURE_PREFS_NAME.xml").delete()
        File(dir, "$SECURE_PREFS_NAME.bak").delete()
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "tailsync_settings"
        private const val SECURE_PREFS_NAME = "tailsync_secure"
        private const val DEFAULT_STATE_SUBDIR = "tailsync-state"

        private const val KEY_SYNC_DIR = "sync_dir"
        private const val KEY_STATE_DIR = "state_dir"
        private const val KEY_HOSTNAME = "hostname"
        private const val KEY_AUTH_KEY = "auth_key"
        private const val KEY_PORT = "port"
        private const val KEY_PEERS = "peers"
        private const val KEY_SCAN_MS = "scan_interval_ms"
        private const val KEY_SYNC_MS = "sync_interval_ms"
        private const val KEY_BLOCK_SIZE = "block_size"
        /** Legacy key; removed on save. Android always uses tsnet. */
        private const val KEY_NET_MODE = "net_mode"
        /** Legacy key; ServiceName was removed from daemon discovery. */
        private const val KEY_SERVICE_NAME = "service_name"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_SERVICE_WANTED = "service_wanted"
    }
}

data class UserSettings(
    val syncDir: String,
    val stateDir: String,
    val hostname: String,
    val authKey: String,
    val port: Int,
    val peers: String,
    val scanIntervalMs: Long,
    val syncIntervalMs: Long,
    val blockSize: Int,
    val treeUri: String? = null,
)
