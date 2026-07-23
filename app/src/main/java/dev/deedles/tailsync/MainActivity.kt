package dev.deedles.tailsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.deedles.tailsync.ui.TailsyncScreen
import dev.deedles.tailsync.ui.theme.TailsyncTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val manageAllFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshStoragePermission()
        }

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Best-effort persistent URI grant; the engine uses the absolute path.
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Some providers do not grant persistable permissions.
                }
                viewModel.onTreePicked(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()

        setContent {
            TailsyncTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TailsyncScreen(
                        viewModel = viewModel,
                        onPickDirectory = {
                            if (StorageAccess.hasAllFilesAccess()) {
                                openTree.launch(null)
                            } else {
                                viewModel.refreshStoragePermission()
                                openAllFilesSettings()
                            }
                        },
                        onGrantAllFilesAccess = { openAllFilesSettings() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStoragePermission()
    }

    private fun openAllFilesSettings() {
        try {
            manageAllFiles.launch(StorageAccess.manageAllFilesIntent(this))
        } catch (_: Exception) {
            // Device without the settings activity — still refresh state.
            viewModel.refreshStoragePermission()
        }
    }

    private fun maybeRequestNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
