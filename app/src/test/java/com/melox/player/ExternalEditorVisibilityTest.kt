package com.melox.player

import com.melox.player.model.AppSettings
import com.melox.player.ui.component.library.ExternalEditorKind
import com.melox.player.ui.component.library.visibleExternalEditors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEditorVisibilityTest {
    @Test
    fun defaultSettingsShowBothEditorsInTheExistingOrder() {
        val settings = AppSettings()
        assertTrue(settings.showMusicTagEditor)
        assertTrue(settings.showLyricoEditor)
        assertEquals(
            listOf(ExternalEditorKind.MusicTagEditor, ExternalEditorKind.Lyrico),
            visibleExternalEditors(settings.showMusicTagEditor, settings.showLyricoEditor),
        )
    }

    @Test
    fun hidingMusicTagEditorPreservesLyrico() {
        assertEquals(
            listOf(ExternalEditorKind.Lyrico),
            visibleExternalEditors(showMusicTagEditor = false, showLyricoEditor = true),
        )
    }

    @Test
    fun hidingLyricoPreservesMusicTagEditor() {
        assertEquals(
            listOf(ExternalEditorKind.MusicTagEditor),
            visibleExternalEditors(showMusicTagEditor = true, showLyricoEditor = false),
        )
    }

    @Test
    fun hidingBothEditorsRemovesBothEntries() {
        assertTrue(visibleExternalEditors(false, false).isEmpty())
    }

    @Test
    fun enablingEditorsAgainRestoresTheirOrder() {
        val settings = AppSettings(showMusicTagEditor = false, showLyricoEditor = false)
            .copy(showMusicTagEditor = true, showLyricoEditor = true)
        assertEquals(
            listOf(ExternalEditorKind.MusicTagEditor, ExternalEditorKind.Lyrico),
            visibleExternalEditors(settings.showMusicTagEditor, settings.showLyricoEditor),
        )
    }
}
