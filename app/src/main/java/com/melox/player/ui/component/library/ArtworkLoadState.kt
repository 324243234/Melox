package com.melox.player.ui.component.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ArtworkLoadResult<T>(val value: T)

@Composable
internal fun <T> rememberArtworkLoad(
    requestKey: Any,
    initialResult: ArtworkLoadResult<T>? = null,
    load: suspend () -> T,
): ArtworkLoadResult<T>? {
    val request = remember(requestKey) { Any() }
    val currentRequest by rememberUpdatedState(request)
    var result by remember(request) { mutableStateOf(initialResult) }
    LaunchedEffect(request) {
        val loaded = load()
        currentCoroutineContext().ensureActive()
        if (currentRequest === request) result = ArtworkLoadResult(loaded)
    }
    return result
}
