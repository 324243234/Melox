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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp

@Composable
internal fun CompactLandscapePlayerLayout(
    pagerState: PagerState,
    lyricsPagingEnabled: Boolean,
    artworkContent: @Composable (Dp) -> Unit,
    lyricsContent: @Composable (contentWidth: Dp, contentHeight: Dp) -> Unit,
    controlsContent: @Composable (paneWidth: Dp, contentWidth: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The caller removes safe drawing. Each pane gets half of that remaining
    // width. Lyrics plus the control header/progress/primary row follow the
    // portrait inset; the secondary function row spans the entire right pane.
    BoxWithConstraints(modifier = modifier) {
        val paneWidth = maxWidth / 2f
        val artworkSize = compactLandscapeArtworkSize(
            paneWidth = paneWidth,
            availableHeight = maxHeight,
        )
        val pageContentWidth = compactLandscapePaneContentWidth(paneWidth)
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .wrapContentHeight(unbounded = true),
                contentAlignment = Alignment.Center,
            ) {
                artworkContent(artworkSize)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = lyricsPagingEnabled,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    beyondViewportPageCount = 1,
                    verticalAlignment = Alignment.CenterVertically,
                    key = { it },
                ) { page ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (page == 0) {
                            controlsContent(paneWidth, pageContentWidth)
                        } else {
                            lyricsContent(pageContentWidth, artworkSize)
                        }
                    }
                }
            }
        }
    }
}
