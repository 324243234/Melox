package com.melox.player.ui.component.library

import androidx.compose.runtime.staticCompositionLocalOf
import com.melox.player.model.MusicTrack

internal data class TrackMutationActions(
    val hiddenContentUris: Set<String>,
    val onHide: (MusicTrack) -> Unit,
    val onRestore: (Set<String>) -> Unit,
    val onDelete: (MusicTrack) -> Unit,
)

internal val LocalTrackMutationActions = staticCompositionLocalOf<TrackMutationActions?> { null }
