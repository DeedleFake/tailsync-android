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
        )
        assertEquals(1, localGateway.startCount)
        assertEquals(true, local.pendingEnabled.value)
        assertTrue(settings.isServiceWanted())
        local.onClearedForTest()
    }

    @Test
    fun pendingStart_timesOutWhenServiceNeverRuns() {
        val local = MainViewModel(
            settingsRepo = settings,
            serviceGateway = gateway,
            isPathWritable = { true },
            ensureDir = {},
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

        override fun defaultSyncDir(): File = File(settings.syncDir)
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
