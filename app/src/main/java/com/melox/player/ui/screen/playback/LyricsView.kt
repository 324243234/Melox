// SPDX-License-Identifier: Apache-2.0
package com.melox.player.ui.screen.playback

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import java.io.File
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
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
import androidx.compose.ui.MotionDurationScale
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFontFamilyResolver
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
import com.melox.player.model.LyricsTranslationLanguage
import com.melox.player.model.LyricAnimationMode
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricAlignment
import com.melox.player.model.LyricsDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.MiuixIndication

private const val LYRIC_EDGE_FADE_DP = 100f
private const val LYRIC_ANIMATION_BLEED_DP = 8f
internal const val LYRIC_INACTIVE_TEXT_ALPHA = 0.4f
internal const val LYRICS_MANUAL_FOLLOW_RESUME_DELAY_MS = 5_000L
private const val LYRIC_FOCUS_SCALE_IN_DURATION_MS = 600
private const val LYRIC_FOCUS_SCALE_OUT_DURATION_MS = 300
private const val LYRIC_FOCUS_ALPHA_ANIMATION_DURATION_MS = 180
private const val LYRIC_BLUR_ANIMATION_DURATION_MS = 300

// ========================================================
// 【修改】：你定义的大号字体数值
internal const val LYRIC_PRIMARY_FONT_SIZE_SP = 29f
internal const val LYRIC_PRIMARY_LINE_HEIGHT_SP = 30f
internal const val LYRIC_ROMANIZATION_FONT_SIZE_SP = 14f
internal const val LYRIC_ROMANIZATION_LINE_HEIGHT_SP = 16f
internal const val LYRIC_TRANSLATION_FONT_SIZE_SP = 19f
internal const val LYRIC_TRANSLATION_LINE_HEIGHT_SP = 22f
// ========================================================

internal const val LYRIC_TRANSLATION_GAP_DP = 2f
private val LyricLayerPaint = Paint()

