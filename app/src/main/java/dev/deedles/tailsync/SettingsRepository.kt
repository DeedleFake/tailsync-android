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
            serviceName = prefs.getString(KEY_SERVICE_NAME, "") ?: "",
            scanIntervalMs = prefs.getLong(KEY_SCAN_MS, 0L).coerceAtLeast(0L),
            syncIntervalMs = prefs.getLong(KEY_SYNC_MS, 0L).coerceAtLeast(0L),
            blockSize = prefs.getInt(KEY_BLOCK_SIZE, 0).coerceAtLeast(0),
            treeUri = prefs.getString(KEY_TREE_URI, null),
        )
    }

    override fun save(settings: UserSettings) {
        prefs.edit {
            putString(KEY_SYNC_DIR, settings.syncDir)
            putString(KEY_STATE_DIR, settings.stateDir)
            putString(KEY_HOSTNAME, settings.hostname)
            putInt(KEY_PORT, SettingsValidation.clampPort(settings.port))
            putString(KEY_PEERS, settings.peers)
            putString(KEY_SERVICE_NAME, settings.serviceName)
            putLong(KEY_SCAN_MS, settings.scanIntervalMs.coerceAtLeast(0L))
            putLong(KEY_SYNC_MS, settings.syncIntervalMs.coerceAtLeast(0L))
            putInt(KEY_BLOCK_SIZE, settings.blockSize.coerceAtLeast(0))
            // Drop legacy net_mode preference (always tsnet on Android).
            remove(KEY_NET_MODE)
            if (settings.treeUri.isNullOrBlank()) {
                remove(KEY_TREE_URI)
            } else {
                putString(KEY_TREE_URI, settings.treeUri)
            }
        }
        writeAuthKey(settings.authKey)
    }

    private fun readAuthKey(): String = try {
        getSecurePrefs().getString(KEY_AUTH_KEY, "") ?: ""
    } catch (_: Exception) {
        Log.w(TAG, "Failed to read secure prefs; resetting encrypted store")
        recreateSecurePrefs()
        try {
            getSecurePrefs().getString(KEY_AUTH_KEY, "") ?: ""
        } catch (_: Exception) {
            ""
        }
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
        } catch (_: Exception) {
            Log.w(TAG, "Failed to write secure prefs; resetting encrypted store")
            recreateSecurePrefs()
            if (authKey.isNotBlank()) {
                try {
                    getSecurePrefs().edit { putString(KEY_AUTH_KEY, authKey) }
                } catch (_: Exception) {
                    Log.e(TAG, "Unable to store auth key after secure prefs reset")
                }
            }
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
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs open failed; recreating")
            deleteSecurePrefsFiles()
            authKeyWasReset = true
            createEncryptedPrefs()
        }
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
        private const val KEY_SERVICE_NAME = "service_name"
        private const val KEY_SCAN_MS = "scan_interval_ms"
        private const val KEY_SYNC_MS = "sync_interval_ms"
        private const val KEY_BLOCK_SIZE = "block_size"
        /** Legacy key; removed on save. Android always uses tsnet. */
        private const val KEY_NET_MODE = "net_mode"
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
    val serviceName: String,
    val scanIntervalMs: Long,
    val syncIntervalMs: Long,
    val blockSize: Int,
    val treeUri: String? = null,
)
