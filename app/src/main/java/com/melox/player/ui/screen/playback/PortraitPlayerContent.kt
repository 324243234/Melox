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
import androidx.compose.ui.unit.Dp

@Composable
internal fun PortraitPlayerLayout(
    spacing: PlayerVerticalSpacing,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
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
                .then(artworkModifier),
            contentAlignment = Alignment.Center,
        ) {
            artworkContent(maxHeight)
        }
        Spacer(Modifier.height(spacing.artworkToProgress))
        detailsContent()
    }
}