@Composable
internal fun rememberLyricFontFamily(weight: Int): FontFamily = remember(weight) {
    // ========================================================
    // 【修改】：使用你的本地字体路径和加载逻辑
    val mediumFontPath = "/storage/emulated/0/Documents/字体/SimplifiedChinese/SourceHanSansSC-Medium.otf"
    val boldFontPath = "/storage/emulated/0/Documents/字体/SimplifiedChinese/SourceHanSansSC-Bold.otf"

    val isBold = weight >= 600
    val targetPath = if (isBold) boldFontPath else mediumFontPath

    val customTypeface = try {
        val file = File(targetPath)
        if (file.exists() && file.canRead()) {
            Typeface.createFromFile(file)
        } else {
            Typeface.create(Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
        }
    } catch (e: Exception) {
        Typeface.create(Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
    }

    FontFamily(customTypeface)
    // ========================================================
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
    if (frameAdvanceNanos > 250_000_000L) return sampledPositionMs.coerceAtLeast(0L).toDouble()
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
    active: Boolean = true,
): Boolean = hasPositionedInitialFocus && centerOffsetUnchanged && !isPreviewing && active

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
    maxOf(textHeightPx * 0.72f,
        textHeightPx * 0.12f + 12f * 3f * 1.12f + LYRIC_FLOAT_MAX_OFFSET_PX)

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
    lyricFontScale: Float = 1f,
): Float {
    val translationVisible = hasTranslation && showLyricsTranslation
    val spacingScale = 1f + (lyricFontScale - 1f) * 0.25f
    return (if (translationVisible) 10f else 12f) * spacingScale
}

@Composable
internal fun rememberSmoothLyricTimeProvider(
    document: LyricsDocument,
    positionMs: Long,
    positionUpdateElapsedRealtimeMs: Long,
    playbackIteration: Long,
    playbackSpeed: Float,
    isPlaying: Boolean,
    active: Boolean,
    positionDiscontinuityRevision: Long,
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
    val clock = remember(document) {
        LyricPlaybackClock(positionMs, playbackIteration, positionDiscontinuityRevision)
    }
    val smoothPositionMs = clock.positionMs
    val latestPlaybackIteration by rememberUpdatedState(playbackIteration)
    val latestDiscontinuityRevision by rememberUpdatedState(positionDiscontinuityRevision)

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
        positionMs,
        positionUpdateElapsedRealtimeMs,
        playbackSpeed,
        isPlaying,
        active,
        positionDiscontinuityRevision,
    ) {
        clock.updateFromPlayback(
            playbackIteration, currentPlaybackPositionMs(), active, positionDiscontinuityRevision,
        )
        if (seekRequestKey != 0) {
            smoothPositionMs.doubleValue = seekPositionMs.coerceAtLeast(0L).toDouble()
        }
    }
    LaunchedEffect(document, isPlaying, active) {
        if (!isPlaying || !active) return@LaunchedEffect
        var previousFrameTimeNanos = 0L
        while (isActive) {
            val frameTimeNanos = withFrameNanos { it }
            val frameAdvanceNanos = if (previousFrameTimeNanos > 0L) {
                (frameTimeNanos - previousFrameTimeNanos).coerceAtLeast(0L)
            } else {
                0L
            }
            previousFrameTimeNanos = frameTimeNanos
            val clockReset = clock.updateFromPlayback(
                latestPlaybackIteration, currentPlaybackPositionMs(), active, latestDiscontinuityRevision,
            )
            smoothPositionMs.doubleValue = if (latestSeekRequestKey != 0) {
                latestSeekPositionMs.coerceAtLeast(0L).toDouble()
            } else if (clockReset) {
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
    lyricAnimationMode: LyricAnimationMode,
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
    activeLyricAlpha: Float = 1f,
    active: Boolean = true,
    playerActive: Boolean = true,
    positionDiscontinuityRevision: Long = 0L,
    showLyricsRomanization: Boolean = true,
    lyricsTranslationLanguage: LyricsTranslationLanguage = LyricsTranslationLanguage.AUTO,
    applyEdgeFade: Boolean = true,
) {
    val appLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val selectedTranslations = remember(document, lyricsTranslationLanguage, appLanguageTag) {
        document.lines.map { it.translationFor(lyricsTranslationLanguage, appLanguageTag) }
    }
    val documentHasTimedWords = remember(document) {
        document.lines.any { it.words.isNotEmpty() }
    }
    val listState = rememberLazyListState()
    val currentTimeProvider = rememberSmoothLyricTimeProvider(
        document = document,
        positionMs = positionMs,
        positionUpdateElapsedRealtimeMs = positionUpdateElapsedRealtimeMs,
        playbackIteration = playbackIteration,
        playbackSpeed = playbackSpeed,
        isPlaying = isPlaying,
        active = playerActive,
        positionDiscontinuityRevision = positionDiscontinuityRevision,
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
    val offscreenRenderPositionMs = lyricDisplayedPositionMs(
        previewPositionMs = previewPositionMs,
        seekRequestPending = seekRequestKey != 0,
        seekPositionMs = seekPositionMs,
        smoothPositionMs = positionMs,
    )
    val density = LocalDensity.current
    val alignmentProgress = animateFloatAsState(
        targetValue = if (centerLyrics && !document.isDuet) 1f else 0f,
        animationSpec = playerTextAlignmentSpec(),
        label = "lyricsAlignment",
    )
    val viewConfiguration = LocalViewConfiguration.current
    val centerOffsetPx = with(density) { centerOffsetY.toPx() }
    val keepAliveZone = 100.dp
    val playbackFrame by remember(document) {
        derivedStateOf { document.playbackFrame(lyricTimeProvider()) }
    }
    val focusLineIndex by remember(document) {
        derivedStateOf { document.playbackFrame(lyricTimeProvider()).anchorIndex }
    }
    val activeLineIndices = playbackFrame.activeLineIndices
    var isUserBrowsingLyrics by remember(document) { mutableStateOf(false) }
    var outgoingSeekPositions by remember(document) { mutableStateOf(emptyMap<Int, Long>()) }
    var pendingSeekCenteringTargetIndex by remember(document) { mutableIntStateOf(-1) }
    var pendingSeekCenteringDeltaPx by remember(document) { mutableStateOf<Float?>(null) }
    val isPreviewing = previewPositionMs != null
    val rowMotion = remember(document) { LyricRowMotion() }
    val translationMotion = remember(document) { LyricTranslationMotion(showLyricsTranslation) }
    val romanizationMotion = remember(document) { LyricTranslationMotion(showLyricsRomanization) }
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
        lyricFontScale, lyricFontWeight, lyricsTranslationLanguage, appLanguageTag,
        density.density, density.fontScale,
    )
    val visualFocusRenderIndex = focusLineIndex
    
    // ========================================================
    // 【修改】：集成你的大字体与去除排版基线留白的代码
    val lyricFontFamily = rememberLyricFontFamily(lyricFontWeight)

    val normalTextStyle = MiuixTheme.textStyles.title3.copy(
        fontSize = (LYRIC_PRIMARY_FONT_SIZE_SP * lyricFontScale).sp,
        lineHeight = (LYRIC_PRIMARY_LINE_HEIGHT_SP * lyricFontScale).sp,
        fontFamily = lyricFontFamily,
        fontWeight = FontWeight.Normal, // 强制 Normal 避免伪加粗
        fontSynthesis = FontSynthesis.None,
        textDirection = TextDirection.Content,
        textMotion = TextMotion.Animated,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
        )
    )

    val translationTextStyle = MiuixTheme.textStyles.body1.copy(
        fontSize = (LYRIC_TRANSLATION_FONT_SIZE_SP * lyricFontScale).sp,
        lineHeight = (LYRIC_TRANSLATION_LINE_HEIGHT_SP * lyricFontScale).sp,
        fontFamily = lyricFontFamily,
        fontWeight = FontWeight.Normal, // 强制 Normal
        fontSynthesis = FontSynthesis.None,
        textDirection = TextDirection.Content,
        textMotion = TextMotion.Animated,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
        )
    )

    val romanizationTextStyle = translationTextStyle.copy(
        fontSize = (LYRIC_ROMANIZATION_FONT_SIZE_SP * lyricFontScale).sp,
        lineHeight = (LYRIC_ROMANIZATION_LINE_HEIGHT_SP * lyricFontScale).sp,
    )
    // ========================================================

    val textMeasurer = rememberTextMeasurer()
    val layoutDirection = LocalLayoutDirection.current
    val fontResolver = LocalFontFamilyResolver.current
    val lineCache = remember(document, normalTextStyle, emphasisColor, density, layoutDirection, fontResolver) {
        PreparedLyricLineCache()
    }

    val lyricBrowsingModifier = Modifier.pointerInput(document) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Final,
            )
            var movement = Offset.Zero
            var browsingStarted = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                // Both positions use this event's coordinates, even when the pager moves us.
                movement += change.position - change.previousPosition
                if (!browsingStarted) {
                    if (
                        abs(movement.x) > viewConfiguration.touchSlop &&
                            abs(movement.x) >= abs(movement.y)
                    ) {
                        // Once horizontal paging wins, this gesture cannot become browsing.
                        break
                    }
                    if (lyricVerticalDragExceedsTouchSlop(
                            horizontalDeltaPx = movement.x,
                            verticalDeltaPx = movement.y,
                            touchSlopPx = viewConfiguration.touchSlop,
                        )
                    ) {
                        browsingStarted = true
                        isUserBrowsingLyrics = true
                    }
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
        outgoingSeekPositions = emptyMap()
        pendingSeekCenteringTargetIndex = -1
        pendingSeekCenteringDeltaPx = null
    }
    LaunchedEffect(activeLineIndices) {
        outgoingSeekPositions = outgoingSeekPositions.filterKeys { it !in activeLineIndices }
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
    LaunchedEffect(document, showLyricsRomanization) {
        if (document.lines.none { it.romanization != null }) return@LaunchedEffect
        romanizationMotion.transitionTo(showLyricsRomanization) {
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
        active = active,
        focusLineIndices = playbackFrame.anchorLineIndices,
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
        var animateCentering = false
        var centeringSpringStiffness = LYRIC_CENTERING_BASE_STIFFNESS
        var visibleRowOrder = emptyList<Int>()
        var placementCommitted = false
        var centeringOffsetPx = centerOffsetPx
        try {
            scrollInCode.value = true
            listState.scroll {
                // Own native scrolling before capturing positions or awaiting retention layout.
                val layoutInfo = listState.layoutInfo
                val targetItem = layoutInfo.visibleItemsInfo.firstOrNull {
                    it.index == visualFocusRenderIndex
                }
                val focusItems = layoutInfo.visibleItemsInfo.filter { it.index in centeringEvent.focusLineIndices }
                if (targetItem != null && focusItems.size > 1 &&
                    focusItems.size == centeringEvent.focusLineIndices.size
                ) {
                    val groupTop = focusItems.minOf { it.offset }
                    val groupBottom = focusItems.maxOf { it.offset + it.size }
                    if (groupBottom - groupTop <= viewportHeightPx - 2f * abs(centerOffsetPx)) {
                        centeringOffsetPx += targetItem.offset + targetItem.size / 2f -
                            (groupTop + groupBottom) / 2f
                    }
                }
                animateCentering = lyricSeekUsesAnimatedCentering(
                    hasPositionedInitialFocus = hasPositionedInitialFocus,
                    centerOffsetUnchanged = lastCenteringGeometry == centeringGeometry,
                    isPreviewing = isPreviewing,
                    active = active,
                )
                val centeringLine = document.lines.getOrNull(visualFocusRenderIndex)
                val nextTimestampGapMs = centeringLine?.let { line ->
                    document.lines
                        .getOrNull(visualFocusRenderIndex + 1)
                        ?.startTimeMs
                        ?.minus(line.startTimeMs)
                }
                centeringSpringStiffness = lyricCenteringSpringStiffness(nextTimestampGapMs)
                val capturedSeekCenteringDelta = pendingSeekCenteringDeltaPx.takeIf {
                    pendingSeekCenteringTargetIndex == visualFocusRenderIndex && focusItems.size <= 1
                }
                val measuredScrollDelta = capturedSeekCenteringDelta ?: targetItem?.let { visibleTarget ->
                    lyricCenterScrollDelta(
                        itemOffset = visibleTarget