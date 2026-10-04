package com.melox.player.ui.component.library

internal fun visibleExternalEditors(
    showMusicTagEditor: Boolean,
    showLyricoEditor: Boolean,
): List<ExternalEditorKind> = buildList {
    if (showMusicTagEditor) add(ExternalEditorKind.MusicTagEditor)
    if (showLyricoEditor) add(ExternalEditorKind.Lyrico)
}
