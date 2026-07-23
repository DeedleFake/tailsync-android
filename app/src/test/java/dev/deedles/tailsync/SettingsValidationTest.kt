package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValidationTest {

    @Test
    fun clampPort_acceptsZeroAndMax() {
        assertEquals(0, SettingsValidation.clampPort(0))
        assertEquals(65535, SettingsValidation.clampPort(65535))
        assertEquals(5960, SettingsValidation.clampPort(5960))
    }

    @Test
    fun clampPort_clampsOutOfRange() {
        assertEquals(0, SettingsValidation.clampPort(-1))
        assertEquals(65535, SettingsValidation.clampPort(99999))
        assertEquals(65535, SettingsValidation.clampPort("99999"))
    }

    @Test
    fun clampPort_fromString() {
        assertEquals(0, SettingsValidation.clampPort(""))
        assertEquals(443, SettingsValidation.clampPort("443"))
        assertEquals(65535, SettingsValidation.clampPort("70000"))
    }

    @Test
    fun nonNegativeHelpers() {
        assertEquals(0L, SettingsValidation.nonNegativeLong(""))
        assertEquals(30_000L, SettingsValidation.nonNegativeLong("30000"))
        assertEquals(0, SettingsValidation.nonNegativeInt(""))
        assertEquals(64, SettingsValidation.nonNegativeInt("64"))
    }
}
