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

/**
 * JVM tests for interactive-login state machine on [TailsyncRuntime].
 * Runtime must not depend on [AuthBrowser] (Android-free).
 *
 * Uses a test Main dispatcher because process-scoped collectors from other
 * tests may still observe Runtime StateFlows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TailsyncRuntimeAuthTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.markIdle()
        TailsyncRuntime.clearAuthLogin()
        TailsyncRuntime.setLastError(null)
        TailsyncRuntime.clearLogs()
    }

    @After
    fun tearDown() {
        TailsyncRuntime.setServiceRunning(false)
        TailsyncRuntime.markIdle()
        TailsyncRuntime.clearAuthLogin()
        Dispatchers.resetMain()
    }

    @Test
    fun setAuthLogin_setsNeedsLoginAndUrl() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/1")
        assertTrue(TailsyncRuntime.needsLogin.value)
        assertEquals("https://login.tailscale.com/a/1", TailsyncRuntime.authUrl.value)
    }

    @Test
    fun setAuthLogin_blankUrlKeepsPrior() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/prior")
        TailsyncRuntime.setAuthLogin(null)
        assertTrue(TailsyncRuntime.needsLogin.value)
        assertEquals("https://login.tailscale.com/a/prior", TailsyncRuntime.authUrl.value)

        TailsyncRuntime.setAuthLogin("  ")
        assertTrue(TailsyncRuntime.needsLogin.value)
        assertEquals("https://login.tailscale.com/a/prior", TailsyncRuntime.authUrl.value)
    }

    @Test
    fun applyAuthStatus_needsLoginWithUrl() {
        TailsyncRuntime.applyAuthStatus(
            AuthStatusFields(needsLogin = true, authUrl = "https://login.tailscale.com/a/s"),
            running = false,
        )
        assertTrue(TailsyncRuntime.needsLogin.value)
        assertEquals("https://login.tailscale.com/a/s", TailsyncRuntime.authUrl.value)
    }

    @Test
    fun applyAuthStatus_needsLoginWithoutUrl_keepsPrior() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/event")
        TailsyncRuntime.applyAuthStatus(
            AuthStatusFields(needsLogin = true, authUrl = null),
            running = false,
        )
        assertTrue(TailsyncRuntime.needsLogin.value)
        assertEquals("https://login.tailscale.com/a/event", TailsyncRuntime.authUrl.value)
    }

    @Test
    fun applyAuthStatus_notNeededWhileNotRunning_keepsAuth() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/event")
        // Status poll during Start may omit needs_login; must not wipe URL.
        TailsyncRuntime.applyAuthStatus(
            AuthStatusFields(needsLogin = false, authUrl = null),
            running = false,
        )
        assertTrue(TailsyncRuntime.needsLogin.value)
        assertEquals("https://login.tailscale.com/a/event", TailsyncRuntime.authUrl.value)
    }

    @Test
    fun applyAuthStatus_notNeededWhileRunning_clears() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/event")
        TailsyncRuntime.applyAuthStatus(
            AuthStatusFields(needsLogin = false, authUrl = null),
            running = true,
        )
        assertFalse(TailsyncRuntime.needsLogin.value)
        assertNull(TailsyncRuntime.authUrl.value)
    }

    @Test
    fun clearAuthLogin_clearsBoth() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/1")
        TailsyncRuntime.clearAuthLogin()
        assertFalse(TailsyncRuntime.needsLogin.value)
        assertNull(TailsyncRuntime.authUrl.value)
    }

    @Test
    fun markIdle_clearsAuth() {
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/1")
        TailsyncRuntime.markIdle()
        assertFalse(TailsyncRuntime.needsLogin.value)
        assertNull(TailsyncRuntime.authUrl.value)
    }

    @Test
    fun setServiceRunningFalse_clearsAuth() {
        TailsyncRuntime.setServiceRunning(true)
        TailsyncRuntime.setAuthLogin("https://login.tailscale.com/a/1")
        TailsyncRuntime.setServiceRunning(false)
        assertFalse(TailsyncRuntime.needsLogin.value)
        assertNull(TailsyncRuntime.authUrl.value)
    }
}
