package com.melox.player.ui.screen.playback

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
import com.melox.player.model.LyricsTranslationLanguage
import com.melox.player.model.LyricAnimationMode
import com.melox.player.R
import com.melox.player.data.library.ArtistGroup
import com.melox.player.data.library.displayArtistName
import com.melox.player.model.MusicTrack
import com.melox.player.model.LyricsUiState
import com.melox.player.model.isDuetForMedia
import com.melox.player.model.LyricsDocument
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackBackgroundStyle
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackUiState
import com.melox.player.model.SleepTimerState
import com.melox.player.model.withTrackMetadata
import com.melox.player.ui.usesMiuixSmallTopAppBar
import com.melox.player.ui.screen.settings.LyricsInterfacePreferences
import com.melox.player.ui.screen.settings.LyricsTranslationPreferences
import com.melox.player.ui.isMiuixWideLayout
import com.melox.player.ui.component.library.PlaybackArtworkFrame
import com.melox.player.ui.component.library.PLAYBACK_ARTWORK_SHADOW_BLUR_RADIUS
import com.melox.player.ui.component.library.TrackActionsOverlay
import com.melox.player.ui.component.bottomSheetCardColor
import com.melox.player.ui.component.bottomSheetGlassModifier
import com.melox.player.ui.component.bottomSheetMaterialColor
import com.melox.player.ui.component.library.formatDuration
import com.melox.player.ui.component.playback.ArtworkTransitionState
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
import com.melox.player.ui.component.liquid.rememberCombinedBackdrop
import com.melox.player.ui.component.playback.artworkInsetRect
import com.melox.player.ui.component.playback.fittedArtworkRect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.NotesFill
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FullPlayerScreen(
    playback: PlaybackUiState,
    artworkState: ArtworkTransitionState<Bitmap>,
    currentTrack: MusicTrack?,
    lyrics: LyricsUiState,
    playbackBackgroundStyle: PlaybackBackgroundStyle,
    dynamicFlowBackgroundState: DynamicFlowBackgroundState,
    playbackBackgroundDark: Boolean,
    playbackContentColor: Color,
    dynamicColorEnabled: Boolean,
    lyricFontScale: Float,
    lyricFontWeight: Int,
    lyricAnimationMode: LyricAnimationMode,
    lyricBlurEnabled: Boolean,
    centerLyrics: Boolean,
    leftAlignPlayerTitle: Boolean,
    hideControlsOnLyrics: Boolean,
    immersiveMode: Boolean,
    onImmersiveModeChange: (Boolean) -> Unit,
    showLyricsTranslation: Boolean,
    showLyricsRomanization: Boolean,
    lyricsTranslationLanguage: LyricsTranslationLanguage,
    showArtworkLyrics: Boolean,
    onLyricFontScaleChange: (Float) -> Unit,
    onLyricFontWeightChange: (Int) -> Unit,
    onLyricAnimationModeChange: (LyricAnimationMode) -> Unit,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    onCenterLyricsChange: (Boolean) -> Unit,
    onLeftAlignPlayerTitleChange: (Boolean) -> Unit,
    onHideControlsOnLyricsChange: (Boolean) -> Unit,
    onShowLyricsTranslationChange: (Boolean) -> Unit,
    onShowLyricsRomanizationChange: (Boolean) -> Unit,
    onLyricsTranslationLanguageChange: (LyricsTranslationLanguage) -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Boolean,
    onNext: () -> Boolean,
    onSeek: (Long) -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    sleepTimerState: SleepTimerState,
    sleepTimerSeconds: Int,
    onSleepTimerSecondsChange: (Int) -> Unit,
    autoExtendSleepTimer: Boolean,
    onAutoExtendSleepTimerChange: (Boolean) -> Unit,
    playbackPauseFade: Boolean,
    onPlaybackPauseFadeChange: (Boolean) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onAcknowledgeSleepTimerInterruption: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlayNext: (MusicTrack) -> Unit,
    onAppendToQueue: (MusicTrack) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    onGoToAlbum: (MusicTrack) -> Unit,
    artistGroups: List<ArtistGroup>,
    onGoToArtist: (ArtistGroup) -> Unit,
    onExternalEditReturned: (Long) -> Unit,
    showMusicTagEditor: Boolean,
    showLyricoEditor: Boolean,
    showLunaBeatEditor: Boolean,
    backgroundLayer: GraphicsLayer,
    contentLayer: GraphicsLayer,
    frameRecordingGeneration: Int,
    interactionEnabled: Boolean,
    playerActive: Boolean,
    blockUnderlyingInput: Boolean,
    lyricsPagingEnabled: Boolean,
    drawInPlace: Boolean,
    recordInPlace: Boolean,
    sharedArtworkVisible: Boolean,
    initialArtworkPageSelected: Boolean,
    onPlayerDragStart: () -> Unit,
    onPlayerDrag: (Float) -> Unit,
    onPlayerDragEnd: (Float) -> Unit,
    onPlayerDragCancel: () -> Unit,
    onBackgroundLayerRecorded: (generation: Int, size: IntSize) -> Unit,
    onContentLayerRecorded: (generation: Int, size: IntSize) -> Unit,
    onPlayerBoundsChanged: (Rect) -> Unit,
    onArtworkBoundsChanged: (Rect) -> Unit,
    onArtworkDisplaySizeChanged: (Int) -> Unit,
    onArtworkPageSelectedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    circularArtwork: Boolean = false,
    squareArtwork: Boolean = false,
) {
    val item = playback.currentItem?.let { queueItem ->
        currentTrack?.let(queueItem::withTrackMetadata) ?: queueItem
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val artworkResource = rememberPlaybackArtworkResource(
        contentUri = item?.contentUri.orEmpty(),
        dateModifiedEpochSeconds = item?.dateModifiedEpochSeconds ?: 0L,
        fileSizeBytes = item?.fileSizeBytes ?: 0L,
        requestSize = PLAYER_FULL_ARTWORK_REQUEST_SIZE,
        prioritizeBlurredBackground =
            playbackBackgroundStyle == PlaybackBackgroundStyle.BLURRED_ARTWORK,
    )
    val artworkBlend = artworkState.toArtworkBlend()
    val emphasisControlColor = playbackContentColor
    val primaryControlAlpha = if (
        playbackBackgroundStyle == PlaybackBackgroundStyle.DYNAMIC_FLOW &&
        !playbackBackgroundDark && !dynamicColorEnabled
    ) 0.9f else null
    val activeLyricAlpha = if (primaryControlAlpha != null) 0.8f else 1f
    val controlColor = emphasisControlColor.copy(alpha = 0.6f)
    val artistControlColor = emphasisControlColor.copy(
        alpha = 0.6f,
    )
    val artworkCornerRadius = PLAYER_FULL_ARTWORK_CORNER_RADIUS
    var showTrackActions by remember { mutableStateOf(false) }
    var showLyricsSettings by remember { mutableStateOf(false) }
    var showAudioSettings by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showTranslationSettings by remember { mutableStateOf(false) }
    var showTimerInterruption by remember { mutableStateOf(false) }
    LaunchedEffect(sleepTimerState.interruptionNotice) {
        showTimerInterruption = sleepTimerState.interruptionNotice > 0
    }
    var displayedLyricFontScale by remember { mutableFloatStateOf(lyricFontScale) }
    var displayedLyricFontWeight by remember { mutableIntStateOf(lyricFontWeight) }
    var displayedLyricAnimationMode by remember {
        mutableStateOf(lyricAnimationMode)
    }
    var displayedLyricBlurEnabled by remember { mutableStateOf(lyricBlurEnabled) }
    var displayedCenterLyrics by remember { mutableStateOf(centerLyrics) }
    val centerLyricsEnabled = !lyrics.isDuetForMedia(playback.currentItem?.mediaId)
    var displayedLeftAlignPlayerTitle by remember {
        mutableStateOf(leftAlignPlayerTitle)
    }
    var displayedHideControlsOnLyrics by remember {
        mutableStateOf(hideControlsOnLyrics)
    }
    val hideLyricsControls = displayedHideControlsOnLyrics
    val onToggleImmersiveMode = {
        val enabled = !immersiveMode
        onImmersiveModeChange(enabled)
        if (enabled) {
            Toast.makeText(
                context,
                R.string.settings_immersive_mode_title,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    var displayedShowLyricsTranslation by remember {
        mutableStateOf(showLyricsTranslation)
    }
    val toggleLyricsTranslation = {
        displayedShowLyricsTranslation = !displayedShowLyricsTranslation
        onShowLyricsTranslationChange(displayedShowLyricsTranslation)
    }
    var displayedLyricsTranslationLanguage by remember {
        mutableStateOf(lyricsTranslationLanguage)
    }
    var displayedShowLyricsRomanization by remember {
        mutableStateOf(showLyricsRomanization)
    }
    var lyricsFollowRequestKey by remember { mutableIntStateOf(0) }
    var lyricsSeekRequestKey by remember { mutableIntStateOf(0) }
    var lyricsSeekPositionMs by remember { mutableLongStateOf(playback.positionMs) }
    var lyricsSeekPositionUpdateAnchorMs by remember { mutableLongStateOf(0L) }
    var lyricsPreviewPositionMs by remember { mutableStateOf<Long?>(null) }
    val artworkPadding by animateDpAsState(
        targetValue = if (playback.playWhenReady) {
            PLAYER_ARTWORK_PLAYING_PADDING
        } else {
            PLAYER_ARTWORK_PAUSED_PADDING
        },
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 200f,
        ),
        label = "playerArtworkPadding",
    )
    val playerHeaderTitleSlotHeight = with(density) {
        PLAYER_HEADER_TITLE_LINE_HEIGHT.toDp()
    }
    val playerHeaderArtistSlotHeight = with(density) {
        PLAYER_HEADER_ARTIST_LINE_HEIGHT.toDp()
    }
    val playerHeaderContentHeight = playerHeaderTitleSlotHeight +
        1.dp +
        playerHeaderArtistSlotHeight
    val layoutDirection = LocalLayoutDirection.current
    val playerSafeDrawingPadding = WindowInsets.systemBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .asPaddingValues()
    val playerSafeStart = playerSafeDrawingPadding.calculateStartPadding(layoutDirection)
    val playerSafeTop = playerSafeDrawingPadding.calculateTopPadding()
    val playerSafeEnd = playerSafeDrawingPadding.calculateEndPadding(layoutDirection)
    val playerSafeBottom = playerSafeDrawingPadding.calculateBottomPadding()
    val playerControlsBottomPadding = playerControlsBottomPadding(playerSafeBottom)
    val onTogglePlayPauseFromPlayer = {
        if (!playback.playWhenReady) lyricsFollowRequestKey += 1
        onTogglePlayPause()
    }
    val onSeekFromPlayer: (Long) -> Unit = { targetPositionMs ->
        lyricsSeekPositionMs = targetPositionMs
        lyricsSeekPositionUpdateAnchorMs = playback.positionUpdateElapsedRealtimeMs
        lyricsSeekRequestKey += 1
        lyricsPreviewPositionMs = null
        onSeek(targetPositionMs)
    }
    val onPreviewSeekFromPlayer: (Long?) -> Unit = { targetPositionMs ->
        lyricsPreviewPositionMs = targetPositionMs
    }
    LaunchedEffect(item?.contentUri, playback.playbackIteration) {
        lyricsPreviewPositionMs = null
        lyricsSeekRequestKey = 0
        lyricsSeekPositionMs = playback.positionMs
        lyricsSeekPositionUpdateAnchorMs = 0L
    }
    LaunchedEffect(
        lyricsSeekRequestKey,
        lyricsSeekPositionMs,
        lyricsSeekPositionUpdateAnchorMs,
        playback.positionMs,
        playback.positionUpdateElapsedRealtimeMs,
    ) {
        if (
            lyricSeekRequestIsAcknowledged(
                requestKey = lyricsSeekRequestKey,
                positionUpdateAnchorElapsedRealtimeMs = lyricsSeekPositionUpdateAnchorMs,
                positionUpdateElapsedRealtimeMs = playback.positionUpdateElapsedRealtimeMs,
                currentPositionMs = playback.positionMs,
                seekPositionMs = lyricsSeekPositionMs,
            )
        ) {
            lyricsSeekRequestKey = 0
        }
    }
    val pagerState = rememberPagerState(
        initialPage = if (initialArtworkPageSelected || item == null) 0 else 1,
        pageCount = { 2 },
    )

    // ====== 【保留本地：协程作用域，用于控制 Pager 翻页】 ======
    val coroutineScope = rememberCoroutineScope()

    // ====== 【保留本地：横向滑动切歌 + 单击切换面板 手势】 ======
    val artworkGestureModifier = Modifier
        .pointerInput(Unit) {
            var dragOffset = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragOffset = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount
                },
                onDragEnd = {
                    // 超过 60f 判定为有效切歌，防止误触
                    if (dragOffset < -60f) onNext()
                    else if (dragOffset > 60f) onPrevious()
                    dragOffset = 0f
                }
            )
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    // 单击封面时，平滑切换右侧面板 (0: 控制, 1: 歌词)
                    coroutineScope.launch {
                        val targetPage = if (pagerState.currentPage == 0) 1 else 0
                        pagerState.animateScrollToPage(targetPage)
                    }
                }
            )
        }

    LaunchedEffect(lyricFontScale) {
        displayedLyricFontScale = lyricFontScale
    }
    LaunchedEffect(lyricFontWeight) {
        displayedLyricFontWeight = lyricFontWeight
    }
    LaunchedEffect(lyricAnimationMode) {
        displayedLyricAnimationMode = lyricAnimationMode
    }
    LaunchedEffect(lyricBlurEnabled) {
        displayedLyricBlurEnabled = lyricBlurEnabled
    }
    LaunchedEffect(centerLyrics) {
        displayedCenterLyrics = centerLyrics
    }
    LaunchedEffect(leftAlignPlayerTitle) {
        displayedLeftAlignPlayerTitle = leftAlignPlayerTitle
    }
    LaunchedEffect(hideControlsOnLyrics) {
        displayedHideControlsOnLyrics = hideControlsOnLyrics
    }
    LaunchedEffect(immersiveMode) {
        lyricsPreviewPositionMs = null
    }
    LaunchedEffect(showLyricsTranslation) {
        displayedShowLyricsTranslation = showLyricsTranslation
    }
    LaunchedEffect(lyricsTranslationLanguage) {
        displayedLyricsTranslationLanguage = lyricsTranslationLanguage
    }
    LaunchedEffect(showLyricsRomanization) {
        displayedShowLyricsRomanization = showLyricsRomanization
    }
    val dismissGestureModifier = rememberPlayerSheetVerticalDragModifier(
        enabled = interactionEnabled,
        hasItem = true,
        onDragStart = onPlayerDragStart,
        onDrag = onPlayerDrag,
        onDragEnd = onPlayerDragEnd,
        onDragCancel = onPlayerDragCancel,
    )
    BackHandler(
        enabled = interactionEnabled,
        onBack = onDismiss,
    )
    val lyricActionsBackground = if (isRuntimeShaderSupported()) {
        rememberLayerBackdrop()
    } else null
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(interactionEnabled, blockUnderlyingInput) {
                if (interactionEnabled || !blockUnderlyingInput) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            .changes.forEach { it.consume() }
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                onPlayerBoundsChanged(
                    coordinates.findRootCoordinates().localBoundingBoxOf(
                        sourceCoordinates = coordinates,
                        clipBounds = false,
                    ),
                )
            },
        containerColor = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(dismissGestureModifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .recordPlayerLayer(
                        layer = backgroundLayer,
                        drawInPlace = drawInPlace,
                        recordingGeneration = frameRecordingGeneration,
                        recordInPlace = recordInPlace,
                        backdrop = lyricActionsBackground,
                        onRecorded = onBackgroundLayerRecorded,
                    ),
            ) {
                if (item == null) {
                    val emptyBackground = if (playbackBackgroundStyle == PlaybackBackgroundStyle.BLURRED_ARTWORK) {
                        if (playbackBackgroundDark) MiuixTheme.colorScheme.surface
                        else MiuixTheme.colorScheme.background
                    } else {
                        remember(playbackBackgroundDark) {
                            if (playbackBackgroundDark) darkColorScheme().surface
                            else lightColorScheme().background
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().background(emptyBackground),
                    )
                } else when (playbackBackgroundStyle) {
                    PlaybackBackgroundStyle.BLURRED_ARTWORK -> BlurredArtworkBackground(
                        resource = artworkResource,
                        animate = drawInPlace && playback.isPlaying,
                        animateArtworkTransition = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    PlaybackBackgroundStyle.DYNAMIC_FLOW -> DynamicFlowBackground(
                        state = dynamicFlowBackgroundState,
                        dark = playbackBackgroundDark,
                        artwork = artworkResource.artwork,
                        artworkLoading = artworkResource.isLoading,
                        backgroundColor = artworkResource.backgroundColor,
                        animate = drawInPlace && playback.isPlaying,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .recordPlayerLayer(
                        layer = contentLayer,
                        drawInPlace = drawInPlace,
                        recordingGeneration = frameRecordingGeneration,
                        recordInPlace = recordInPlace,
                        onRecorded = onContentLayerRecorded,
                    ),
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val safeContentWidth = (maxWidth - playerSafeStart - playerSafeEnd)
                        .coerceAtLeast(0.dp)
                    val safeContentHeight = (maxHeight - playerSafeTop - playerSafeBottom)
                        .coerceAtLeast(0.dp)
                    val playerLayout = playerLayout(
                        windowWidth = safeContentWidth,
                        windowHeight = safeContentHeight,
                    )
                    val headerTopPadding = playerHeaderTopPadding(playerLayout, playerSafeTop)
                    val lyricsActive = playerActive && playerLyricsAreActive(
                        layout = playerLayout,
                        currentPage = pagerState.currentPage,
                        targetPage = pagerState.targetPage,
                    )
                    SideEffect {
                        onArtworkPageSelectedChanged(
                            playerLayout != PlayerLayout.PORTRAIT || pagerState.settledPage == 0,
                        )
                    }
                    val portraitArtworkSize = playerArtworkSizeForContent(
                        playerContentWidth(
                            safeContentWidth,
                            PLAYER_PORTRAIT_CONTENT_MAX_WIDTH,
                        ),
                    )
                    val portraitArtworkContentWidth = playerArtworkAlignmentContentSize(
                        portraitArtworkSize,
                    )
                    val headerSpacing = playerVerticalSpacing(
                        availableHeight = maxHeight - playerHeaderContentHeight -
                            headerTopPadding,
                        preferredArtworkSize = portraitArtworkSize,
                    ).headerToArtwork
                    val pageHeight = maxHeight
                    val portraitHeaderHeight = headerTopPadding + playerHeaderContentHeight + headerSpacing
                    val portraitChromeAlpha = playerChromeAlpha(!immersiveMode)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = playerSafeStart,
                                end = playerSafeEnd,
                            ),
                    ) {
                    val lyricsCenterOffsetY = if (hideLyricsControls) {
                        hiddenLyricsCenterOffsetY(
                            pageHeight = pageHeight,
                            headerTopPadding = 0.dp,
                            headerContentHeight = 0.dp,
                            headerSpacing = 0.dp,
                        )
                    } else {
                        0.dp
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (playerLayout == PlayerLayout.WIDE_TWO_PANE) {
                            val playbackPaneWidth = landscapePlayerPlaybackPaneWidth(maxWidth)
                            val lyricsPaneWidth = landscapePlayerLyricsPaneWidth(maxWidth)
                            val paneSpacing = landscapePlayerPaneSpacing(maxWidth)
                            val artworkSize = landscapePlayerArtworkSize(
                                availableWidth = playbackPaneWidth,
                                availableHeight = maxHeight,
                            )
                            val artworkContentWidth = playerUnboundedContentWidth(playbackPaneWidth)
                            WidePlayerContent(
                                topPadding = headerTopPadding,
                                bottomPadding = playerControlsBottomPadding,
                                playbackPaneWidth = playbackPaneWidth,
                                lyricsPaneWidth = lyricsPaneWidth,
                                paneSpacing = paneSpacing,
                                playbackContent = {
                                    val spacing = landscapePlayerSpacing()
                                    PlayerHeader(
                                        item = item,
                                        trackChangeDirection = playback.trackChangeDirection,
                                        titleColor = emphasisControlColor,
                                        artistColor = artistControlColor,
                                        leftAligned = displayedLeftAlignPlayerTitle,
                                        titleSlotHeight = playerHeaderTitleSlotHeight,
                                        artistSlotHeight = playerHeaderArtistSlotHeight,
                                        modifier = Modifier
                                            .width(artworkContentWidth)
                                            .playerChromeVisibility(!immersiveMode),
                                    )
                                    Spacer(Modifier.height(LANDSCAPE_PLAYER_TITLE_TO_ARTWORK_SPACING))
                                    BoxWithConstraints(
                                        // 【修改这里】：结合宽屏状态下的手势
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(end = 48.dp)
                                            .then(artworkGestureModifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PlayerArtwork(
                                            circularArtwork = circularArtwork, squareArtwork = squareArtwork,
                                            showArtwork = item != null,
                                            size = fitPlayerArtworkSize(
                                                preferredSize = artworkSize,
                                                availableHeight = maxHeight,
                                            ),
                                            artworkPadding = artworkPadding,
                                            artworkBlend = artworkBlend,
                                            cornerRadius = artworkCornerRadius,
                                            sharedArtworkVisible = sharedArtworkVisible,
                                            onArtworkBoundsChanged = onArtworkBoundsChanged,
                                            onArtworkDisplaySizeChanged =
                                                onArtworkDisplaySizeChanged,
                                        )
                                    }
                                    Spacer(Modifier.height(LANDSCAPE_PLAYER_ARTWORK_TO_PROGRESS_SPACING))
                                    PlayerDetails(
                                        primaryControlAlpha = primaryControlAlpha,
                                        immersiveMode = immersiveMode,
                                        onToggleImmersiveMode = onToggleImmersiveMode,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .wrapContentHeight(),
                                        contentWidth = artworkContentWidth,
                                        spacing = spacing,
                                        playback = playback,
                                        emphasisControlColor = emphasisControlColor,
                                        onTogglePlayPause = onTogglePlayPauseFromPlayer,
                                        onPrevious = onPrevious,
                                        onNext = onNext,
                                        onSeek = onSeekFromPlayer,
                                        onPreviewPositionChange = onPreviewSeekFromPlayer,
                                        onCyclePlaybackMode = onCyclePlaybackMode,
                                        onOpenAudioSettings = { showAudioSettings = true },
                                        onOpenSleepTimer = { showSleepTimer = true },
                                        onOpenQueue = onOpenQueue,
                                        onOpenTrackActions = {
                                            if (currentTrack != null) showTrackActions = true
                                        },
                                        primaryControlsWidth = artworkContentWidth,
                                        secondaryControlsWidth = playbackPaneWidth,
                                    )
                                    Spacer(Modifier.height(spacing.panelBottom))
                                },
                                lyricsContent = {
                                    val lyricBackdrop = if (lyricActionsBackground != null) {
                                        rememberLayerBackdrop()
                                    } else null
                                    val actionBackdrop = if (lyricActionsBackground != null && lyricBackdrop != null) {
                                        rememberCombinedBackdrop(lyricActionsBackground, lyricBackdrop)
                                    } else null
                                    Box(Modifier.fillMaxSize()) {
                                        SyncedLyrics(
                                            activeLyricAlpha = activeLyricAlpha,
                                            lyrics = lyrics,
                                            positionMs = playback.positionMs,
                                            positionUpdateElapsedRealtimeMs =
                                                playback.positionUpdateElapsedRealtimeMs,
                                            playbackIteration = playback.playbackIteration,
                                            playbackSpeed = playback.playbackSpeed,
                                            previewPositionMs = lyricsPreviewPositionMs,
                                            isPlaying = playback.isPlaying,
                                            active = lyricsActive,
                                            playerActive = playerActive,
                                            positionDiscontinuityRevision = playback.positionDiscontinuityRevision,
                                            onSeek = onSeekFromPlayer,
                                            contentWidth = landscapePlayerLyricsContentWidth(lyricsPaneWidth),
                                            controlColor = controlColor,
                                            emphasisControlColor = emphasisControlColor,
                                            lyricFontScale = displayedLyricFontScale,
                                            lyricFontWeight = displayedLyricFontWeight,
                                            lyricAnimationMode = displayedLyricAnimationMode,
                                            lyricBlurEnabled = displayedLyricBlurEnabled,
                                            centerLyrics = displayedCenterLyrics,
                                            lyricCenterOffsetY = 0.dp,
                                            showLyricsTranslation = displayedShowLyricsTranslation,
                                            showLyricsRomanization = displayedShowLyricsRomanization,
                                            lyricsTranslationLanguage = displayedLyricsTranslationLanguage,
                                            showBottomFade = true,
                                            resumeFollowRequestKey = lyricsFollowRequestKey,
                                            seekRequestKey = lyricsSeekRequestKey,
                                            seekPositionMs = lyricsSeekPositionMs,
                                            // 【修改这里】：保留本地的 28dp 舒适留白
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp).then(
                                                if (lyricBackdrop != null) Modifier.layerBackdrop(lyricBackdrop)
                                                else Modifier,
                                            ),
                                        )
                                        PlayerLyricActionsRow(
                                            showLyricsTranslation = displayedShowLyricsTranslation,
                                            emphasisControlColor = emphasisControlColor,
                                            onOpenLyricsSettings = { showLyricsSettings = true },
                                            onToggleLyricsTranslation = toggleLyricsTranslation,
                                            onOpenTranslationSettings = { showTranslationSettings = true },
                                            enabled = !immersiveMode,
                                            blurBackdrop = actionBackdrop,
                                            modifier = Modifier.align(Alignment.BottomCenter)
                                                .fillMaxWidth().playerChromeVisibility(!immersiveMode),
                                        )
                                    }
                                },
                            )
                        } else if (playerLayout == PlayerLayout.COMPACT_LANDSCAPE) {
                            CompactLandscapePlayerLayout(
                                pagerState = pagerState,
                                lyricsPagingEnabled = lyricsPagingEnabled && item != null,
                                immersiveMode = immersiveMode,
                                artworkContent = { artworkSize ->
                                    // 【修改这里】：将点击与滑动手势附加在紧凑模式封面外层
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 24.dp)
                                            .then(artworkGestureModifier),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        PlayerArtwork(
                                            circularArtwork = circularArtwork, squareArtwork = squareArtwork,
                                            showArtwork = item != null,
                                            size = artworkSize,
                                            artworkPadding = artworkPadding,
                                            artworkBlend = artworkBlend,
                                            cornerRadius = artworkCornerRadius,
                                            sharedArtworkVisible = sharedArtworkVisible,
                                            onArtworkBoundsChanged = onArtworkBoundsChanged,
                                            onArtworkDisplaySizeChanged =
                                                onArtworkDisplaySizeChanged,
                                        )
                                    }
                                },
                                headerContent = { contentWidth ->
                                    PlayerHeader(
                                        item = item,
                                        trackChangeDirection = playback.trackChangeDirection,
                                        titleColor = emphasisControlColor,
                                        artistColor = artistControlColor,
                                        leftAligned = displayedLeftAlignPlayerTitle,
                                        titleSlotHeight = playerHeaderTitleSlotHeight,
                                        artistSlotHeight = playerHeaderArtistSlotHeight,
                                        modifier = Modifier
                                            .width(contentWidth)
                                            .playerChromeVisibility(!immersiveMode),
                                    )
                                },
                                lyricsToggleContent = { showLyrics, enabled, onToggle ->
                                    PlayerIconButton(
                                        icon = if (showLyrics) MiuixIcons.NotesFill else MiuixIcons.Notes,
                                        description = stringResource(
                                            if (showLyrics) R.string.player_show_controls
                                            else R.string.player_show_lyrics,
                                        ),
                                        onClick = onToggle,
                                        size = 24.dp,
                                        tint = emphasisControlColor.copy(alpha = 0.6f),
                                        enabled = enabled,
                                    )
                                },
                                lyricsContent = { paneWidth, contentWidth, contentHeight, lyricsVisible ->
                                    val lyricBackdrop = if (lyricActionsBackground != null) {
                                        rememberLayerBackdrop()
                                    } else null
                                    val actionBackdrop = if (lyricActionsBackground != null && lyricBackdrop != null) {
                                        rememberCombinedBackdrop(lyricActionsBackground, lyricBackdrop)
                                    } else null
                                    Box(
                                        modifier = Modifier
                                            .width(paneWidth)
                                            .height(contentHeight),
                                    ) {
                                        SyncedLyrics(
                                            activeLyricAlpha = activeLyricAlpha,
                                            lyrics = lyrics,
                                            positionMs = playback.positionMs,
                                            positionUpdateElapsedRealtimeMs =
                                                playback.positionUpdateElapsedRealtimeMs,
                                            playbackIteration = playback.playbackIteration,
                                            playbackSpeed = playback.playbackSpeed,
                                            previewPositionMs = lyricsPreviewPositionMs,
                                            isPlaying = playback.isPlaying,
                                            active = playerActive && lyricsVisible,
                                            playerActive = playerActive,
                                            positionDiscontinuityRevision = playback.positionDiscontinuityRevision,
                                            onSeek = onSeekFromPlayer,
                                            contentWidth = contentWidth,
                                            controlColor = controlColor,
                                            emphasisControlColor = emphasisControlColor,
                                            lyricFontScale = displayedLyricFontScale,
                                            lyricFontWeight = displayedLyricFontWeight,
                                            lyricAnimationMode = displayedLyricAnimationMode,
                                            lyricBlurEnabled = displayedLyricBlurEnabled,
                                            centerLyrics = displayedCenterLyrics,
                                            lyricCenterOffsetY = 0.dp,
                                            showLyricsTranslation = displayedShowLyricsTranslation,
                                            showLyricsRomanization = displayedShowLyricsRomanization,
                                            lyricsTranslationLanguage = displayedLyricsTranslationLanguage,
                                            showBottomFade = true,
                                            resumeFollowRequestKey = lyricsFollowRequestKey,
                                            seekRequestKey = lyricsSeekRequestKey,
                                            seekPositionMs = lyricsSeekPositionMs,
                                            // 【修改这里】：保留本地的 28dp 舒适留白
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 28.dp)
                                                .then(
                                                    if (lyricBackdrop != null) Modifier.layerBackdrop(lyricBackdrop)
                                                    else Modifier,
                                                ),
                                        )
                                        PlayerLyricActionsRow(
                                            showLyricsTranslation = displayedShowLyricsTranslation,
                                            emphasisControlColor = emphasisControlColor,
                                            onOpenLyricsSettings = { showLyricsSettings = true },
                                            onToggleLyricsTranslation = toggleLyricsTranslation,
                                            onOpenTranslationSettings = { showTranslationSettings = true },
                                            enabled = !immersiveMode,
                                            blurBackdrop = actionBackdrop,
                                            modifier = Modifier.align(Alignment.BottomCenter)
                                                .fillMaxWidth().playerChromeVisibility(!immersiveMode),
                                        )
                                    }
                                },
                                controlsContent = { paneWidth, contentWidth ->
                                    Column(
                                        modifier = Modifier
                                            .width(paneWidth)
                                            .wrapContentHeight(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        PlayerDetails(
                                            primaryControlAlpha = primaryControlAlpha,
                                            immersiveMode = immersiveMode,
                                            onToggleImmersiveMode = onToggleImmersiveMode,
                                            modifier = Modifier.fillMaxWidth(),
                                            contentWidth = contentWidth,
                                            spacing = compactLandscapePlayerSpacing(),
                                            playback = playback,
                                            emphasisControlColor = emphasisControlColor,
                                            onTogglePlayPause = onTogglePlayPauseFromPlayer,
                                            onPrevious = onPrevious,
                                            onNext = onNext,
                                            onSeek = onSeekFromPlayer,
                                            onPreviewPositionChange = onPreviewSeekFromPlayer,
                                            onCyclePlaybackMode = onCyclePlaybackMode,
                                            onOpenAudioSettings = { showAudioSettings = true },
                                            onOpenSleepTimer = { showSleepTimer = true },
                                            onOpenQueue = onOpenQueue,
                                            onOpenTrackActions = {
                                                if (currentTrack != null) showTrackActions = true
                                            },
                                            primaryControlsWidth = minOf(contentWidth, 320.dp),
                                            secondaryControlsWidth = paneWidth,
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = compactLandscapeSidePadding(
                                            ownSafeInset = playerSafeStart,
                                            oppositeSafeInset = playerSafeEnd,
                                        ),
                                        top = playerSafeTop,
                                        end = compactLandscapeSidePadding(
                                            ownSafeInset = playerSafeEnd,
                                            oppositeSafeInset = playerSafeStart,
                                        ),
                                        // Mirror the top safe space independently of navigation bars.
                                        bottom = playerSafeTop,
                                    ),
                            )
                        } else {
                            val contentWidth = playerContentWidth(
                                maxWidth, PLAYER_PORTRAIT_CONTENT_MAX_WIDTH,
                            )
                            val artworkSize = playerArtworkSizeForContent(contentWidth)
                            val artworkContentWidth = playerArtworkAlignmentContentSize(artworkSize)
                            val spacing = playerVerticalSpacing(
                                availableHeight = maxHeight,
                                preferredArtworkSize = artworkSize,
                                panelBottom = playerControlsBottomPadding,
                            )
                            if (hideLyricsControls) {
                                PlayerContentPager(
                                    activeLyricAlpha = activeLyricAlpha,
                                    pagerState = pagerState,
                                    lyrics = lyrics,
                                    positionMs = playback.positionMs,
                                    positionUpdateElapsedRealtimeMs =
                                        playback.positionUpdateElapsedRealtimeMs,
                                    playbackIteration = playback.playbackIteration,
                                    playbackSpeed = playback.playbackSpeed,
                                    previewPositionMs = lyricsPreviewPositionMs,
                                    isPlaying = playback.isPlaying,
                                    lyricsActive = lyricsActive,
                                    playerActive = playerActive,
                                    positionDiscontinuityRevision = playback.positionDiscontinuityRevision,
                                    onSeek = onSeekFromPlayer,
                                    contentWidth = artworkContentWidth,
                                    controlColor = controlColor,
                                    emphasisControlColor = emphasisControlColor,
                                    lyricsPagingEnabled = lyricsPagingEnabled,
                                    lyricFontScale = displayedLyricFontScale,
                                    lyricFontWeight = displayedLyricFontWeight,
                                    lyricAnimationMode = displayedLyricAnimationMode,
                                    lyricBlurEnabled = displayedLyricBlurEnabled,
                                    centerLyrics = displayedCenterLyrics,
                                    lyricCenterOffsetY = lyricsCenterOffsetY,
                                    showLyricsTranslation = displayedShowLyricsTranslation,
                                    showLyricsRomanization = displayedShowLyricsRomanization,
                                    lyricsTranslationLanguage = displayedLyricsTranslationLanguage,
                                    showBottomFade = false,
                                    resumeFollowRequestKey = lyricsFollowRequestKey,
                                    seekRequestKey = lyricsSeekRequestKey,
                                    seekPositionMs = lyricsSeekPositionMs,
                                    // 【修改这里】：保留本地竖屏 28dp 的舒适边距
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                                    lyricsBackground = lyricActionsBackground,
                                    lyricsViewportTopInset = portraitHeaderHeight,
                                    immersiveMode = immersiveMode,
                                    lyricsBottomContent = { blurBackdrop ->
                                        PlayerLyricActionsRow(
                                            showLyricsTranslation = displayedShowLyricsTranslation,
                                            emphasisControlColor = emphasisControlColor,
                                            onOpenLyricsSettings = { showLyricsSettings = true },
                                            onToggleLyricsTranslation = toggleLyricsTranslation,
                                            onOpenTranslationSettings = { showTranslationSettings = true },
                                            enabled = !immersiveMode,
                                            blurBackdrop = blurBackdrop,
                                            modifier = Modifier.fillMaxWidth()
                                                .then(if (!immersiveMode) dismissGestureModifier else Modifier)
                                                .padding(bottom = spacing.panelBottom)
                                                .playerChromeVisibility(!immersiveMode),
                                        )
                                    },
                                    artworkContent = { overscrollModifier ->
                                        PortraitPlayerLayout(
                                            spacing = spacing,
                                            artworkTopPadding = portraitHeaderHeight,
                                            artworkModifier = overscrollModifier,
                                            artworkContent = { _ ->
                                                PortraitArtworkWithLyrics(
                                                    activeLyricAlpha = activeLyricAlpha,
                                                    lyrics = lyrics,
                                                    playback = playback,
                                                    enabled = showArtworkLyrics,
                                                    lyricsVisible = !immersiveMode,
                                                    active = playerActive &&
                                                        (pagerState.currentPage == 0 || pagerState.targetPage == 0),
                                                    showTranslation = displayedShowLyricsTranslation,
                                                    showRomanization = displayedShowLyricsRomanization,
                                                    translationLanguage = displayedLyricsTranslationLanguage,
                                                    centerLyrics = displayedCenterLyrics,
                                                    animationMode = displayedLyricAnimationMode,
                                                    lyricFontWeight = displayedLyricFontWeight,
                                                    preferredArtworkSize = artworkSize,
                                                    progressGap = spacing.artworkToProgress +
                                                        PLAYER_PROGRESS_TOUCH_HEIGHT / 2f,
                                                    contentWidth = artworkContentWidth,
                                                    color = emphasisControlColor,
                                                    previewPositionMs = lyricsPreviewPositionMs
                                                        ?: lyricsSeekPositionMs.takeIf { lyricsSeekRequestKey != 0 },
                                                    modifier = Modifier.fillMaxSize(),
                                                    artworkContent = { captionArtworkHeight ->
                                                        PlayerArtwork(
                                                            circularArtwork = circularArtwork, squareArtwork = squareArtwork,
                                                            showArtwork = item != null,
                                                            size = fitPlayerArtworkSize(
                                                                artworkSize,
                                                                captionArtworkHeight,
                                                            ),
                                                            artworkPadding = artworkPadding,
                                                            artworkBlend = artworkBlend,
                                                            cornerRadius = artworkCornerRadius,
                                                            sharedArtworkVisible = sharedArtworkVisible,
                                                            onArtworkBoundsChanged = onArtworkBoundsChanged,
                                                            onArtworkDisplaySizeChanged =
                                                                onArtworkDisplaySizeChanged,
                                                        )
                                                    },
                                                )
                                            },
                                            detailsContent = {
                                                PlayerDetails(
                                                    primaryControlAlpha = primaryControlAlpha,
                                                    immersiveMode = immersiveMode,
                                                    onToggleImmersiveMode = onToggleImmersiveMode,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    contentWidth = artworkContentWidth,
                                                    secondaryControlsWidth = maxWidth,
                                                    spacing = spacing,
                                                    playback = playback,
                                                    emphasisControlColor = emphasisControlColor,
                                                    onTogglePlayPause =
                                                        onTogglePlayPauseFromPlayer,
                                                    onPrevious = onPrevious,
                                                    onNext = onNext,
                                                    onSeek = onSeekFromPlayer,
                                                    onPreviewPositionChange = onPreviewSeekFromPlayer,
                                                    onCyclePlaybackMode = onCyclePlaybackMode,
                                                    onOpenAudioSettings = { showAudioSettings = true },
                                                    onOpenSleepTimer = { showSleepTimer = true },
                                                    onOpenQueue = onOpenQueue,
                                                    onOpenTrackActions = {
                                                        if (currentTrack != null) {
                                                            showTrackActions = true
                                                        }
                                                    },
                                                )
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    },
                                )
                            } else {
                                val progressTrackInset =
                                    (PLAYER_PROGRESS_TOUCH_HEIGHT - PLAYER_PROGRESS_IDLE_HEIGHT) / 2f
                                val lyricBackdrop = if (lyricActionsBackground != null) {
                                    rememberLayerBackdrop()
                                } else null
                                val controlsBackdrop = if (lyricActionsBackground != null && lyricBackdrop != null) {
                                    rememberCombinedBackdrop(lyricActionsBackground, lyricBackdrop)
                                } else null
                                PortraitPlayerLayout(
                                    spacing = spacing.copy(artworkToProgress = 0.dp),
                                    artworkBottomOverlap = progressTrackInset,
                                    artworkTopPadding = portraitHeaderHeight,
                                    overlayDetails = true,
                                    detailsModifier = if (!immersiveMode) dismissGestureModifier else Modifier,
                                    artworkContent = { artworkHeight ->
                                        PlayerContentPager(
                                            activeLyricAlpha = activeLyricAlpha,
                                            pagerState = pagerState,
                                            lyrics = lyrics,
                                            positionMs = playback.positionMs,
                                            positionUpdateElapsedRealtimeMs =
                                                playback.positionUpdateElapsedRealtimeMs,
                                            playbackIteration = playback.playbackIteration,
                                            playbackSpeed = playback.playbackSpeed,
                                            previewPositionMs = lyricsPreviewPositionMs,
                                            isPlaying = playback.isPlaying,
                                            lyricsActive = lyricsActive,
                                            playerActive = playerActive,
                                            positionDiscontinuityRevision = playback.positionDiscontinuityRevision,
                                            onSeek = onSeekFromPlayer,
                                            contentWidth = artworkContentWidth,
                                            controlColor = controlColor,
                                            emphasisControlColor = emphasisControlColor,
                                            lyricsPagingEnabled = lyricsPagingEnabled,
                                            lyricFontScale = displayedLyricFontScale,
                                            lyricFontWeight = displayedLyricFontWeight,
                                            lyricAnimationMode =
                                                displayedLyricAnimationMode,
                                            lyricBlurEnabled = displayedLyricBlurEnabled,
                                            centerLyrics = displayedCenterLyrics,
                                            lyricCenterOffsetY =
                                                portraitHeaderHeight + (artworkHeight - maxHeight) / 2f,
                                            showLyricsTranslation =
                                                displayedShowLyricsTranslation,
                                            showLyricsRomanization =
                                                displayedShowLyricsRomanization,
                                            lyricsTranslationLanguage = displayedLyricsTranslationLanguage,
                                            showBottomFade = true,
                                            resumeFollowRequestKey = lyricsFollowRequestKey,
                                            seekRequestKey = lyricsSeekRequestKey,
                                            seekPositionMs = lyricsSeekPositionMs,
                                            // 【修改这里】：保留本地竖屏 28dp 的舒适边距
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                                            lyricsBackground = lyricActionsBackground,
                                            lyricsBackdrop = lyricBackdrop,
                                            lyricsViewportTopInset = portraitHeaderHeight,
                                            lyricsViewportBottomInset =
                                                (maxHeight - portraitHeaderHeight - artworkHeight).coerceAtLeast(0.dp),
                                            immersiveMode = immersiveMode,
                                            artworkAlignment = Alignment.TopCenter,
                                            lyricsBottomContent = { blurBackdrop ->
                                                PlayerLyricActionsRow(
                                                    showLyricsTranslation = displayedShowLyricsTranslation,
                                                    emphasisControlColor = emphasisControlColor,
                                                    onOpenLyricsSettings = { showLyricsSettings = true },
                                                    onToggleLyricsTranslation = toggleLyricsTranslation,
                                                    onOpenTranslationSettings = { showTranslationSettings = true },
                                                    enabled = !immersiveMode,
                                                    blurBackdrop = blurBackdrop,
                                                    modifier = Modifier.fillMaxWidth()
                                                        .then(if (!immersiveMode) dismissGestureModifier else Modifier)
                                                        .padding(bottom = PLAYER_PROGRESS_TIME_SPACING + 12.dp)
                                                        .playerChromeVisibility(!immersiveMode),
                                                )
                                            },
                                            artworkContent = { overscrollModifier ->
                                                PortraitArtworkWithLyrics(
                                                    activeLyricAlpha = activeLyricAlpha,
                                                    lyrics = lyrics,
                                                    playback = playback,
                                                    enabled = showArtworkLyrics,
                                                    lyricsVisible = !immersiveMode,
                                                    active = playerActive &&
                                                        (pagerState.currentPage == 0 || pagerState.targetPage == 0),
                                                    showTranslation = displayedShowLyricsTranslation,
                                                    showRomanization = displayedShowLyricsRomanization,
                                                    translationLanguage = displayedLyricsTranslationLanguage,
                                                    centerLyrics = displayedCenterLyrics,
                                                    animationMode = displayedLyricAnimationMode,
                                                    lyricFontWeight = displayedLyricFontWeight,
                                                    preferredArtworkSize = artworkSize,
                                                    progressGap = spacing.artworkToProgress +
                                                        PLAYER_PROGRESS_TOUCH_HEIGHT / 2f,
                                                    contentWidth = artworkContentWidth,
                                                    color = emphasisControlColor,
                                                    previewPositionMs = lyricsPreviewPositionMs
                                                        ?: lyricsSeekPositionMs.takeIf { lyricsSeekRequestKey != 0 },
                                                    modifier = Modifier
                                                        .padding(top = portraitHeaderHeight)
                                                        .fillMaxWidth().height(artworkHeight)
                                                        .padding(bottom = spacing.artworkToProgress + progressTrackInset)
                                                        .then(overscrollModifier),
                                                    artworkContent = { captionArtworkHeight ->
                                                        PlayerArtwork(
                                                            circularArtwork = circularArtwork, squareArtwork = squareArtwork,
                                                            showArtwork = item != null,
                                                            size = fitPlayerArtworkSize(
                                                                artworkSize,
                                                                captionArtworkHeight,
                                                            ),
                                                            artworkPadding = artworkPadding,
                                                            artworkBlend = artworkBlend,
                                                            cornerRadius = artworkCornerRadius,
                                                            sharedArtworkVisible = sharedArtworkVisible,
                                                            onArtworkBoundsChanged =
                                                                onArtworkBoundsChanged,
                                                            onArtworkDisplaySizeChanged =
                                                                onArtworkDisplaySizeChanged,
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                    detailsContent = {
                                        PlayerDetails(
                                            primaryControlAlpha = primaryControlAlpha,
                                            primaryControlsBackdrop = controlsBackdrop,
                                            primaryControlsBackdropAlpha = {
                                                if (immersiveMode || portraitChromeAlpha.value < 1f) {
                                                    (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                                                        .coerceIn(0f, 1f)
                                                } else 0f
                                            },
                                            immersiveMode = immersiveMode,
                                            onToggleImmersiveMode = onToggleImmersiveMode,
                                            modifier = Modifier.fillMaxWidth(),
                                            contentWidth = artworkContentWidth,
                                            secondaryControlsWidth = maxWidth,
                                            spacing = spacing,
                                            playback = playback,
                                            emphasisControlColor = emphasisControlColor,
                                            onTogglePlayPause = onTogglePlayPauseFromPlayer,
                                            onPrevious = onPrevious,
                                            onNext = onNext,
                                            onSeek = onSeekFromPlayer,
                                            onPreviewPositionChange = onPreviewSeekFromPlayer,
                                            onCyclePlaybackMode = onCyclePlaybackMode,
                                            onOpenAudioSettings = { showAudioSettings = true },
                                            onOpenSleepTimer = { showSleepTimer = true },
                                            onOpenQueue = onOpenQueue,
                                            onOpen