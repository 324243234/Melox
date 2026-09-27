package com.melox.player.ui.screen.playback

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import com.melox.player.R
import com.melox.player.data.library.ArtistGroup
import com.melox.player.data.library.displayArtistName
import com.melox.player.model.MusicTrack
import com.melox.player.model.LyricsUiState
import com.melox.player.model.LyricsDocument
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackBackgroundStyle
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackUiState
import com.melox.player.model.withTrackMetadata
import com.melox.player.ui.isMiuixWideLayout
import com.melox.player.ui.component.library.PlaybackArtworkFrame
import com.melox.player.ui.component.library.PLAYBACK_ARTWORK_SHADOW_BLUR_RADIUS
import com.melox.player.ui.component.library.TrackActionsOverlay
import com.melox.player.ui.component.library.formatDuration
import com.melox.player.ui.component.playback.BlurredArtworkBackground
import com.melox.player.ui.component.playback.DynamicFlowBackground
import com.melox.player.ui.component.playback.DynamicFlowBackgroundState
import com.melox.player.ui.component.playback.PLAYER_FULL_ARTWORK_CORNER_RADIUS
import com.melox.player.ui.component.playback.PLAYER_FULL_ARTWORK_REQUEST_SIZE
import com.melox.player.ui.component.playback.PLAYER_TRACK_ARTWORK_CROSSFADE_DURATION_MILLIS
import com.melox.player.ui.component.playback.PLAYER_TRACK_ARTWORK_CROSSFADE_EASING
import com.melox.player.ui.component.playback.rememberPlaybackArtworkResource
import com.melox.player.ui.component.playback.playerControlIconTransition
import com.melox.player.ui.component.playback.recordPlayerLayer
import com.melox.player.ui.component.playback.WeightedCrossfadeFrame
import com.melox.player.ui.component.playback.weightedCrossfadeFrames
import com.melox.player.ui.component.playback.rememberPlayerSheetVerticalDragModifier
import com.melox.player.ui.component.playback.artworkInsetRect
import com.melox.player.ui.component.playback.fittedArtworkRect
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun PlayerDetails(
    contentWidth: Dp,
    playback: PlaybackUiState,
    emphasisControlColor: Color,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviewPositionChange: (Long?) -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenPlaybackOptions: () -> Unit,
    onOpenLyricsSettings: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTrackActions: () -> Unit,
    modifier: Modifier = Modifier,
    primaryControlsWidth: Dp = contentWidth,
    secondaryControlsWidth: Dp = contentWidth,
    spacing: PlayerVerticalSpacing = PlayerVerticalSpacing(),
) {
    val progressIndicatorColor = emphasisControlColor.copy(alpha = 0.6f)
    val progressLabelColor = emphasisControlColor.copy(alpha = 0.6f)
    val secondaryControlColor = emphasisControlColor.copy(alpha = 0.6f)
    Box(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(contentWidth)
                    .zIndex(1f),
            ) {
                PlayerProgress(
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    enabled = playback.currentItem != null,
                    indicatorColor = progressIndicatorColor,
                    labelColor = progressLabelColor,
                    primaryControlSpacing = spacing.progressToPrimary,
                    onSeek = onSeek,
                    onPreviewPositionChange = onPreviewPositionChange,
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .width(primaryControlsWidth)
                    .height(PLAYER_PLAY_PAUSE_TOUCH_SIZE),
            ) {
                val layout = playerPrimaryControlLayout(maxWidth)
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = layout.previousTouchOffset),
                    minWidth = PLAYER_SIDE_CONTROL_TOUCH_SIZE,
                    minHeight = PLAYER_SIDE_CONTROL_TOUCH_SIZE,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_previous_track),
                        contentDescription = stringResource(R.string.previous_track),
                        modifier = Modifier.size(PLAYER_SIDE_CONTROL_ICON_SIZE),
                        tint = emphasisControlColor,
                    )
                }
                AnimatedPlayPauseButton(
                    playWhenReady = playback.playWhenReady,
                    tint = emphasisControlColor,
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = layout.playPauseTouchOffset),
                )
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = layout.nextTouchOffset),
                    minWidth = PLAYER_SIDE_CONTROL_TOUCH_SIZE,
                    minHeight = PLAYER_SIDE_CONTROL_TOUCH_SIZE,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_next_track),
                        contentDescription = stringResource(R.string.next_track),
                        modifier = Modifier.size(PLAYER_SIDE_CONTROL_ICON_SIZE),
                        tint = emphasisControlColor,
                    )
                }
            }
            Spacer(Modifier.height(spacing.controlGroup))
            Row(
                modifier = Modifier.width(secondaryControlsWidth),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackModeButton(
                    mode = playback.playbackMode,
                    tint = secondaryControlColor,
                    onClick = onCyclePlaybackMode,
                )
                IconButton(
                    onClick = onOpenLyricsSettings,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Tune,
                        contentDescription = stringResource(R.string.player_settings_open),
                        modifier = Modifier.size(24.dp),
                        tint = secondaryControlColor,
                    )
                }
                IconButton(onClick = onOpenPlaybackOptions) {
                    Icon(
                        imageVector = MiuixIcons.SearchDevice,
                        contentDescription = stringResource(R.string.playback_options_open),
                        tint = secondaryControlColor,
                    )
                }
                PlayerIconButton(
                    icon = MiuixIcons.Playlist,
                    description = stringResource(R.string.open_queue),
                    size = 24.dp,
                    tint = secondaryControlColor,
                    onClick = onOpenQueue,
                )
                PlayerIconButton(
                    icon = MiuixIcons.More,
                    description = stringResource(R.string.track_actions),
                    size = 23.dp,
                    tint = secondaryControlColor,
                    onClick = onOpenTrackActions,
                )
            }
        }
    }
}
@Composable
private fun AnimatedPlayPauseButton(
    playWhenReady: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        minWidth = PLAYER_PLAY_PAUSE_TOUCH_SIZE,
        minHeight = PLAYER_PLAY_PAUSE_TOUCH_SIZE,
    ) {
        AnimatedContent(
            targetState = playWhenReady,
            transitionSpec = { playerControlIconTransition() },
            label = "playPauseIcon",
        ) { playing ->
            Icon(
                painter = painterResource(
                    if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                ),
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                modifier = Modifier.size(PLAYER_PLAY_PAUSE_ICON_SIZE),
                tint = tint,
            )
        }
    }
}

