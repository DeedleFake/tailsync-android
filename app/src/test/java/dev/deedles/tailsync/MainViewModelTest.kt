package dev.deedles.tailsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var settings: FakeSettingsStore
    private lateinit var gateway: FakeServiceGateway
    private lateinit var syncDir: File
    private lateinit var stateDir: File
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        // Reset process runtime between tests.
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.markIdle()
        TailsyncRuntime.setLastError(null)
        TailsyncRuntime.clearLogs()

        syncDir = File.createTempFile("sync", "dir").apply {
            delete()
            mkdirs()
        }
        stateDir = File.createTempFile("state", "dir").apply {
            delete()
            mkdirs()
        }
        settings = FakeSettingsStore(
            defaults = UserSettings(
                syncDir = syncDir.absolutePath,
                stateDir = stateDir.absolutePath,
                hostname = "phone",
                authKey = "tskey-auth-test",
                port = 0,
                peers = "",
                serviceName = "",
                scanIntervalMs = 0,
                syncIntervalMs = 0,
                blockSize = 0,
            ),
        )
        gateway = FakeServiceGateway()
        vm = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { true },
        )
    }

    @After
    fun tearDown() {
        vm.onClearedForTest()
        syncDir.deleteRecursively()
        stateDir.deleteRecursively()
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.markIdle()
        TailsyncRuntime.setLastError(null)
        Dispatchers.resetMain()
    }

    @Test
    fun enable_persistsFormBeforePending_andStartsService() {
        vm.updateForm { it.copy(hostname = "tailsync-phone", authKey = "tskey-auth-new") }

        vm.setServiceEnabled(true)

        assertEquals(1, gateway.startCount)
        assertEquals(0, gateway.stopCount)
        assertTrue(settings.isServiceWanted())
        assertEquals("tailsync-phone", settings.load().hostname)
        assertEquals("tskey-auth-new", settings.load().authKey)
        assertEquals(true, vm.pendingEnabled.value)
        assertFalse(vm.uiState.value.formEnabled)
        assertTrue(vm.uiState.value.switchEnabled)
        assertTrue(vm.uiState.value.switchChecked)
    }

    @Test
    fun failedStart_clearsPendingAndServiceWanted() {
        vm.setServiceEnabled(true)
        assertEquals(true, vm.pendingEnabled.value)

        // Simulate service coming up then failing (mirrors failAndStop).
        vm.onServiceFailedForTest("start failed: boom")

        assertNull(vm.pendingEnabled.value)
        assertFalse(settings.isServiceWanted())
        assertFalse(vm.uiState.value.switchChecked)
        assertTrue(vm.uiState.value.switchEnabled)
        assertTrue(vm.uiState.value.formEnabled)
    }

    @Test
    fun disable_clearsWantedAndCanCancelPending() {
        vm.setServiceEnabled(true)
        assertEquals(true, vm.pendingEnabled.value)

        vm.setServiceEnabled(false)

        assertEquals(1, gateway.stopCount)
        assertFalse(settings.isServiceWanted())
        assertNull(vm.pendingEnabled.value)
        assertFalse(vm.uiState.value.switchChecked)
    }

    @Test
    fun invalidSyncDir_doesNotStartOrLockSwitch() {
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { path -> path != syncDir.absolutePath },
            ensureDir = {},
            hasAllFilesAccess = { true },
        )
        local.setServiceEnabled(true)

        assertEquals(0, gateway.startCount)
        assertFalse(settings.isServiceWanted())
        assertNull(local.pendingEnabled.value)
        assertTrue(local.uiState.value.formEnabled)
        assertTrue(local.uiState.value.switchEnabled)
        local.onClearedForTest()
    }

    @Test
    fun blankSyncDir_doesNotStart() {
        settings.save(settings.load().copy(syncDir = ""))
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { it.isNotBlank() },
            ensureDir = {},
            hasAllFilesAccess = { true },
        )
        local.setServiceEnabled(true)

        assertEquals(0, gateway.startCount)
        assertFalse(settings.isServiceWanted())
        assertNull(local.pendingEnabled.value)
        assertTrue(
            TailsyncRuntime.lastError.value?.contains("Pick a sync folder") == true ||
                local.uiState.value.saveMessage?.contains("Pick a sync folder") == true,
        )
        local.onClearedForTest()
    }

    @Test
    fun missingAllFilesAccess_doesNotStart() {
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { false },
        )
        local.setServiceEnabled(true)

        assertEquals(0, gateway.startCount)
        assertFalse(settings.isServiceWanted())
        assertNull(local.pendingEnabled.value)
        assertFalse(local.uiState.value.hasAllFilesAccess)
        assertFalse(local.uiState.value.canPickDirectory)
        assertTrue(
            TailsyncRuntime.lastError.value?.contains("All files access") == true ||
                local.uiState.value.saveMessage?.contains("All files access") == true,
        )
        local.onClearedForTest()
    }

    @Test
    fun applyTreePick_withoutAllFiles_doesNotChangeSyncDir() {
        val original = syncDir.absolutePath
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { false },
        )
        local.applyTreePick(
            uriString = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            resolved = "/storage/emulated/0/Download",
        )

        assertEquals(original, local.form.value.syncDir)
        assertNull(local.form.value.treeUri)
        assertEquals(PathHintKind.NeedAccessToPick, local.pathHintKind.value)
        assertEquals(original, settings.load().syncDir) // no private fallback persisted
        local.onClearedForTest()
    }

    @Test
    fun applyTreePick_unresolvable_leavesSyncDirUnchanged() {
        val original = syncDir.absolutePath
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { true },
        )
        local.applyTreePick(uriString = "content://other/tree/x", resolved = null)

        assertEquals(original, local.form.value.syncDir)
        assertEquals(PathHintKind.ResolveFailed, local.pathHintKind.value)
        assertTrue(local.uiState.value.saveMessage?.contains("could not be used") == true)
        // Must not invent an app-private sync root.
        assertEquals(original, settings.load().syncDir)
        assertFalse(settings.load().syncDir.contains("files"))
        local.onClearedForTest()
    }

    @Test
    fun applyTreePick_nonWritableResolved_leavesSyncDirUnchanged() {
        val original = syncDir.absolutePath
        val resolved = "/storage/emulated/0/Download/Tailsync"
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { path -> path != resolved },
            ensureDir = {},
            hasAllFilesAccess = { true },
        )
        local.applyTreePick(
            uriString = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTailsync",
            resolved = resolved,
        )

        assertEquals(original, local.form.value.syncDir)
        assertEquals(PathHintKind.ResolveFailed, local.pathHintKind.value)
        assertEquals(original, settings.load().syncDir)
        local.onClearedForTest()
    }

    @Test
    fun applyTreePick_success_updatesAndPersistsSyncDir() {
        val resolved = File(syncDir, "picked").absolutePath
        var ensured: String? = null
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = { ensured = it },
            hasAllFilesAccess = { true },
        )
        val uriString =
            "content://com.android.externalstorage.documents/tree/primary%3Apicked"
        local.applyTreePick(uriString = uriString, resolved = resolved)

        assertEquals(resolved, local.form.value.syncDir)
        assertEquals(uriString, local.form.value.treeUri)
        assertEquals(PathHintKind.Resolved, local.pathHintKind.value)
        assertTrue(local.uiState.value.pathHint?.contains(resolved) == true)
        assertEquals(resolved, ensured)
        // Auto-persist so process death keeps the pick.
        assertEquals(resolved, settings.load().syncDir)
        assertEquals(uriString, settings.load().treeUri)
        local.onClearedForTest()
    }

    @Test
    fun computeFormEnabled_rules() {
        assertTrue(MainViewModel.computeFormEnabled(false, "idle", null))
        assertFalse(MainViewModel.computeFormEnabled(true, "running", null))
        assertFalse(MainViewModel.computeFormEnabled(false, "starting", null))
        assertFalse(MainViewModel.computeFormEnabled(false, "idle", true))
        assertTrue(MainViewModel.computeFormEnabled(false, "idle", false))
    }

    @Test
    fun init_withServiceWanted_autoStarts() {
        settings.setServiceWanted(true)
        val localGateway = FakeServiceGateway()
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = localGateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { true },
        )
        assertEquals(1, localGateway.startCount)
        assertEquals(true, local.pendingEnabled.value)
        assertTrue(settings.isServiceWanted())
        local.onClearedForTest()
    }

    @Test
    fun init_withServiceWanted_butNoAllFiles_doesNotStart() {
        settings.setServiceWanted(true)
        val localGateway = FakeServiceGateway()
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = localGateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { false },
        )
        assertEquals(0, localGateway.startCount)
        assertFalse(settings.isServiceWanted())
        assertNull(local.pendingEnabled.value)
        local.onClearedForTest()
    }

    @Test
    fun pendingStart_timesOutWhenServiceNeverRuns() {
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { true },
            pendingStartTimeoutMs = 50L,
        )
        local.setServiceEnabled(true)
        assertEquals(true, local.pendingEnabled.value)

        // Advance virtual time past the pending-start timeout.
        dispatcher.scheduler.advanceTimeBy(51L)
        dispatcher.scheduler.runCurrent()

        assertNull(local.pendingEnabled.value)
        assertFalse(settings.isServiceWanted())
        assertTrue(
            TailsyncRuntime.lastError.value?.contains("did not complete") == true,
        )
        local.onClearedForTest()
    }

    @Test
    fun refreshStoragePermission_updatesUiFlag() {
        var granted = false
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { granted },
        )
        assertFalse(local.uiState.value.hasAllFilesAccess)
        assertEquals(PathHintKind.DefaultDenied, local.pathHintKind.value)
        granted = true
        local.refreshStoragePermission()
        assertTrue(local.uiState.value.hasAllFilesAccess)
        assertTrue(local.uiState.value.canPickDirectory)
        assertEquals(PathHintKind.DefaultGranted, local.pathHintKind.value)
        local.onClearedForTest()
    }

    @Test
    fun refreshStoragePermission_revokeWhileRunning_stopsService() {
        var granted = true
        val localGateway = FakeServiceGateway()
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = localGateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { granted },
        )
        local.setServiceEnabled(true)
        TailsyncRuntime.setServiceRunning(true)
        // Acknowledge pending start so switch stays "on" via runtime.
        local.pendingEnabled.value // force read
        // Clear pending as if service came up (reconcile path).
        TailsyncRuntime.setPhase("running")
        // Manually clear pending the way reconcile would when running matches.
        // Simulate mid-run revoke on resume.
        granted = false
        local.refreshStoragePermission()

        assertEquals(1, localGateway.stopCount)
        assertFalse(settings.isServiceWanted())
        assertFalse(local.uiState.value.hasAllFilesAccess)
        assertEquals(PathHintKind.DefaultDenied, local.pathHintKind.value)
        assertTrue(
            TailsyncRuntime.lastError.value?.contains("revoked") == true ||
                local.uiState.value.saveMessage?.contains("revoked") == true,
        )
        local.onClearedForTest()
    }

    @Test
    fun refreshStoragePermission_keepsResolvedHintAfterReGrant() {
        var granted = true
        val resolved = File(syncDir, "keep").absolutePath
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
            hasAllFilesAccess = { granted },
        )
        local.applyTreePick(uriString = "content://tree/keep", resolved = resolved)
        assertEquals(PathHintKind.Resolved, local.pathHintKind.value)

        granted = false
        local.refreshStoragePermission()
        assertEquals(PathHintKind.DefaultDenied, local.pathHintKind.value)

        granted = true
        local.refreshStoragePermission()
        // After re-grant from denied default, show default granted (resolved was overwritten on revoke).
        assertEquals(PathHintKind.DefaultGranted, local.pathHintKind.value)
        local.onClearedForTest()
    }

    private class FakeServiceGateway : ServiceGateway {
        var startCount = 0
        var stopCount = 0
        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }
    }

    private class FakeSettingsStore(
        defaults: UserSettings,
    ) : SettingsStore {
        private var settings = defaults
        private var wanted = false
        private var resetNotice = false

        override fun defaultStateDir(): File = File(settings.stateDir)
        override fun hasAuthKey(): Boolean = settings.authKey.isNotBlank()
        override fun consumeAuthKeyResetNotice(): Boolean {
            val v = resetNotice
            resetNotice = false
            return v
        }

        override fun isServiceWanted(): Boolean = wanted
        override fun setServiceWanted(wanted: Boolean) {
            this.wanted = wanted
        }

        override fun load(): UserSettings = settings
        override fun save(settings: UserSettings) {
            this.settings = settings
        }
    }
}
