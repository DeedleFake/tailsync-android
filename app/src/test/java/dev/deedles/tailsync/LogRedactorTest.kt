package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {

    @Test
    fun redactsTsKeyPrefix() {
        val inText = "using key tskey-auth-abcDEF123_x for login"
        val out = LogRedactor.redact(inText)
        assertFalse(out.contains("tskey-auth-abcDEF123_x"))
        assertTrueContains(out, "tskey-[redacted]")
    }

    @Test
    fun redactsAuthKeyAssignment() {
        val out = LogRedactor.redact("auth_key=supersecretvalue")
        assertEquals("auth_key=[redacted]", out)
    }

    @Test
    fun leavesOrdinaryLogsAlone() {
        val msg = "INFO: peer connected host=100.64.0.1"
        assertEquals(msg, LogRedactor.redact(msg))
    }

    private fun assertTrueContains(haystack: String, needle: String) {
        if (!haystack.contains(needle)) {
            throw AssertionError("Expected '$haystack' to contain '$needle'")
        }
    }
}
