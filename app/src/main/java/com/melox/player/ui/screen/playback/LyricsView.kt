// SPDX-License-Identifier: Apache-2.0
package com.melox.player.ui.screen.playback

import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricsDocument
import java.util.IdentityHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LYRIC_EDGE_FADE_DP = 100f
private const val LYRIC_ANIMATION_BLEED_DP = 8f
internal const val LYRIC_INACTIVE_TEXT_ALPHA = 0.4f
internal const val LYRICS_MANUAL_FOLLOW_RESUME_DELAY_MS = 5_000L
private const val LYRIC_FOCUS_SCALE_IN_DURATION_MS = 600
private const val LYRIC_FOCUS_SCALE_OUT_DURATION_MS = 300
private const val LYRIC_FOCUS_ALPHA_ANIMATION_DURATION_MS = 180
private const val LYRIC_BLUR_ANIMATION_DURATION_MS = 300
internal const val LYRIC_PRIMARY_FONT_SIZE_SP = 24f
internal const val LYRIC_PRIMARY_LINE_HEIGHT_SP = 28f
internal const val LYRIC_TRANSLATION_FONT_SIZE_SP = 16f
internal const val LYRIC_TRANSLATION_LINE_HEIGHT_SP = 22f
private val LyricLayerPaint = Paint()

@Composable
internal fun rememberLyricFontFamily(weight: Int): FontFamily = remember(weight) {
    val mediumFontPath = "/storage/emulated/0/Documents/字体/SimplifiedChinese/SourceHanSansSC-Medium.otf"
    val boldFontPath = "/storage/emulated/0/Documents/字体/SimplifiedChinese/SourceHanSansSC-Bold.otf"

    // 将参数恢复为 Int 以解决编译报错，在内部根据 600 阈值判断加载哪个文件
    val isBold = weight >= 600
    val targetPath = if (isBold) boldFontPath else mediumFontPath
    val customTypeface = try {
        val file = java.io.File(targetPath)
        if (file.exists() && file.canRead()) {
            android.graphics.Typeface.createFromFile(file)
        } else {
            android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
        }
    } catch (e: Exception) {
        android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
    }

    androidx.compose.ui.text.font.FontFamily(customTypeface)
}

internal fun lyricEdgeFadeHeights(showBottomFade: Boolean): Pair<Float, Float> =
    LYRIC_EDGE_FADE_DP to if (showBottomFade) LYRIC_EDGE_FADE_DP else 0f

internal fun lyricCenterScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    centerOffsetPx: Float = 0f,
): Float = itemOffset + itemSize / 2f -
    ((viewportStartOffset + viewportEndOffset) / 2f + centerOffsetPx)

internal fun lyricTargetScrollOffset(
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    centerOffsetPx: Float = 0f,
): Int = -(
    (viewportStartOffset + viewportEndOffset) / 2f +
        centerOffsetPx -
        itemSize / 2f
    ).roundToInt()

internal fun lyricBlurRadiusTarget(
    lyricBlurEnabled: Boolean,
    distanceFromFocus: Int,
    isUserBrowsingLyrics: Boolean,
): Float = if (
    lyricBlurEnabled && distanceFromFocus > 0 && !isUserBrowsingLyrics
) {
    distanceFromFocus * 3f
} else {
    0f
}

internal fun lyricTranslationAlpha(lineAlpha: Float): Float = (
    LYRIC_INACTIVE_TEXT_ALPHA / lineAlpha.coerceAtLeast(LYRIC_INACTIVE_TEXT_ALPHA)
).coerceIn(0f, 1f)

internal fun lyricLineLayerAlpha(
    lineAlpha: Float,
    usesWordProgress: Boolean,
): Float = if (usesWordProgress) 1f else lineAlpha

internal fun lyricWordProgressActiveAlpha(
    lineAlpha: Float,
    usesWordProgress: Boolean,
): Float = if (usesWordProgress) lineAlpha else 1f

internal fun lyricSeekPositionIsApplied(
    currentPositionMs: Long,
    seekPositionMs: Long,
): Boolean = abs(currentPositionMs - seekPositionMs) <= 250L

internal fun lyricSeekRequestIsAcknowledged(
    requestKey: Int,
    positionUpdateAnchorElapsedRealtimeMs: Long,
    positionUpdateElapsedRealtimeMs: Long,
    currentPositionMs: Long,
    seekPositionMs: Long,
): Boolean {
    if (requestKey == 0) return false
    val hasFreshPosition =
        positionUpdateElapsedRealtimeMs > positionUpdateAnchorElapsedRealtimeMs
    return hasFreshPosition && lyricSeekPositionIsApplied(currentPositionMs, seekPositionMs)
}

internal fun lyricPlaybackPositionMs(
    positionMs: Long,
    positionUpdateElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
): Long {
    val basePositionMs = positionMs.coerceAtLeast(0L)
    if (!isPlaying || positionUpdateElapsedRealtimeMs <= 0L) return basePositionMs
    val elapsedMs = (nowElapsedRealtimeMs - positionUpdateElapsedRealtimeMs).coerceAtLeast(0L)
    val validSpeed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (basePositionMs + elapsedMs * validSpeed)
        .roundToLong()
        .coerceAtLeast(0L)
}

internal fun stabilizedLyricPlaybackPositionMs(
    previousPositionMs: Double,
    sampledPositionMs: Long,
    frameAdvanceNanos: Long,
    playbackSpeed: Float,
): Double {
    val validSpeed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
    val frameAdvancedPositionMs = previousPositionMs.coerceAtLeast(0.0) +
        frameAdvanceNanos.coerceAtLeast(0L) / 1_000_000.0 * validSpeed
    return maxOf(
        sampledPositionMs.coerceAtLeast(0L).toDouble(),
        frameAdvancedPositionMs,
    )
}

internal fun lyricSeekUsesAnimatedCentering(
    hasPositionedInitialFocus: Boolean,
    centerOffsetUnchanged: Boolean,
    isPreviewing: Boolean = false,
): Boolean = hasPositionedInitialFocus && centerOffsetUnchanged && !isPreviewing

internal fun lyricLineRenderPositionMs(
    lineIndex: Int,
    displayedPositionMs: Long,
    outgoingSeekLineIndex: Int,
    outgoingSeekPositionMs: Long,
): Long = if (lineIndex == outgoingSeekLineIndex) {
    outgoingSeekPositionMs
} else {
    displayedPositionMs
}

internal fun lyricOutgoingSeekCanClear(
    lineIndex: Int,
    outgoingSeekLineIndex: Int,
    currentLineIndex: Int,
    settledAlpha: Float,
): Boolean = lineIndex == outgoingSeekLineIndex &&
    currentLineIndex != lineIndex &&
    settledAlpha == LYRIC_INACTIVE_TEXT_ALPHA

internal fun lyricDisplayedPositionMs(
    previewPositionMs: Long?,
    seekRequestPending: Boolean,
    seekPositionMs: Long,
    smoothPositionMs: Long,
): Long = previewPositionMs?.coerceAtLeast(0L) ?: if (seekRequestPending) {
    seekPositionMs.coerceAtLeast(0L)
} else {
    smoothPositionMs.coerceAtLeast(0L)
}

internal fun lyricScrollIsManual(
    listIsScrolling: Boolean,
    scrollInCode: Boolean,
): Boolean = listIsScrolling && !scrollInCode

internal fun lyricVerticalDragExceedsTouchSlop(
    horizontalDeltaPx: Float,
    verticalDeltaPx: Float,
    touchSlopPx: Float,
): Boolean = abs(verticalDeltaPx) > touchSlopPx &&
    abs(verticalDeltaPx) > abs(horizontalDeltaPx)

internal fun lyricProgrammaticTranslationStart(
    currentTranslationY: Float,
    measuredScrollDelta: Float?,
    targetRenderIndex: Int,
    previousRenderIndex: Int,
    offscreenTravelPx: Float,
): Float = currentTranslationY + when {
    measuredScrollDelta != null -> measuredScrollDelta
    targetRenderIndex > previousRenderIndex -> offscreenTravelPx
    targetRenderIndex < previousRenderIndex -> -offscreenTravelPx
    else -> 0f
}

internal fun lyricRetainedTravelPx(
    translationStartPx: Float,
    velocityPxPerSecond: Float,
    stiffness: Float,
): Float = abs(translationStartPx) + abs(velocityPxPerSecond) / sqrt(stiffness.coerceAtLeast(1f))

internal fun lyricRetentionIsMeasured(
    viewportHeightPx: Int,
    reservePx: Int,
    measuredHeightPx: Int,
    beforePaddingPx: Int,
    afterPaddingPx: Int,
): Boolean = measuredHeightPx == viewportHeightPx + reservePx * 2 &&
    beforePaddingPx == viewportHeightPx / 2 + reservePx &&
    afterPaddingPx == beforePaddingPx

internal fun lyricActualPlacementTranslation(
    previousTranslationPx: Float,
    previousTargetOffsetPx: Int?,
    placedTargetOffsetPx: Int?,
    offscreenTranslationPx: Float,
): Float = if (previousTargetOffsetPx != null && placedTargetOffsetPx != null) {
    previousTranslationPx + previousTargetOffsetPx - placedTargetOffsetPx
} else {
    offscreenTranslationPx
}

