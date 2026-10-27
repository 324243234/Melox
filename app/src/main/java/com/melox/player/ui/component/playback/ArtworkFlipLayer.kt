package com.melox.player.ui.component.playback

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.google.android.renderscript.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun ArtworkFlipLayer(
    state: ArtworkFlipState<Bitmap>,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    content: @Composable (Bitmap, Modifier) -> Unit,
) {
    // Only a face change recomposes content; sample continuous motion in the layers below.
    val bitmap = state.visibleValue ?: return
    val frontBlur = rememberLegacyFlipBlur(state.front)
    val backBlur = rememberLegacyFlipBlur(state.back)
    Box(
        modifier = modifier,
    ) {
        Box(Modifier.graphicsLayer {
            transformOrigin = TransformOrigin.Center
            rotationY = state.frame.rotationY
            cameraDistance = artworkFlipCameraDistance(size.width, size.height)
            // Keep the shadow outside the face bounds and on the same plane.
            clip = false
        }) {
            val blurModifier = if (Build.VERSION.SDK_INT >= 31) {
                Modifier.graphicsLayer {
                    val radius = minOf(size.width, size.height) * 0.08f * state.frame.blurFraction
                    renderEffect = if (radius > 0.1f) BlurEffect(radius, radius, TileMode.Clamp) else null
                }
            } else Modifier.drawWithContent {
                drawContent()
                val frame = state.frame
                val blurredBitmap = if (frame.showBack) backBlur else frontBlur
                if (blurredBitmap != null && frame.blurFraction > 0f) {
                    val scale = contentScale.computeScaleFactor(
                        Size(blurredBitmap.width.toFloat(), blurredBitmap.height.toFloat()), size,
                    )
                    val width = (blurredBitmap.width * scale.scaleX).roundToInt().coerceAtLeast(1)
                    val height = (blurredBitmap.height * scale.scaleY).roundToInt().coerceAtLeast(1)
                    drawImage(
                        image = blurredBitmap.asImageBitmap(),
                        dstOffset = IntOffset(((size.width - width) / 2f).roundToInt(),
                            ((size.height - height) / 2f).roundToInt()),
                        dstSize = IntSize(width, height),
                        alpha = frame.blurFraction,
                        filterQuality = FilterQuality.High,
                    )
                }
            }
            content(bitmap, blurModifier)
        }
    }
}

@Composable
private fun rememberLegacyFlipBlur(bitmap: Bitmap?): Bitmap? {
    var blurred by remember(bitmap) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bitmap) {
        if (Build.VERSION.SDK_INT >= 31 || bitmap == null) return@LaunchedEffect
        blurred = withContext(Dispatchers.Default) {
            val scale = (128f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val sampled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
            val source = if (sampled.config == Bitmap.Config.ARGB_8888) sampled
                else sampled.copy(Bitmap.Config.ARGB_8888, false)
            val result = Toolkit.blur(source, (minOf(source.width, source.height) * 0.08f)
                .roundToInt().coerceIn(1, 25))
            if (source !== sampled) source.recycle()
            if (sampled !== bitmap) sampled.recycle()
            result
        }
    }
    return blurred
}
