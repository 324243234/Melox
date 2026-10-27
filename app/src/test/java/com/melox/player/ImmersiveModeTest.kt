package com.melox.player

import com.melox.player.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveModeTest {
    @Test
    fun defaultsKeepTheExistingPlayerAndStatusBar() {
        val settings = AppSettings()
        assertFalse(settings.immersiveMode)
        assertFalse(settings.hideStatusBar)
        assertFalse(settings.statusBarHidden)
    }

    @Test
    fun leavingImmersionRetainsTheIndependentStatusBarAndLyricChoices() {
        val original = AppSettings(hideStatusBar = true, hideControlsOnLyrics = true)
        val immersive = original.copy(immersiveMode = true)
        assertTrue(immersive.statusBarHidden)
        val restored = immersive.copy(immersiveMode = false)
        assertTrue(restored.statusBarHidden)
        assertTrue(restored.hideStatusBar)
        assertTrue(restored.hideControlsOnLyrics)
    }

    @Test
    fun immersionKeepsTheStatusBarHiddenUntilBothPreferencesAreOff() {
        val immersive = AppSettings(immersiveMode = true, hideStatusBar = true)
        val withoutStatusBarPreference = immersive.copy(hideStatusBar = false)
        assertTrue(withoutStatusBarPreference.statusBarHidden)
        assertTrue(withoutStatusBarPreference.immersiveMode)
        assertFalse(withoutStatusBarPreference.copy(immersiveMode = false).statusBarHidden)
    }
}
