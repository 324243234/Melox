package com.melox.player.ui.component.library

internal fun offsetSectionIndices(indices: Map<String, Int>, leadingItemCount: Int): Map<String, Int> =
    if (leadingItemCount == 0) indices else indices.mapValues { (_, index) -> index + leadingItemCount }
