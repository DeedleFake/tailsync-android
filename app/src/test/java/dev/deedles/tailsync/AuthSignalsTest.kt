package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSignalsTest {

    @Test
    fun parseAuthEvent_extractsUrl() {
        val event = AuthSignals.parseAuthEvent(
            """{"type":"auth","url":"https://login.tailscale.com/a/example"}""",
        )
        assertEquals("https://login.tailscale.com/a/example", event!!.url)
    }

    @Test
    fun parseAuthEvent_trimsUrl() {
        val event = AuthSignals.parseAuthEvent(
            """{"type":"auth","url":"  https://login.tailscale.com/a/x  "}""",
        )
        assertEquals("https://login.tailscale.com/a/x", event!!.url)
    }

    @Test
    fun parseAuthEvent_ignoresOtherTypes() {
        assertNull(AuthSignals.parseAuthEvent("""{"type":"status","running":false}"""))
        assertNull(AuthSignals.parseAuthEvent("""{"type":"log","msg":"hi"}"""))
        assertNull(AuthSignals.parseAuthEvent("""{"type":"error","msg":"x"}"""))
    }

    @Test
    fun parseAuthEvent_blankUrlIsNull() {
        assertNull(AuthSignals.parseAuthEvent("""{"type":"auth","url":""}"""))
        assertNull(AuthSignals.parseAuthEvent("""{"type":"auth"}"""))
    }

    @Test
    fun parseAuthEvent_invalidJsonIsNull() {
        assertNull(AuthSignals.parseAuthEvent("not-json"))
        assertNull(AuthSignals.parseAuthEvent(""))
    }

    @Test
    fun parseAuthStatus_needsLoginAndUrl() {
        val status = AuthSignals.parseAuthStatus(
            """{"phase":"starting","running":false,"needs_login":true,"auth_url":"https://login.tailscale.com/a/1"}""",
        )
        assertTrue(status.needsLogin)
        assertEquals("https://login.tailscale.com/a/1", status.authUrl)
    }

    @Test
    fun parseAuthStatus_needsLoginWithoutUrl() {
        val status = AuthSignals.parseAuthStatus(
            """{"phase":"starting","needs_login":true}""",
        )
        assertTrue(status.needsLogin)
        assertNull(status.authUrl)
    }

    @Test
    fun parseAuthStatus_clearedWhenNotNeeded() {
        val status = AuthSignals.parseAuthStatus(
            """{"phase":"running","running":true,"needs_login":false}""",
        )
        assertFalse(status.needsLogin)
        assertNull(status.authUrl)
    }

    @Test
    fun parseAuthStatus_missingKeysMeansNoLogin() {
        val status = AuthSignals.parseAuthStatus(
            """{"phase":"running","running":true,"dir":"/data"}""",
        )
        assertFalse(status.needsLogin)
        assertNull(status.authUrl)
    }

    @Test
    fun parseAuthStatus_nullOrBlank() {
        assertFalse(AuthSignals.parseAuthStatus(null).needsLogin)
        assertFalse(AuthSignals.parseAuthStatus("").needsLogin)
        assertFalse(AuthSignals.parseAuthStatus("   ").needsLogin)
    }

    @Test
    fun parseAuthStatus_invalidJson() {
        val status = AuthSignals.parseAuthStatus("{broken")
        assertFalse(status.needsLogin)
        assertNull(status.authUrl)
    }

    @Test
    fun resolveAuthUrl_prefersEventUrl() {
        val status = AuthStatusFields(
            needsLogin = true,
            authUrl = "https://login.tailscale.com/from-status",
        )
        assertEquals(
            "https://login.tailscale.com/from-event",
            AuthSignals.resolveAuthUrl("https://login.tailscale.com/from-event", status),
        )
    }

    @Test
    fun resolveAuthUrl_fallsBackToStatusWhenNeedsLogin() {
        val status = AuthStatusFields(
            needsLogin = true,
            authUrl = "https://login.tailscale.com/from-status",
        )
        assertEquals(
            "https://login.tailscale.com/from-status",
            AuthSignals.resolveAuthUrl(null, status),
        )
    }

    @Test
    fun resolveAuthUrl_nullWhenNotNeeded() {
        val status = AuthStatusFields(needsLogin = false, authUrl = "https://example.com")
        assertNull(AuthSignals.resolveAuthUrl(null, status))
    }

    @Test
    fun parseAuthEvent_unescapesUrl() {
        // Flat-field regex unescapes JSON string escapes (\/ \" \\).
        val event = AuthSignals.parseAuthEvent(
            """{"type":"auth","url":"https:\/\/login.tailscale.com\/a\/path?q=1"}""",
        )
        assertEquals("https://login.tailscale.com/a/path?q=1", event!!.url)
    }

    @Test
    fun parseAuthEvent_keyOrderIndependent() {
        val urlFirst = AuthSignals.parseAuthEvent(
            """{"url":"https://login.tailscale.com/a/order","type":"auth"}""",
        )
        assertEquals("https://login.tailscale.com/a/order", urlFirst!!.url)

        val typeFirst = AuthSignals.parseAuthEvent(
            """{"type":"auth","url":"https://login.tailscale.com/a/order"}""",
        )
        assertEquals("https://login.tailscale.com/a/order", typeFirst!!.url)
    }

    @Test
    fun parseAuthStatus_keyOrderIndependent() {
        val status = AuthSignals.parseAuthStatus(
            """{"auth_url":"https://login.tailscale.com/a/2","needs_login":true,"phase":"starting"}""",
        )
        assertTrue(status.needsLogin)
        assertEquals("https://login.tailscale.com/a/2", status.authUrl)
    }
}