@Composable
private fun PlaybackModeButton(
    mode: PlaybackMode,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
    ) {
        val (icon, description) = when (mode) {
            PlaybackMode.ORDER -> R.drawable.ic_player_repeat_all to
                R.string.playback_mode_order
            PlaybackMode.REPEAT_ONE -> R.drawable.ic_player_repeat_one to
                R.string.playback_mode_repeat_one
            PlaybackMode.RANDOM -> R.drawable.ic_player_shuffle to
                R.string.playback_mode_random
        }
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
    }
}

@Composable
private fun PlayerProgress(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    indicatorColor: Color,
    labelColor: Color,
    primaryControlSpacing: Dp,
    onSeek: (Long) -> Unit,
    onPreviewPositionChange: (Long?) -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val interactionEnabled = enabled && durationMs > 0L
    var seekPositionMs by remember { mutableFloatStateOf(positionMs.toFloat()) }
    var isSeeking by remember { mutableStateOf(false) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnPreviewPositionChange by rememberUpdatedState(onPreviewPositionChange)
    LaunchedEffect(positionMs, durationMs, isSeeking) {
        if (!isSeeking) {
            seekPositionMs = positionMs.coerceIn(0L, safeDuration).toFloat()
        }
    }
    val displayedPosition = if (isSeeking) {
        seekPositionMs
    } else {
        positionMs.coerceIn(0L, safeDuration).toFloat()
    }
    val progress = (displayedPosition / safeDuration.toFloat()).coerceIn(0f, 1f)
    val indicatorHeight by animateDpAsState(
        targetValue = if (isSeeking) 12.dp else PLAYER_PROGRESS_IDLE_HEIGHT,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 550f),
        label = "playerProgressPressedHeight",
    )
    val indicatorWidthExpansion by animateDpAsState(
        targetValue = if (isSeeking) 8.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 550f),
        label = "playerProgressPressedWidth",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(playerProgressLayoutHeight(primaryControlSpacing)),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(PLAYER_PROGRESS_TOUCH_HEIGHT)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    if (interactionEnabled) {
                        setProgress { targetProgress ->
                            seekPositionMs =
                                targetProgress.coerceIn(0f, 1f) * safeDuration.toFloat()
                            currentOnSeek(seekPositionMs.roundToLong())
                            true
                        }
                    }
                }
                .pointerInput(interactionEnabled, safeDuration) {
                    if (!interactionEnabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun updatePosition(horizontalPosition: Float): Long {
                            val fraction = if (size.width == 0) {
                                0f
                            } else {
                                (horizontalPosition / size.width).coerceIn(0f, 1f)
                            }
                            seekPositionMs = fraction * safeDuration.toFloat()
                            return seekPositionMs.roundToLong()
                        }
                        isSeeking = true
                        updatePosition(down.position.x)
                        down.consume()
                        var completed = false
                        var isDragging = false
                        try {
                            completed = drag(down.id) { change ->
                                val targetPositionMs = updatePosition(change.position.x)
                                if (
                                    !isDragging &&
                                        progressGestureIsDrag(
                                            horizontalDistancePx =
                                                change.position.x - down.position.x,
                                            touchSlopPx = viewConfiguration.touchSlop,
                                        )
                                ) {
                                    isDragging = true
                                }
                                if (isDragging) {
                                    currentOnPreviewPositionChange(targetPositionMs)
                                }
                                change.consume()
                            }
                            if (completed) currentOnSeek(seekPositionMs.roundToLong())
                        } finally {
                            if (!completed || !isDragging) {
                                currentOnPreviewPositionChange(null)
                            }
                            isSeeking = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            LinearProgressIndicator(
                progress = progress,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = if (enabled) {
                        indicatorColor
                    } else {
                        indicatorColor.copy(alpha = indicatorColor.alpha * 0.6f)
                    },
                    disabledForegroundColor =
                        indicatorColor.copy(alpha = indicatorColor.alpha * 0.5f),
                    backgroundColor =
                        indicatorColor.copy(alpha = 0.3f),
                ),
                height = indicatorHeight,
                modifier = Modifier
                    .width(maxWidth + indicatorWidthExpansion),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = PLAYER_PROGRESS_LABEL_OFFSET),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayedPosition.roundToLong()),
                style = MiuixTheme.textStyles.footnote1,
                color = labelColor,
            )
            Text(
                text = formatDuration(durationMs),
                style = MiuixTheme.textStyles.footnote1,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 26.dp,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(size),
            tint = tint,
        )
    }
}