internal fun lyricGlyphOutsetPx(textHeightPx: Float): Float =
    textHeightPx * 0.12f + 12f * 3f * 1.12f + LYRIC_FLOAT_MAX_OFFSET_PX

internal fun lyricCharacterPivot(position: Offset, width: Float, height: Float): Offset =
    Offset(position.x + width / 2f, position.y + height)

internal fun lyricOffscreenTranslationDistance(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    itemSize: Int,
): Float =
    (viewportEndOffset - viewportStartOffset).coerceAtLeast(0) / 2f +
        itemSize.coerceAtLeast(0) / 2f

internal fun lyricBlurShouldDisableForBrowsing(
    isUserBrowsingLyrics: Boolean,
    @Suppress("UNUSED_PARAMETER") isManualScrolling: Boolean,
): Boolean = isUserBrowsingLyrics

internal const val LYRIC_CENTERING_BASE_STIFFNESS = 80f
internal const val LYRIC_CENTERING_REFERENCE_INTERVAL_MS = 1_000L
internal const val LYRIC_CENTERING_MAX_STIFFNESS = 1_200f

internal fun lyricCenteringSpringStiffness(nextTimestampGapMs: Long?): Float {
    val gapMs = nextTimestampGapMs?.takeIf { it > 0L }
        ?: return LYRIC_CENTERING_BASE_STIFFNESS
    val speedRatio = (
        LYRIC_CENTERING_REFERENCE_INTERVAL_MS.toFloat() / gapMs.toFloat()
    ).coerceAtLeast(1f)
    return (
        LYRIC_CENTERING_BASE_STIFFNESS * speedRatio * speedRatio
    ).coerceAtMost(LYRIC_CENTERING_MAX_STIFFNESS)
}

@Suppress("UNUSED_PARAMETER")
internal fun lyricLineVerticalPaddingDp(
    hasTimedWords: Boolean,
    hasTranslation: Boolean,
    showLyricsTranslation: Boolean,
): Float {
    val translationVisible = hasTranslation && showLyricsTranslation
    return if (translationVisible) 12f else 16f
}

@Composable
private fun rememberSmoothLyricTimeProvider(
    document: LyricsDocument,
    positionMs: Long,
    positionUpdateElapsedRealtimeMs: Long,
    playbackIteration: Long,
    playbackSpeed: Float,
    isPlaying: Boolean,
    seekRequestKey: Int,
    seekPositionMs: Long,
): () -> Long {
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestPositionUpdateElapsedRealtimeMs by rememberUpdatedState(
        positionUpdateElapsedRealtimeMs,
    )
    val latestPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val latestSeekRequestKey by rememberUpdatedState(seekRequestKey)
    val latestSeekPositionMs by rememberUpdatedState(seekPositionMs)
    val clock = remember(document) { LyricPlaybackClock(positionMs, playbackIteration) }
    val smoothPositionMs = clock.positionMs
    val latestPlaybackIteration by rememberUpdatedState(playbackIteration)

    fun currentPlaybackPositionMs(): Long = lyricPlaybackPositionMs(
        positionMs = latestPositionMs,
        positionUpdateElapsedRealtimeMs = latestPositionUpdateElapsedRealtimeMs,
        nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        isPlaying = isPlaying,
        playbackSpeed = latestPlaybackSpeed,
    )

    LaunchedEffect(
        document,
        seekRequestKey,
        seekPositionMs,
        playbackIteration,
    ) {
        clock.resetForIteration(playbackIteration, currentPlaybackPositionMs())
        if (seekRequestKey != 0) {
            smoothPositionMs.doubleValue = seekPositionMs.coerceAtLeast(0L).toDouble()
        }
    }
    LaunchedEffect(document, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var previousFrameTimeNanos = 0L
        while (isActive) {
            val frameTimeNanos = withFrameNanos { it }
            val frameAdvanceNanos = if (previousFrameTimeNanos > 0L) {
                (frameTimeNanos - previousFrameTimeNanos).coerceAtLeast(0L)
            } else {
                0L
            }
            previousFrameTimeNanos = frameTimeNanos
            val iterationReset = clock.resetForIteration(
                latestPlaybackIteration, currentPlaybackPositionMs(),
            )
            smoothPositionMs.doubleValue = if (latestSeekRequestKey != 0) {
                latestSeekPositionMs.coerceAtLeast(0L).toDouble()
            } else if (iterationReset) {
                smoothPositionMs.doubleValue
            } else {
                stabilizedLyricPlaybackPositionMs(
                    previousPositionMs = smoothPositionMs.doubleValue,
                    sampledPositionMs = currentPlaybackPositionMs(),
                    frameAdvanceNanos = frameAdvanceNanos,
                    playbackSpeed = latestPlaybackSpeed,
                )
            }
        }
    }

    return remember(smoothPositionMs) { { smoothPositionMs.doubleValue.roundToLong() } }
}

