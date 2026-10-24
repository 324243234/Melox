package com.melox.player.ui.screen.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

@Composable
internal fun PortraitPlayerLayout(
    spacing: PlayerVerticalSpacing,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    artworkBottomOverlap: Dp = 0.dp,
    artworkContent: @Composable (Dp) -> Unit,
    detailsContent: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(bottom = spacing.panelBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (artworkBottomOverlap > 0.dp) Modifier.layout { measurable, constraints ->
                        val overlap = artworkBottomOverlap.roundToPx()
                        val placeable = measurable.measure(constraints.offset(vertical = overlap))
                        layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) {
                            placeable.place(0, 0)
                        }
                    } else Modifier,
                )
                .then(artworkModifier),
            contentAlignment = Alignment.Center,
        ) {
            artworkContent(maxHeight)
        }
        Spacer(Modifier.height(spacing.artworkToProgress))
        detailsContent()
    }
}
