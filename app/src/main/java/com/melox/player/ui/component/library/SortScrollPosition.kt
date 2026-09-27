package com.melox.player.ui.component.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

private class RenderedSort(var value: Any)

/** Preserve viewport coordinates only when the rendered ordering changes. */
@Composable
internal fun PreserveSortScrollPosition(
    sortKey: Any,
    query: String,
    itemCount: Int,
    scrollState: Any,
    layoutKey: Any = Unit,
    preservePosition: () -> Unit,
) {
    // Search, source-size and geometry changes establish a new baseline.
    val previous = remember(scrollState, query, itemCount, layoutKey) {
        RenderedSort(sortKey)
    }
    SideEffect {
        if (previous.value != sortKey) {
            preservePosition()
            previous.value = sortKey
        }
    }
}