@Composable
internal fun LyricsView(
    document: LyricsDocument,
    positionMs: Long,
    positionUpdateElapsedRealtimeMs: Long,
    playbackIteration: Long,
    playbackSpeed: Float,
    previewPositionMs: Long?,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    contentWidth: Dp,
    lyricFontScale: Float,
    lyricFontWeight: Int,
    forceWordByWordLyrics: Boolean,
    lyricBlurEnabled: Boolean,
    centerLyrics: Boolean,
    centerOffsetY: Dp = 0.dp,
    showLyricsTranslation: Boolean,
    showBottomFade: Boolean,
    resumeFollowRequestKey: Int,
    seekRequestKey: Int,
    seekPositionMs: Long,
    emphasisColor: Color,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentTimeProvider = rememberSmoothLyricTimeProvider(
        document = document,
        positionMs = positionMs,
        positionUpdateElapsedRealtimeMs = positionUpdateElapsedRealtimeMs,
        playbackIteration = playbackIteration,
        playbackSpeed = playbackSpeed,
        isPlaying = isPlaying,
        seekRequestKey = seekRequestKey,
        seekPositionMs = seekPositionMs,
    )
    val latestSeekRequestKey = rememberUpdatedState(seekRequestKey)
    val latestSeekPositionMs = rememberUpdatedState(seekPositionMs)
    val latestPreviewPositionMs = rememberUpdatedState(previewPositionMs)
    val lyricTimeProvider = remember(currentTimeProvider) {
        {
            lyricDisplayedPositionMs(
                previewPositionMs = latestPreviewPositionMs.value,
                seekRequestPending = latestSeekRequestKey.value != 0,
                seekPositionMs = latestSeekPositionMs.value,
                smoothPositionMs = currentTimeProvider(),
            )
        }
    }
    val density = LocalDensity.current
    val alignmentProgress = animateFloatAsState(
        targetValue = if (centerLyrics) 1f else 0f,
        animationSpec = playerTextAlignmentSpec(),
        label = "lyricsAlignment",
    )
    val viewConfiguration = LocalViewConfiguration.current
    val centerOffsetPx = with(density) { centerOffsetY.toPx() }
    val keepAliveZone = 100.dp
    val currentLineIndex by remember(document) {
        derivedStateOf { document.currentLineIndex(lyricTimeProvider()) }
    }
    val focusLineIndex by remember(document) {
        derivedStateOf { document.focusLineIndex(lyricTimeProvider()) }
    }
    var isUserBrowsingLyrics by remember(document) { mutableStateOf(false) }
    var outgoingSeekLineIndex by remember(document) { mutableIntStateOf(-1) }
    var outgoingSeekPositionMs by remember(document) { mutableLongStateOf(0L) }
    var pendingSeekCenteringTargetIndex by remember(document) { mutableIntStateOf(-1) }
    var pendingSeekCenteringDeltaPx by remember(document) { mutableStateOf<Float?>(null) }
    val isPreviewing = previewPositionMs != null
    val rowMotion = remember(document) { LyricRowMotion() }
    val translationMotion = remember(document) { LyricTranslationMotion(showLyricsTranslation) }
    var translationRevision by remember(document) { mutableIntStateOf(0) }
    var lyricTapRevision by remember(document) { mutableIntStateOf(0) }
    var retainedTravelPx by remember(document) { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember(document) { mutableIntStateOf(0) }
    var viewportWidthPx by remember(document) { mutableIntStateOf(0) }
    var lastCenteringGeometry by remember(document) { mutableStateOf<List<Any>?>(null) }
    var lastVisualFocusRenderIndex by remember(document) { mutableIntStateOf(-1) }
    val scrollInCode = remember { mutableStateOf(false) }
    val isManualScrolling by remember {
        derivedStateOf {
            lyricScrollIsManual(
                listIsScrolling = listState.isScrollInProgress,
                scrollInCode = scrollInCode.value,
            )
        }
    }
    var hasPositionedInitialFocus by remember(document) { mutableStateOf(false) }
    val centeringGeometry = listOf(
        centerOffsetY, viewportHeightPx, viewportWidthPx, contentWidth,
        lyricFontScale, lyricFontWeight,
        density.density, density.fontScale,
    )
    val visualFocusRenderIndex = focusLineIndex
    
    // 【核心修复】分离加载 Medium 和 Bold 字体文件，并改传 Int 以解决编译报错
    val mediumLyricFontFamily = rememberLyricFontFamily(weight = 400)
    val boldLyricFontFamily = rememberLyricFontFamily(weight = 700)
    
    // 普通未播放行：使用 Medium 文件，【关闭】 FontWeight 的伪加粗
    val normalTextStyle = MiuixTheme.textStyles.title3.copy(
        fontSize = (LYRIC_PRIMARY_FONT_SIZE_SP * lyricFontScale).sp,
        lineHeight = (LYRIC_PRIMARY_LINE_HEIGHT_SP * lyricFontScale).sp,
        fontFamily = mediumLyricFontFamily,
        fontWeight = FontWeight.Normal, // 强制 Normal，保持原汁原味
        fontSynthesis = FontSynthesis.None,
        textDirection = TextDirection.Content,
        textMotion = TextMotion.Animated,
    )
    
    // 当前播放行：使用 Bold 文件，【同样关闭】伪加粗
    val activeTextStyle = normalTextStyle.copy(
        fontFamily = boldLyricFontFamily,
        fontWeight = FontWeight.Normal // 强制 Normal，避免二次加粗导致发糊、过度膨胀
    )

    val translationTextStyle = MiuixTheme.textStyles.body1.copy(
        fontSize = (LYRIC_TRANSLATION_FONT_SIZE_SP * lyricFontScale).sp,
        lineHeight = (LYRIC_TRANSLATION_LINE_HEIGHT_SP * lyricFontScale).sp,
        fontFamily = mediumLyricFontFamily,
        fontWeight = FontWeight.Normal,
        fontSynthesis = FontSynthesis.None,
        textDirection = TextDirection.Content,
        textMotion = TextMotion.Animated,
    )
    
    val activeTranslationTextStyle = translationTextStyle.copy(
        fontFamily = boldLyricFontFamily,
        fontWeight = FontWeight.Normal
    )

    val lyricBrowsingModifier = Modifier.pointerInput(document) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Final,
            )
            var browsingStarted = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: break
                if (change.changedToUpIgnoreConsumed()) break
                if (
                    !browsingStarted &&
                        lyricVerticalDragExceedsTouchSlop(
                            horizontalDeltaPx = change.position.x - down.position.x,
                            verticalDeltaPx = change.position.y - down.position.y,
                            touchSlopPx = viewConfiguration.touchSlop,
                        )
                ) {
                    browsingStarted = true
                    isUserBrowsingLyrics = true
                }
            }
        }
    }
    LaunchedEffect(
        isManualScrolling,
        isUserBrowsingLyrics,
        isPlaying,
    ) {
        if (
            !isPlaying ||
                isManualScrolling ||
                !isUserBrowsingLyrics
        ) {
            return@LaunchedEffect
        }
        delay(LYRICS_MANUAL_FOLLOW_RESUME_DELAY_MS)
        if (isPlaying && !isManualScrolling && isUserBrowsingLyrics) {
            isUserBrowsingLyrics = false
        }
    }
    LaunchedEffect(playbackIteration) {
        isUserBrowsingLyrics = false
        outgoingSeekLineIndex = -1
        pendingSeekCenteringTargetIndex = -1
        pendingSeekCenteringDeltaPx = null
    }
    LaunchedEffect(resumeFollowRequestKey) {
        isUserBrowsingLyrics = false
    }
    LaunchedEffect(isPreviewing) {
        if (isPreviewing) {
            isUserBrowsingLyrics = false
        }
    }
    LaunchedEffect(seekRequestKey, seekPositionMs) {
        if (seekRequestKey > 0) {
            isUserBrowsingLyrics = false
        }
    }
    LaunchedEffect(document, showLyricsTranslation) {
        if (document.lines.none { it.translation != null }) return@LaunchedEffect
        translationMotion.transitionTo(showLyricsTranslation) {
            translationRevision++
        }
    }
    val centeringEvent = LyricCenteringEvent(
        focusIndex = visualFocusRenderIndex,
        requestedPositionMs = seekPositionMs,
        tapRevision = lyricTapRevision,
        browsing = isUserBrowsingLyrics,
        previewing = isPreviewing,
        geometry = centeringGeometry,
        translationRevision = translationRevision,
    )
    LaunchedEffect(document, centeringEvent) {
        if (viewportHeightPx == 0) return@LaunchedEffect
        if (visualFocusRenderIndex < 0) {
            hasPositionedInitialFocus = true
            return@LaunchedEffect
        }
        if (isUserBrowsingLyrics) {
            // Transfer the common visual displacement into native scrolling without a jump.
            val consumed = listState.dispatchRawDelta(-rowMotion.offset(lastVisualFocusRenderIndex))
            rowMotion.absorbScroll(consumed)
            retainedTravelPx = maxOf(retainedTravelPx, rowMotion.retainedTravel(LYRIC_CENTERING_BASE_STIFFNESS))
            snapshotFlow { isManualScrolling }.collectLatest { scrolling ->
                if (scrolling) {
                    rowMotion.absorbScroll(0f)
                } else {
                    // Give the gesture that enabled browsing a frame to acquire the list.
                    withFrameNanos { }
                    if (!isManualScrolling) {
                        rowMotion.settle(LYRIC_CENTERING_BASE_STIFFNESS)
                        retainedTravelPx = 0f
                    }
                }
            }
            return@LaunchedEffect
        }
        val layoutInfo = listState.layoutInfo
        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == visualFocusRenderIndex
        }
        val animateCentering = lyricSeekUsesAnimatedCentering(
            hasPositionedInitialFocus = hasPositionedInitialFocus,
            centerOffsetUnchanged = lastCenteringGeometry == centeringGeometry,
            isPreviewing = isPreviewing,
        )
        val centeringLine = document.lines.getOrNull(visualFocusRenderIndex)
        val nextTimestampGapMs = centeringLine?.let { line ->
            document.lines
                .getOrNull(visualFocusRenderIndex + 1)
                ?.startTimeMs
                ?.minus(line.startTimeMs)
        }
        val centeringSpringStiffness = lyricCenteringSpringStiffness(nextTimestampGapMs)
        val capturedSeekCenteringDelta = pendingSeekCenteringDeltaPx.takeIf {
            pendingSeekCenteringTargetIndex == visualFocusRenderIndex
        }
        val measuredScrollDelta = capturedSeekCenteringDelta ?: targetItem?.let { visibleTarget ->
            lyricCenterScrollDelta(
                itemOffset = visibleTarget.offset,
                itemSize = visibleTarget.size,
                viewportStartOffset = layoutInfo.viewportStartOffset,
                viewportEndOffset = layoutInfo.viewportEndOffset,
                centerOffsetPx = centerOffsetPx,
            )
        }
        val estimatedItemSize = layoutInfo.visibleItemsInfo
            .map { it.size }
            .average()
            .takeIf { !it.isNaN() }
            ?.roundToInt()
            ?: with(density) { normalTextStyle.lineHeight.toDp().roundToPx() }
        val offscreenCenteringTravelPx = lyricOffscreenTranslationDistance(
            viewportStartOffset = 0,
            viewportEndOffset = viewportHeightPx + with(density) { (keepAliveZone * 2).roundToPx() },
            itemSize = targetItem?.size ?: estimatedItemSize,
        )
        val previousRenderIndex = lastVisualFocusRenderIndex
            .takeIf { it >= 0 }
            ?: visualFocusRenderIndex
        val currentTranslationVelocity = rowMotion.velocity(visualFocusRenderIndex)
        val previousVisualOffsets = layoutInfo.visibleItemsInfo.associate { item ->
            item.index to item.offset + rowMotion.offset(item.index)
        }
        val previousViewportStart = layoutInfo.viewportStartOffset +
            with(density) { keepAliveZone.roundToPx() } + retainedTravelPx.roundToInt()
        val visibleRowOrder = lyricVisibleRowOrder(
            rows = layoutInfo.visibleItemsInfo.map { item ->
                LyricVisualRow(item.index, previousVisualOffsets.getValue(item.index), item.size)
            },
            viewportStart = previousViewportStart.toFloat(),
            viewportEnd = previousViewportStart + viewportHeightPx.toFloat(),
        )
        lastCenteringGeometry = centeringGeometry
        val previousTranslation = rowMotion.offset(visualFocusRenderIndex)
        val previousTargetOffset = targetItem?.offset
        val requestedTranslation = if (animateCentering) {
            val translationStart = lyricProgrammaticTranslationStart(
                currentTranslationY = previousTranslation,
                measuredScrollDelta = measuredScrollDelta,
                targetRenderIndex = visualFocusRenderIndex,
                previousRenderIndex = previousRenderIndex,
                offscreenTravelPx = offscreenCenteringTravelPx,
            )
            retainedTravelPx = maxOf(
                retainedTravelPx,
                rowMotion.retainedTravel(centeringSpringStiffness) + abs(translationStart - previousTranslation),
                lyricRetainedTravelPx(
                    translationStartPx = translationStart,
                    velocityPxPerSecond = currentTranslationVelocity,
                    stiffness = centeringSpringStiffness,
                ),
            )
            translationStart
        } else {
            retainedTravelPx = 0f
            0f
        }
        val reservePx = with(density) { keepAliveZone.roundToPx() } + retainedTravelPx.roundToInt()
        // scrollToItem can synchronously remeasure the child with its old constraints.
        // Wait until the outer viewport and both paddings have changed together first.
        snapshotFlow { listState.layoutInfo }.first { measured ->
            lyricRetentionIsMeasured(
                viewportHeightPx = viewportHeightPx,
                reservePx = reservePx,
                measuredHeightPx = measured.viewportSize.height,
                beforePaddingPx = measured.beforeContentPadding,
                afterPaddingPx = measured.afterContentPadding,
            )
        }
        try {
            scrollInCode.value = true
            val readyLayout = listState.layoutInfo
            val readyTarget = readyLayout.visibleItemsInfo.firstOrNull {
                it.index == visualFocusRenderIndex
            }
            listState.scrollToItem(
                index = visualFocusRenderIndex,
                scrollOffset = lyricTargetScrollOffset(
                    itemSize = readyTarget?.size ?: targetItem?.size ?: estimatedItemSize,
                    viewportStartOffset = readyLayout.viewportStartOffset,
                    viewportEndOffset = readyLayout.viewportEndOffset,
                    centerOffsetPx = centerOffsetPx,
                ),
            )
            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.index == visualFocusRenderIndex
            }?.let { centeredItem ->
                val centeredLayoutInfo = listState.layoutInfo
                val correction = lyricCenterScrollDelta(
                    itemOffset = centeredItem.offset,
                    itemSize = centeredItem.size,
                    viewportStartOffset = centeredLayoutInfo.viewportStartOffset,
                    viewportEndOffset = centeredLayoutInfo.viewportEndOffset,
                    centerOffsetPx = centerOffsetPx,
                )
                if (abs(correction) >= 0.5f) listState.scrollBy(correction)
            }
            val placedTargetOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.index == visualFocusRenderIndex
            }?.offset
            rowMotion.place(
                previousVisualOffsets = previousVisualOffsets,
                placedOffsets = listState.layoutInfo.visibleItemsInfo.associate { it.index to it.offset },
                fallbackTranslation = lyricActualPlacementTranslation(
                    previousTranslationPx = previousTranslation,
                    previousTargetOffsetPx = previousTargetOffset,
                    placedTargetOffsetPx = placedTargetOffset,
                    offscreenTranslationPx = requestedTranslation,
                ),
                fallbackVelocity = currentTranslationVelocity,
                animate = animateCentering,
            )
            hasPositionedInitialFocus = true
        } finally {
            scrollInCode.value = false
        }
        if (pendingSeekCenteringTargetIndex == visualFocusRenderIndex) {
            pendingSeekCenteringTargetIndex = -1
            pendingSeekCenteringDeltaPx = null
        }
        lastVisualFocusRenderIndex = visualFocusRenderIndex
        if (animateCentering) {
            rowMotion.settle(
                stiffness = centeringSpringStiffness,
                visibleOrder = visibleRowOrder,
                cascade = true,
            )
            retainedTravelPx = 0f
        }
    }

    BoxWithConstraints(modifier = modifier.onSizeChanged {
        viewportHeightPx = it.height
        viewportWidthPx = it.width
    }) {
        val animationBleed = LYRIC_ANIMATION_BLEED_DP.dp
        val horizontalPadding = (
            (maxWidth - contentWidth) / 2f - animationBleed
            ).coerceAtLeast(0.dp)
        val viewportHeight = maxHeight
        // Read retention during measurement so padding and viewport grow together before scrollBy.
        val listContentPadding = remember(document, horizontalPadding, viewportHeight, density) {
            object : PaddingValues {
                override fun calculateLeftPadding(layoutDirection: LayoutDirection) = horizontalPadding
                override fun calculateRightPadding(layoutDirection: LayoutDirection) = horizontalPadding
                override fun calculateTopPadding() = with(density) {
                    (viewportHeight.roundToPx() / 2 + keepAliveZone.roundToPx() +
                        retainedTravelPx.roundToInt()).toDp()
                }
                override fun calculateBottomPadding() = calculateTopPadding()
            }
        }
        val glyphOutset = with(density) {
            lyricGlyphOutsetPx(normalTextStyle.lineHeight.toPx()).toDp()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .then(lyricBrowsingModifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        val (topFadeDp, bottomFadeDp) = lyricEdgeFadeHeights(showBottomFade)
                        val topFade = (topFadeDp.dp.toPx() / size.height.coerceAtLeast(1f))
                            .coerceAtMost(0.5f)
                        val bottomFade =
                            (bottomFadeDp.dp.toPx() / size.height.coerceAtLeast(1f))
                                .coerceAtMost(0.5f)
                        val edgeMask = if (bottomFade > 0f) {
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                topFade to Color.Black,
                                1f - bottomFade to Color.Black,
                                1f to Color.Transparent,
                            )
                        } else {
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                topFade to Color.Black,
                                1f to Color.Black,
                            )
                        }
                        onDrawWithContent {
                            drawContent()
                            drawRect(
                                brush = edgeMask,
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
            ) {
                LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                .graphicsLayer {
                    alpha = if (hasPositionedInitialFocus) 1f else 0f
                }
                .layout { measurable, constraints ->
                        val keepAlivePx = keepAliveZone.roundToPx() + retainedTravelPx.roundToInt()
                        val extraHeightPx = keepAlivePx * 2
                        val placeable = measurable.measure(
                            constraints.copy(
                                maxHeight = constraints.maxHeight + extraHeightPx,
                            ),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(0, -keepAlivePx)
                        }
                    },
                contentPadding = listContentPadding,
            ) {
                itemsIndexed(
                    items = document.lines,
                    key = { index, line ->
                        "line-${line.startTimeMs}-${line.endTimeMs}-$index"
                    },
                ) { index, line ->
                    val distanceFromFocus = if (focusLineIndex >= 0) {
                        kotlin.math.abs(index - focusLineIndex)
                    } else {
                        0
                    }
                    val blurRadius by animateFloatAsState(
                        targetValue = lyricBlurRadiusTarget(
                            lyricBlurEnabled = lyricBlurEnabled,
                            distanceFromFocus = distanceFromFocus,
                            isUserBrowsingLyrics = lyricBlurShouldDisableForBrowsing(
                                isUserBrowsingLyrics = isUserBrowsingLyrics,
                                isManualScrolling = isManualScrolling,
                            ),
                        ),
                        animationSpec = tween(LYRIC_BLUR_ANIMATION_DURATION_MS),
                        label = "lyricBlurRadius",
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = rowMotion.offset(index) },
                    ) {
                        val isCurrentLine = index == currentLineIndex
                        val preservesOutgoingSeekProgress = index == outgoingSeekLineIndex
                        val usesWordProgress = line.words.isNotEmpty() || forceWordByWordLyrics
                        
                        val usePlaybackProgress = isCurrentLine || preservesOutgoingSeekProgress
                        val currentLineTextStyle = if (usePlaybackProgress) activeTextStyle else normalTextStyle
                        val currentLineTranslationStyle = if (usePlaybackProgress) activeTranslationTextStyle else translationTextStyle
                        
                        val lineTimeProvider = {
                            lyricLineRenderPositionMs(
                                lineIndex = index,
                                displayedPositionMs = lyricTimeProvider(),
                                outgoingSeekLineIndex = outgoingSeekLineIndex,
                                outgoingSeekPositionMs = outgoingSeekPositionMs,
                            )
                        }
                        LyricsLineItem(
                            isFocused = isCurrentLine,
                            usesWordProgress = usesWordProgress,
                            alignmentProgress = alignmentProgress,
                            blurRadius = blurRadius,
                            glyphOutset = glyphOutset,
                            onAlphaSettled = { settledAlpha ->
                                if (
                                    lyricOutgoingSeekCanClear(
                                        lineIndex = index,
                                        outgoingSeekLineIndex = outgoingSeekLineIndex,
                                        currentLineIndex = currentLineIndex,
                                        settledAlpha = settledAlpha,
                                    )
                                ) {
                                    outgoingSeekLineIndex = -1
                                }
                            },
                            onClick = {
                                val clickedItem = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                lyricTapRevision += 1
                                pendingSeekCenteringTargetIndex = index
                                pendingSeekCenteringDeltaPx = clickedItem?.let { visibleItem ->
                                    val layoutInfo = listState.layoutInfo
                                    lyricCenterScrollDelta(
                                        itemOffset = visibleItem.offset,
                                        itemSize = visibleItem.size,
                                        viewportStartOffset = layoutInfo.viewportStartOffset,
                                        viewportEndOffset = layoutInfo.viewportEndOffset,
                                        centerOffsetPx = centerOffsetPx,
                                    )
                                }
                                if (currentLineIndex >= 0 && currentLineIndex != index) {
                                    outgoingSeekLineIndex = currentLineIndex
                                    outgoingSeekPositionMs = lyricTimeProvider()
                                }
                                isUserBrowsingLyrics = false
                                onSeek(line.startTimeMs)
                            },
                        ) { wordProgressActiveAlpha, translationAlpha ->
                            if (line.words.isNotEmpty()) {
                                TimedLyricLine(
                                    line = line,
                                    usePlaybackProgress = usePlaybackProgress,
                                    translationAlpha = translationAlpha,
                                    activeAlpha = wordProgressActiveAlpha,
                                    currentTimeProvider = lineTimeProvider,
                                    normalTextStyle = currentLineTextStyle,
                                    translationTextStyle = currentLineTranslationStyle,
                                    emphasisColor = emphasisColor,
                                    alignmentProgress = alignmentProgress,
                                    translationMotion = translationMotion,
                                )
                            } else {
                                SyncedLyricLine(
                                    line = line,
                                    usePlaybackProgress = usePlaybackProgress,
                                    translationAlpha = translationAlpha,
                                    activeAlpha = wordProgressActiveAlpha,
                                    currentTimeProvider = lineTimeProvider,
                                    forceWordByWordLyrics = forceWordByWordLyrics,
                                    normalTextStyle = currentLineTextStyle,
                                    translationTextStyle = currentLineTranslationStyle,
                                    emphasisColor = emphasisColor,
                                    alignmentProgress = alignmentProgress,
                                    translationMotion = translationMotion,
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun LyricsLineItem(
    isFocused: Boolean,
    usesWordProgress: Boolean,
    alignmentProgress: State<Float>,
    blurRadius: Float,
    glyphOutset: Dp,
    onAlphaSettled: (Float) -> Unit,
    onClick: () -> Unit,
    content: @Composable (
        wordProgressActiveAlpha: Float,
        translationAlpha: Float,
    ) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.98f,
        animationSpec = if (isFocused) {
            tween(
                durationMillis = LYRIC_FOCUS_SCALE_IN_DURATION_MS,
                easing = LinearOutSlowInEasing,
            )
        } else {
            tween(
                durationMillis = LYRIC_FOCUS_SCALE_OUT_DURATION_MS,
                easing = EaseInOut,
            )
        },
        label = "lyricFocusScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else LYRIC_INACTIVE_TEXT_ALPHA,
        animationSpec = tween(
            durationMillis = LYRIC_FOCUS_ALPHA_ANIMATION_DURATION_MS,
            easing = LinearOutSlowInEasing,
        ),
        label = "lyricFocusAlpha",
        finishedListener = { settledAlpha ->
            onAlphaSettled(settledAlpha)
        },
    )
    val layerAlpha = lyricLineLayerAlpha(
        lineAlpha = alpha,
        usesWordProgress = usesWordProgress,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = layerAlpha
                transformOrigin = TransformOrigin(
                    pivotFractionX = alignmentProgress.value * 0.5f,
                    pivotFractionY = 1f,
                )
                compositingStrategy = CompositingStrategy.Offscreen
                outsets = LayerOutsets(all = glyphOutset + (blurRadius * 3f).toDp())
                if (blurRadius > 0f) {
                    renderEffect = BlurEffect(
                        radiusX = blurRadius,
                        radiusY = blurRadius,
                        edgeTreatment = TileMode.Clamp,
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LYRIC_ANIMATION_BLEED_DP.dp),
        ) {
            content(
                lyricWordProgressActiveAlpha(
                    lineAlpha = alpha,
                    usesWordProgress = usesWordProgress,
                ),
                lyricTranslationAlpha(layerAlpha),
            )
        }
    }
}

@Composable
private fun TranslationText(
    text: String,
    style: TextStyle,
    color: Color,
    alignmentProgress: State<Float>,
    motion: LyricTranslationMotion,
    modifier: Modifier = Modifier,
) {
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = TextAlign.Start,
        onTextLayout = { textLayout = it },
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val height = (placeable.height * motion.expansion.value.coerceIn(0f, 1f)).roundToInt()
                layout(placeable.width, constraints.constrainHeight(height)) {
                    placeable.place(0, 0)
                }
            }
            .animatedPlayerTextAlignment(textLayout, alignmentProgress)
            .graphicsLayer { alpha = motion.opacity.value }
            .then(if (motion.opacity.value == 0f) Modifier.clearAndSetSemantics { } else Modifier),
    )
}

@Composable
private fun TimedLyricLine(
    line: LyricLine,
    usePlaybackProgress: Boolean,
    translationAlpha: Float,
    activeAlpha: Float,
    currentTimeProvider: () -> Long,
    normalTextStyle: TextStyle,
    translationTextStyle: TextStyle,
    emphasisColor: Color,
    alignmentProgress: State<Float>,
    translationMotion: LyricTranslationMotion,
) {
    val textMeasurer = rememberTextMeasurer()
    val verticalPadding = lyricTranslationPaddingDp(
        hasTranslation = line.translation != null,
        expansion = translationMotion.expansion.value,
    ).dp - LYRIC_ANIMATION_BLEED_DP.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.Start,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val availableWidthPx = with(density) { maxWidth.toPx() }
            val syllables = remember(line.words) {
                line.words.map { word ->
                    Syllable(
                        startTimeMs = word.startTimeMs,
                        endTimeMs = word.endTimeMs,
                        content = word.text + if (word.hasTrailingSpace) " " else "",
                    )
                }
            }
            val spaceWidth = remember(textMeasurer, normalTextStyle) {
                textMeasurer.measure(" ", normalTextStyle).size.width.toFloat()
            }
            val measuredSyllables = remember(
                syllables,
                textMeasurer,
                normalTextStyle,
                spaceWidth,
                availableWidthPx,
            ) {
                measureSyllables(
                    syllables = syllables,
                    textMeasurer = textMeasurer,
                    style = normalTextStyle,
                    spaceWidth = spaceWidth,
                    availableWidthPx = availableWidthPx,
                )
            }
            val wrappedLines = remember(
                measuredSyllables,
                availableWidthPx,
                textMeasurer,
                normalTextStyle,
            ) {
                calculateWrappedLines(
                    syllableLayouts = measuredSyllables,
                    availableWidthPx = availableWidthPx,
                    textMeasurer = textMeasurer,
                    style = normalTextStyle,
                )
            }
            val lineHeight = remember(textMeasurer, normalTextStyle) {
                textMeasurer.measure("M", normalTextStyle).size.height.toFloat()
            }
            val positionedLines = remember(
                wrappedLines,
                availableWidthPx,
                lineHeight,
            ) {
                calculateStaticLineLayout(
                    wrappedLines = wrappedLines,
                    lineHeight = lineHeight,
                )
            }
            val rowRenderData = remember(positionedLines) {
                calculateRowRenderData(positionedLines)
            }
            val animationBleedPx = with(density) { LYRIC_ANIMATION_BLEED_DP.dp.toPx() }
            val totalHeight = (lineHeight * wrappedLines.size).roundToInt() + 8

            val latestTimeProvider = rememberUpdatedState(currentTimeProvider)
            val latestActiveAlpha = rememberUpdatedState(activeAlpha)
            val staticCompleted by remember(line, latestTimeProvider) {
                derivedStateOf { latestTimeProvider.value() >= line.endTimeMs }
            }
            val drawTimedLyrics: CacheDrawScope.() -> DrawResult = remember(
                rowRenderData, emphasisColor, animationBleedPx, usePlaybackProgress, staticCompleted,
            ) {
                {
                    val glyphGlows = IdentityHashMap<SyllableLayout, List<CachedGlyphGlow?>>()
                    if (usePlaybackProgress) {
                        rowRenderData.forEach { row ->
                            row.layouts.filter { it.useWordAnimation }.forEach { layout ->
                                val duration = layout.animationSource.endTimeMs -
                                    layout.animationSource.startTimeMs
                                val radius = wordMotion(0.5f, duration).glowRadius
                                val padding = lyricGlyphOutsetPx(layout.textLayoutResult.size.height.toFloat())
                                glyphGlows[layout] = layout.characterLayouts.orEmpty().mapIndexed { index, glyph ->
                                    if (layout.syllable.content.getOrNull(index)?.isWhitespace() != false) {
                                        null
                                    } else {
                                        val layer = obtainGraphicsLayer()
                                        layer.record(
                                            size = IntSize(
                                                glyph.size.width + (padding * 2).roundToInt(),
                                                glyph.size.height + (padding * 2).roundToInt(),
                                            ),
                                        ) {
                                            drawText(
                                                textLayoutResult = glyph,
                                                color = emphasisColor,
                                                topLeft = Offset(padding, padding),
                                                shadow = Shadow(emphasisColor, Offset.Zero, radius),
                                            )
                                        }
                                        CachedGlyphGlow(layer, padding)
                                    }
                                }
                            }
                        }
                    }
                    onDrawBehind {
                        withTransform({ translate(top = animationBleedPx) }) {
                            drawLyricsLine(
                                rows = rowRenderData,
                                canvasWidth = size.width,
                                alignmentProgress = alignmentProgress,
                                positionMs = if (usePlaybackProgress) {
                                    latestTimeProvider.value()
                                } else if (staticCompleted) {
                                    line.endTimeMs
                                } else {
                                    line.startTimeMs
                                },
                                color = emphasisColor,
                                activeAlpha = latestActiveAlpha.value,
                                glyphGlows = glyphGlows,
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(
                        width = maxWidth,
                        height = with(density) {
                            (totalHeight.toFloat() + animationBleedPx * 2f).toDp()
                        },
                    )
                    .drawWithCache(drawTimedLyrics),
            )
        }
        line.translation?.let { translation ->
            TranslationText(
                text = translation,
                style = translationTextStyle,
                color = emphasisColor.copy(alpha = translationAlpha),
                alignmentProgress = alignmentProgress,
                motion = translationMotion,
            )
        }
    }
}

@Composable
private fun SyncedLyricLine(
    line: LyricLine,
    usePlaybackProgress: Boolean,
    translationAlpha: Float,
    activeAlpha: Float,
    currentTimeProvider: () -> Long,
    forceWordByWordLyrics: Boolean,
    normalTextStyle: TextStyle,
    translationTextStyle: TextStyle,
    emphasisColor: Color,
    alignmentProgress: State<Float>,
    translationMotion: LyricTranslationMotion,
) {
    var textLayout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val verticalPadding = lyricTranslationPaddingDp(
        hasTranslation = line.translation != null,
        expansion = translationMotion.expansion.value,
    ).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.Start,
    ) {
        if (usePlaybackProgress && forceWordByWordLyrics) {
            ForcedLyricLineText(
                text = line.displayText,
                startTimeMs = line.startTimeMs,
                endTimeMs = line.endTimeMs,
                currentTimeProvider = currentTimeProvider,
                textStyle = normalTextStyle,
                color = emphasisColor,
                activeAlpha = activeAlpha,
                alignmentProgress = alignmentProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = line.displayText,
                modifier = Modifier.fillMaxWidth()
                    .animatedPlayerTextAlignment(textLayout, alignmentProgress),
                style = normalTextStyle,
                color = if (forceWordByWordLyrics) {
                    emphasisColor.copy(
                        alpha = emphasisColor.alpha * LYRIC_INACTIVE_TEXT_ALPHA,
                    )
                } else {
                    emphasisColor
                },
                textAlign = TextAlign.Start,
                onTextLayout = { textLayout = it },
            )
        }
        line.translation?.let { translation ->
            TranslationText(
                text = translation,
                style = translationTextStyle,
                color = emphasisColor.copy(alpha = translationAlpha),
                alignmentProgress = alignmentProgress,
                motion = translationMotion,
            )
        }
    }
}

@Composable
private fun ForcedLyricLineText(
    text: String,
    startTimeMs: Long,
    endTimeMs: Long,
    currentTimeProvider: () -> Long,
    textStyle: TextStyle,
    color: Color,
    activeAlpha: Float,
    alignmentProgress: State<Float>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.roundToPx() }
        val measuredStyle = remember(textStyle) {
            textStyle.copy(textAlign = TextAlign.Start)
        }
        val textLayoutResult = remember(
            text,
            measuredStyle,
            availableWidthPx,
            textMeasurer,
        ) {
            textMeasurer.measure(
                text = text,
                style = measuredStyle,
                constraints = Constraints(
                    minWidth = availableWidthPx,
                    maxWidth = availableWidthPx,
                ),
            )
        }
        val rowWidths = remember(textLayoutResult) {
            List(textLayoutResult.lineCount.coerceAtLeast(1)) { lineIndex ->
                (
                    textLayoutResult.getLineRight(lineIndex) -
                        textLayoutResult.getLineLeft(lineIndex)
                    ).coerceAtLeast(0f)
            }
        }
        Canvas(
            modifier = Modifier.size(
                width = maxWidth,
                height = with(density) { textLayoutResult.size.height.toDp() },
            ),
        ) {
            val lineProgress = lyricIntervalProgress(
                positionMs = currentTimeProvider(),
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
            )
            repeat(textLayoutResult.lineCount) { lineIndex ->
                val offset = playerTextAlignmentOffset(
                    lineLeft = textLayoutResult.getLineLeft(lineIndex),
                    lineRight = textLayoutResult.getLineRight(lineIndex),
                    containerWidth = size.width,
                    progress = alignmentProgress.value,
                )
                withTransform({ translate(left = offset) }) {
                    clipRect(
                        left = -size.width,
                        top = textLayoutResult.getLineTop(lineIndex),
                        right = size.width * 2f,
                        bottom = textLayoutResult.getLineBottom(lineIndex),
                    ) {
                        drawLyricForeground(
                            textLayoutResult = textLayoutResult,
                            color = color.copy(alpha = color.alpha * LYRIC_INACTIVE_TEXT_ALPHA),
                        )
                        drawForcedLyricRow(
                            textLayoutResult = textLayoutResult,
                            rowWidths = rowWidths,
                            rowIndex = lineIndex,
                            lineProgress = lineProgress,
                            color = color.copy(alpha = color.alpha * activeAlpha),
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawForcedLyricRow(
    textLayoutResult: TextLayoutResult,
    rowWidths: List<Float>,
    rowIndex: Int,
    lineProgress: Float,
    color: Color,
) {
    val rowProgress = forcedLyricRowProgress(
        lineProgress = lineProgress,
        rowIndex = rowIndex,
        rowWidths = rowWidths,
    )
    if (rowProgress <= 0f) return

    val left = textLayoutResult.getLineLeft(rowIndex)
    val right = textLayoutResult.getLineRight(rowIndex)
    val top = textLayoutResult.getLineTop(rowIndex)
    val bottom = textLayoutResult.getLineBottom(rowIndex)
    val rowWidth = right - left
    if (rowWidth <= 0f || bottom <= top) return
    val rowBounds = Rect(left, top, right, bottom)

    drawIntoCanvas { canvas ->
        canvas.saveLayer(rowBounds, LyricLayerPaint)
        drawLyricForeground(
            textLayoutResult = textLayoutResult,
            color = color,
        )
        if (rowProgress < 1f) {
            val fadeRange = (100f / rowWidth).coerceAtMost(1f)
            val fadeCenter = -fadeRange / 2f + (1f + fadeRange) * rowProgress
            val fadeStart = (fadeCenter - fadeRange / 2f).coerceIn(0f, 1f)
            val fadeEnd = (fadeCenter + fadeRange / 2f).coerceIn(0f, 1f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.White,
                        fadeStart to Color.White,
                        fadeEnd to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    startX = left,
                    endX = right,
                ),
                topLeft = rowBounds.topLeft,
                size = rowBounds.size,
                blendMode = BlendMode.DstIn,
            )
        }
        canvas.restore()
    }
}

internal fun forcedLyricRowProgress(
    lineProgress: Float,
    rowIndex: Int,
    rowWidths: List<Float>,
): Float {
    if (rowIndex !in rowWidths.indices) return 0f
    var totalWidth = 0f
    var widthBeforeRow = 0f
    rowWidths.forEachIndexed { index, width ->
        val normalizedWidth = width.coerceAtLeast(0f)
        totalWidth += normalizedWidth
        if (index < rowIndex) widthBeforeRow += normalizedWidth
    }
    if (totalWidth <= 0f) return 0f
    val completedWidth = lineProgress.coerceIn(0f, 1f) * totalWidth
    val rowWidth = rowWidths[rowIndex].coerceAtLeast(0f)
    if (rowWidth <= 0f) return if (completedWidth > widthBeforeRow) 1f else 0f
    return ((completedWidth - widthBeforeRow) / rowWidth).coerceIn(0f, 1f)
}

private data class Syllable(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val content: String,
)

private data class SyllableLayout(
    val syllable: Syllable,
    val textLayoutResult: TextLayoutResult,
    val useWordAnimation: Boolean,
    val animationSource: Syllable = syllable,
    val characterOffset: Int = 0,
    val width: Float = textLayoutResult.size.width.toFloat(),
    val lineEndWidth: Float = width,
    val position: Offset = Offset.Zero,
    val characterLayouts: List<TextLayoutResult>? = null,
    val characterOriginalBounds: List<Rect>? = null,
    val firstBaseline: Float = textLayoutResult.firstBaseline,
)

private data class WrappedLine(
    val syllables: List<SyllableLayout>,
    val totalWidth: Float,
)

private data class CachedGlyphGlow(val layer: GraphicsLayer, val paddingPx: Float)

private data class RowRenderData(
    val layouts: List<SyllableLayout>,
    val minX: Float,
    val maxX: Float,
    val width: Float,
    val firstStartTimeMs: Long,
    val lastEndTimeMs: Long,
    val layerBounds: Rect,
)

private fun measureSyllables(
    syllables: List<Syllable>,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    spaceWidth: Float,
    availableWidthPx: Float,
): List<SyllableLayout> = syllables.flatMap { source ->
    val chunks = lyricTextChunks(source.content, availableWidthPx) {
        textMeasurer.measure(it, style).size.width.toFloat()
    }
    chunks.map { range ->
        val duration = source.endTimeMs - source.startTimeMs
        val syllable = Syllable(
            startTimeMs = source.startTimeMs + duration * range.first / source.content.length,
            endTimeMs = source.startTimeMs + duration * (range.last + 1) / source.content.length,
            content = source.content.substring(range),
        )
        val layoutResult = textMeasurer.measure(syllable.content, style)
        var layoutWidth = layoutResult.size.width.toFloat()
        if (syllable.content.endsWith(" ")) {
            val trimmedContent = syllable.content.trimEnd()
            val trimmedWidth = textMeasurer.measure(trimmedContent, style).size.width.toFloat()
            val spaceCount = syllable.content.length - trimmedContent.length
            layoutWidth = maxOf(layoutWidth, trimmedWidth + spaceWidth * spaceCount)
        }
        val useWordAnimation = shouldUseWordAnimation(
            content = source.content,
            durationMs = source.endTimeMs - source.startTimeMs,
        )
        val animateCharacters = !source.content.needsJoinedText()
        SyllableLayout(
            syllable = syllable,
            textLayoutResult = layoutResult,
            useWordAnimation = useWordAnimation,
            animationSource = source,
            characterOffset = range.first,
            width = layoutWidth,
            lineEndWidth = textMeasurer.measure(syllable.content.trimEnd(), style).size.width.toFloat(),
            characterLayouts = if (animateCharacters) {
                syllable.content.map { textMeasurer.measure(it.toString(), style) }
            } else {
                null
            },
            characterOriginalBounds = if (animateCharacters) {
                syllable.content.indices.map(layoutResult::getBoundingBox)
            } else {
                null
            },
        )
    }
}

internal fun shouldUseWordAnimation(
    content: String,
    durationMs: Long,
): Boolean {
    if (content.isEmpty() || durationMs < 1_000L) return false
    val perCharacterDurationMs = durationMs.toFloat() / content.length
    return perCharacterDurationMs > 200f && !content.shouldUseSimpleAnimation()
}

internal const val LYRIC_FLOAT_MAX_OFFSET_PX = 4f

private fun String.shouldUseSimpleAnimation(): Boolean {
    val visibleCharacters = filterNot { character ->
        character.isWhitespace() || character.isPunctuation()
    }
    if (visibleCharacters.isEmpty()) return false
    return visibleCharacters.all(Char::isCjk) || needsJoinedText()
}

private fun String.needsJoinedText(): Boolean = any {
    it.isArabic() || it.isDevanagari() || it.isSurrogate() ||
        Character.getType(it) == Character.NON_SPACING_MARK.toInt()
}

private fun Char.isCjk(): Boolean = code in 0x3400..0x4DBF ||
    code in 0x4E00..0x9FFF ||
    code in 0xF900..0xFAFF

private fun Char.isArabic(): Boolean = code in 0x0600..0x06FF ||
    code in 0x0750..0x077F ||
    code in 0x08A0..0x08FF

private fun Char.isDevanagari(): Boolean = code in 0x0900..0x097F

private fun Char.isPunctuation(): Boolean = when (Character.getType(this)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    -> true
    else -> false
}

private fun calculateWrappedLines(
    syllableLayouts: List<SyllableLayout>,
    availableWidthPx: Float,
    textMeasurer: TextMeasurer,
    style: TextStyle,
): List<WrappedLine> = lyricWrapRanges(
    contents = syllableLayouts.map { it.syllable.content },
    widths = syllableLayouts.map { it.width },
    availableWidthPx = availableWidthPx,
    lineEndWidths = syllableLayouts.map { it.lineEndWidth },
).map { range ->
    trimLineTrailingSpaces(syllableLayouts.subList(range.first, range.last + 1), textMeasurer, style)
}

private fun trimLineTrailingSpaces(
    layouts: List<SyllableLayout>,
    textMeasurer: TextMeasurer,
    style: TextStyle,
): WrappedLine {
    if (layouts.isEmpty()) return WrappedLine(emptyList(), 0f)
    val processed = layouts.toMutableList()
    while (processed.lastOrNull()?.syllable?.content?.isBlank() == true) {
        processed.removeAt(processed.lastIndex)
    }
    if (processed.isEmpty()) return WrappedLine(emptyList(), 0f)

    val lastLayout = processed.last()
    val trimmedContent = lastLayout.syllable.content.trimEnd()
    if (trimmedContent.length < lastLayout.syllable.content.length) {
        if (trimmedContent.isEmpty()) {
            processed.removeAt(processed.lastIndex)
        } else {
            val trimmedResult = textMeasurer.measure(trimmedContent, style)
            processed[processed.lastIndex] = lastLayout.copy(
                syllable = lastLayout.syllable.copy(content = trimmedContent),
                textLayoutResult = trimmedResult,
                width = trimmedResult.size.width.toFloat(),
            )
        }
    }
    return WrappedLine(
        syllables = processed,
        totalWidth = processed.sumOf { it.width.toDouble() }.toFloat(),
    )
}

private fun calculateStaticLineLayout(
    wrappedLines: List<WrappedLine>,
    lineHeight: Float,
): List<List<SyllableLayout>> = wrappedLines.mapIndexed { lineIndex, wrappedLine ->
    val maxBaseline = wrappedLine.syllables.maxOfOrNull { it.firstBaseline } ?: 0f
    var currentX = 0f
    wrappedLine.syllables.map { initialLayout ->
        val positioned = initialLayout.copy(
            position = Offset(
                x = currentX,
                y = lineIndex * lineHeight + maxBaseline - initialLayout.firstBaseline,
            ),
        )
        currentX += initialLayout.width
        positioned
    }
}

private fun calculateRowRenderData(
    lineLayouts: List<List<SyllableLayout>>,
): List<RowRenderData> = lineLayouts.mapNotNull { layouts ->
    if (layouts.isEmpty()) return@mapNotNull null
    val minX = layouts.minOf { it.position.x }
    val maxX = layouts.maxOf { it.position.x + it.width }
    val width = maxX - minX
    val minY = layouts.minOf { it.position.y }
    val height = layouts.maxOf { it.textLayoutResult.size.height }.toFloat()
    val drawingOutset = lyricGlyphOutsetPx(height)
    RowRenderData(
        layouts = layouts,
        minX = minX,
        maxX = maxX,
        width = width,
        firstStartTimeMs = layouts.minOf { it.animationSource.startTimeMs },
        lastEndTimeMs = layouts.last().syllable.endTimeMs,
        layerBounds = Rect(
            left = minX - drawingOutset,
            top = minY - drawingOutset,
            right = maxX + drawingOutset,
            bottom = minY + height + drawingOutset,
        ),
    )
}

private fun DrawScope.drawLyricsLine(
    rows: List<RowRenderData>,
    canvasWidth: Float,
    alignmentProgress: State<Float>,
    positionMs: Long,
    color: Color,
    activeAlpha: Float,
    glyphGlows: Map<SyllableLayout, List<CachedGlyphGlow?>>,
) {
    val motionStrength = lyricLineMotionStrength(activeAlpha)
    rows.forEach { row ->
        val offset = playerTextAlignmentOffset(
            lineLeft = row.minX,
            lineRight = row.maxX,
            containerWidth = canvasWidth,
            progress = alignmentProgress.value,
        )
        withTransform({ translate(left = offset) }) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(row.layerBounds, LyricLayerPaint)
                drawRowText(row.layouts, color, positionMs, glyphGlows, motionStrength)
                drawRect(
                    brush = createLineGradient(row, positionMs, activeAlpha),
                    topLeft = row.layerBounds.topLeft,
                    size = row.layerBounds.size,
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}

internal fun DrawScope.drawLyricForeground(
    textLayoutResult: TextLayoutResult,
    color: Color,
    topLeft: Offset = Offset.Zero,
) {
    drawText(
        textLayoutResult = textLayoutResult,
        color = color,
        topLeft = topLeft,
        shadow = Shadow.None,
    )
}

internal fun lyricLineMotionStrength(activeAlpha: Float): Float =
    ((activeAlpha - LYRIC_INACTIVE_TEXT_ALPHA) / (1f - LYRIC_INACTIVE_TEXT_ALPHA)).coerceIn(0f, 1f)

internal fun WordMotion.withLineStrength(strength: Float): WordMotion {
    val amount = strength.coerceIn(0f, 1f)
    return copy(
        scale = 1f + (scale - 1f) * amount,
        offsetYPx = offsetYPx * amount,
        glowAlpha = glowAlpha * amount,
    )
}

private fun createLineGradient(
    row: RowRenderData,
    positionMs: Long,
    activeAlpha: Float,
): Brush {
    val activeColor = Color.White.copy(alpha = activeAlpha)
    val inactiveColor = Color.White.copy(alpha = LYRIC_INACTIVE_TEXT_ALPHA)
    if (row.width <= 0f) {
        return Brush.horizontalGradient(
            listOf(
                if (positionMs >= row.lastEndTimeMs) activeColor else inactiveColor,
                if (positionMs >= row.lastEndTimeMs) activeColor else inactiveColor,
            ),
        )
    }
    if (positionMs <= row.firstStartTimeMs) {
        return Brush.horizontalGradient(listOf(inactiveColor, inactiveColor))
    }
    if (positionMs >= row.lastEndTimeMs) {
        return Brush.horizontalGradient(listOf(activeColor, activeColor))
    }

    val activeLayout = row.layouts.firstOrNull { layout ->
        positionMs >= layout.syllable.startTimeMs &&
            positionMs < layout.syllable.endTimeMs
    }
    val currentPixelPosition = if (activeLayout != null) {
        activeLayout.position.x + activeLayout.width * lyricIntervalProgress(
            positionMs = positionMs,
            startTimeMs = activeLayout.syllable.startTimeMs,
            endTimeMs = activeLayout.syllable.endTimeMs,
        )
    } else {
        row.layouts.lastOrNull { positionMs >= it.syllable.endTimeMs }
            ?.let { it.position.x + it.width }
            ?: row.minX
    }
    val lineProgress = ((currentPixelPosition - row.minX) / row.width).coerceIn(0f, 1f)
    val fadeRange = (100f / row.width).coerceAtMost(1f)
    val fadeCenter = -fadeRange / 2f + (1f + fadeRange) * lineProgress
    val fadeStart = (fadeCenter - fadeRange / 2f).coerceIn(0f, 1f)
    val fadeEnd = (fadeCenter + fadeRange / 2f).coerceIn(0f, 1f)
    return Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to activeColor,
            fadeStart to activeColor,
            fadeEnd to inactiveColor,
            1f to inactiveColor,
        ),
        startX = row.minX,
        endX = row.maxX,
    )
}

private fun DrawScope.drawRowText(
    layouts: List<SyllableLayout>,
    color: Color,
    positionMs: Long,
    glyphGlows: Map<SyllableLayout, List<CachedGlyphGlow?>>,
    motionStrength: Float,
) {
    layouts.forEachIndexed { index, layout ->
        if (layout.characterLayouts != null) {
            val characterLayouts = layout.characterLayouts.orEmpty()
            val characterBounds = layout.characterOriginalBounds.orEmpty()
            val source = layout.animationSource
            val characterCount = source.content.length
            layout.syllable.content.forEachIndexed { characterIndex, _ ->
                val characterLayout = characterLayouts.getOrNull(characterIndex)
                    ?: return@forEachIndexed
                val characterBox = characterBounds.getOrNull(characterIndex)
                    ?: return@forEachIndexed
                val absoluteCharacterIndex = layout.characterOffset + characterIndex
                val motion = (if (layout.useWordAnimation &&
                    !layout.syllable.content[characterIndex].isWhitespace()
                ) {
                    characterMotion(
                        positionMs = positionMs,
                        wordStartTimeMs = source.startTimeMs,
                        wordEndTimeMs = source.endTimeMs,
                        characterIndex = absoluteCharacterIndex,
                        characterCount = characterCount,
                    )
                } else {
                    WordMotion(
                        scale = 1f,
                        offsetYPx = lyricCharacterFloatOffset(
                            positionMs, source.startTimeMs, source.endTimeMs,
                            absoluteCharacterIndex, characterCount,
                        ),
                        glowRadius = 0f,
                    )
                }).withLineStrength(motionStrength)
                val centeredOffsetX =
                    (characterBox.width - characterLayout.size.width) / 2f
                val position = Offset(
                    x = layout.position.x + characterBox.left + centeredOffsetX,
                    y = layout.position.y + layout.firstBaseline - characterLayout.firstBaseline +
                        motion.offsetYPx,
                )
                withTransform({
                    scale(
                        scaleX = motion.scale,
                        scaleY = motion.scale,
                        pivot = lyricCharacterPivot(
                            position = position,
                            width = characterLayout.size.width.toFloat(),
                            height = characterLayout.size.height.toFloat(),
                        ),
                    )
                }) {
                    val glow = glyphGlows[layout]?.getOrNull(characterIndex)
                    if (glow != null && motion.glowAlpha > 0.001f) {
                        glow.layer.alpha = motion.glowAlpha
                        translate(position.x - glow.paddingPx, position.y - glow.paddingPx) {
                            drawLayer(glow.layer)
                        }
                    }
                    drawLyricForeground(
                        textLayoutResult = characterLayout,
                        color = color,
                        topLeft = position,
                    )
                }
            }
        } else {
            val driverLayout = if (layout.syllable.content.trim().all(Char::isPunctuation)) {
                layouts.subList(0, index).lastOrNull { candidate ->
                    candidate.syllable.content.trim().any { !it.isPunctuation() }
                } ?: layout
            } else {
                layout
            }
            val floatOffset = lyricCharacterFloatOffset(
                positionMs = positionMs,
                startTimeMs = driverLayout.animationSource.startTimeMs,
                endTimeMs = driverLayout.animationSource.endTimeMs,
                characterIndex = 0,
                characterCount = 1,
            )
            drawLyricForeground(
                textLayoutResult = layout.textLayoutResult,
                color = color,
                topLeft = layout.position.copy(y = layout.position.y + floatOffset * motionStrength),
            )
        }
    }
}

internal data class WordMotion(
    val scale: Float,
    val offsetYPx: Float,
    val glowRadius: Float,
    val glowAlpha: Float = 0f,
)

internal fun lyricIntervalProgress(
    positionMs: Long,
    startTimeMs: Long,
    endTimeMs: Long,
): Float {
    val durationMs = endTimeMs - startTimeMs
    if (durationMs <= 0L) return if (positionMs >= startTimeMs) 1f else 0f
    return ((positionMs - startTimeMs).toFloat() / durationMs).coerceIn(0f, 1f)
}

internal fun characterProgress(
    positionMs: Long,
    wordStartTimeMs: Long,
    wordEndTimeMs: Long,
    characterIndex: Int,
    characterCount: Int,
): Float {
    val wordDurationMs = (wordEndTimeMs - wordStartTimeMs).coerceAtLeast(0L)
    val windowFraction = if (characterCount <= 10) 0.68f else 4f / (characterCount + 3)
    val animationDurationMs = wordDurationMs * windowFraction
    if (animationDurationMs <= 0f) {
        return if (positionMs >= wordStartTimeMs) 1f else 0f
    }
    val boundedCharacterCount = characterCount.coerceAtLeast(1)
    val characterRatio = if (boundedCharacterCount > 1) {
        characterIndex.coerceIn(0, boundedCharacterCount - 1).toFloat() /
            (boundedCharacterCount - 1)
    } else {
        0.5f
    }
    val latestStartTimeMs = wordEndTimeMs - animationDurationMs
    val animationStartTimeMs = wordStartTimeMs +
        (latestStartTimeMs - wordStartTimeMs) * characterRatio
    return ((positionMs - animationStartTimeMs) / animationDurationMs).coerceIn(0f, 1f)
}

internal fun lyricCharacterFloatOffset(
    positionMs: Long,
    startTimeMs: Long,
    endTimeMs: Long,
    characterIndex: Int,
    characterCount: Int,
): Float {
    val count = characterCount.coerceAtLeast(1)
    val duration = (endTimeMs - startTimeMs).coerceAtLeast(1L).toFloat()
    val window = duration * (2f / (count + 1)).coerceAtMost(1f)
    val ratio = if (count == 1) 0f else characterIndex.coerceIn(0, count - 1).toFloat() / (count - 1)
    val start = startTimeMs + (duration - window) * ratio
    val progress = ((positionMs - start) / window).coerceIn(0f, 1f)
    val eased = (1f - cos(progress * Math.PI).toFloat()) / 2f
    return -LYRIC_FLOAT_MAX_OFFSET_PX * eased
}

internal fun characterMotion(
    positionMs: Long,
    wordStartTimeMs: Long,
    wordEndTimeMs: Long,
    characterIndex: Int,
    characterCount: Int,
): WordMotion {
    val progress = characterProgress(
        positionMs = positionMs,
        wordStartTimeMs = wordStartTimeMs,
        wordEndTimeMs = wordEndTimeMs,
        characterIndex = characterIndex,
        characterCount = characterCount,
    )
    val motion = wordMotion(progress, wordEndTimeMs - wordStartTimeMs)
    return motion.copy(
        offsetYPx = lyricCharacterFloatOffset(
            positionMs, wordStartTimeMs, wordEndTimeMs, characterIndex, characterCount,
        ),
    )
}

internal fun wordMotion(
    progress: Float,
    durationMs: Long,
): WordMotion {
    val boundedProgress = progress.coerceIn(0f, 1f)
    val durationRatio = ((durationMs - 1_000L) / 2_000f).coerceIn(0f, 1f)
    val intensity = durationRatio * durationRatio * (3f - 2f * durationRatio)
    val pulse = sin(boundedProgress * Math.PI).toFloat().let { it * it }
    return WordMotion(
        scale = 1f + (0.06f + 0.06f * intensity) * pulse,
        offsetYPx = -LYRIC_FLOAT_MAX_OFFSET_PX *
            (1f - cos(boundedProgress * Math.PI).toFloat()) / 2f,
        glowRadius = 7f + 5f * intensity,
        glowAlpha = (0.42f + 0.2f * intensity) * pulse,
    )
}