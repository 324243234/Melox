package com.melox.player.ui.screen.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PortraitPlayerLayout(
    spacing: PlayerVerticalSpacing,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    detailsModifier: Modifier = Modifier,
    artworkBottomOverlap: Dp = 0.dp,
    artworkTopPadding: Dp = 0.dp,
    overlayDetails: Boolean = false,
    artworkContent: @Composable (Dp) -> Unit,
    detailsContent: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val top = artworkTopPadding.roundToPx().coerceIn(0, height)
        val details = subcompose("details") {
            Box(
                modifier = detailsModifier.padding(bottom = spacing.panelBottom),
                contentAlignment = Alignment.TopCenter,
            ) { detailsContent() }
        }.single().measure(Constraints.fixedWidth(width).copy(maxHeight = height))
        val artworkHeight = (height - top - details.height -
            spacing.artworkToProgress.roundToPx()).coerceAtLeast(0) + artworkBottomOverlap.roundToPx()
        val viewportHeight = if (overlayDetails) height else artworkHeight
        val artwork = subcompose("artwork") {
            Box(modifier = artworkModifier, contentAlignment = Alignment.Center) {
                // Keep the artwork page's original height when lyrics extend behind controls.
                artworkContent(artworkHeight.toDp())
            }
        }.single().measure(Constraints.fixed(width, viewportHeight))
        layout(width, height) {
            artwork.place(0, if (overlayDetails) 0 else top)
            details.place(0, height - details.height)
        }
    }
}
