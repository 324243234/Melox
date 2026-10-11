package com.melox.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.BroadcastFrameClock
import com.melox.player.ui.component.playback.PlayerSheetTransitionState
import com.melox.player.ui.component.playback.sharedContainerRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class PlayerResizeTransitionTest {
    @Test
    fun liveGlassClickRequiresCurrentFramesAndKeepsPressedSourceSize() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        val oldGeneration = state.currentFrameRecordingGeneration

        state.open(miniPlayerScale = 1.04f, requireFreshMiniFrame = true)

        assertTrue(state.isMounted)
        assertFalse(state.sharedLayersReady)
        assertFalse(state.fullPlayerDrawsAboveRoot)
        assertEquals(312f, state.overlayMiniPlayerBounds.width, 0.001f)
        assertTrue(state.currentFrameRecordingGeneration > oldGeneration)
        state.markMiniFrameRecorded(portrait, oldGeneration, IntSize(300, 64))
        state.markFullFrameRecorded(portrait, oldGeneration, portrait)
        assertFalse(state.sharedLayersReady)

        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)
        assertTrue(state.fullPlayerDrawsAboveRoot)
        val currentGeneration = state.currentFrameRecordingGeneration
        state.open(miniPlayerScale = 1f, requireFreshMiniFrame = true)
        assertEquals(currentGeneration, state.currentFrameRecordingGeneration)
        assertEquals(1.04f, state.overlayMiniPlayerScale, 0f)
    }

    @Test
    fun liveGlassDragRecordsAfterExpansionAndRetainsCaptureAtZero() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        val oldGeneration = state.currentFrameRecordingGeneration

        state.beginMiniPlayerDrag(miniPlayerScale = 1.01f, requireFreshMiniFrame = true)
        state.dragMiniPlayerBy(80f, miniPlayerScale = 1.02f)
        assertFalse(state.isMounted)
        assertFalse(state.sharedLayersReady)
        val currentGeneration = state.currentFrameRecordingGeneration
        assertTrue(currentGeneration > oldGeneration)

        state.dragMiniPlayerBy(-100f, miniPlayerScale = 1.04f)
        assertTrue(state.isMounted)
        state.markMiniFrameRecorded(portrait, oldGeneration, IntSize(300, 64))
        state.markFullFrameRecorded(portrait, oldGeneration, portrait)
        assertFalse(state.sharedLayersReady)
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)

        state.dragMiniPlayerBy(20f, miniPlayerScale = 1.06f)
        assertEquals(0f, state.progress, 0f)
        assertTrue(state.isMounted)
        assertEquals(1.04f, state.capturedMiniPlayerPressScale!!, 0f)
        state.dragMiniPlayerBy(-20f, miniPlayerScale = 1.08f)
        assertEquals(currentGeneration, state.currentFrameRecordingGeneration)
        assertEquals(1.04f, state.overlayMiniPlayerScale, 0f)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun anotherLiveGlassGestureRejectsThePreviousPresentationFrames() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.02f, requireFreshMiniFrame = true)
        state.dragMiniPlayerBy(80f, miniPlayerScale = 1.04f)
        state.cancelDrag()
        assertFalse(state.isMounted)
        val oldGeneration = state.currentFrameRecordingGeneration
        reportFrames(state, portrait)

        state.beginMiniPlayerDrag(miniPlayerScale = 1.03f, requireFreshMiniFrame = true)
        state.dragMiniPlayerBy(-20f, miniPlayerScale = 1.07f)

        assertTrue(state.currentFrameRecordingGeneration > oldGeneration)
        assertFalse(state.sharedLayersReady)
        assertEquals(1.07f, state.capturedMiniPlayerPressScale!!, 0f)
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun collapsedDownwardDragKeepsTheLivePressedMiniPlayerVisible() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.02f)
        state.dragBy(120f)

        assertEquals(0f, state.progress, 0f)
        assertTrue(state.isDragging)
        assertTrue(state.miniPlayerAcceptsInput)
        assertFalse(state.isMounted)
        assertFalse(state.isTransitionActive)
        assertFalse(state.fullPlayerDrawsAboveRoot)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun downwardThenUpwardDragCapturesTheLatestVisiblePressScale() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.01f)
        state.dragMiniPlayerBy(120f, miniPlayerScale = 1.02f)
        state.dragMiniPlayerBy(-80f, miniPlayerScale = 1.04f)
        assertEquals(0f, state.progress, 0f)
        assertFalse(state.isMounted)

        state.dragMiniPlayerBy(-60f, miniPlayerScale = 1.06f)

        assertTrue(state.progress > 0f)
        assertTrue(state.isMounted)
        assertTrue(state.isTransitionActive)
        assertEquals(1.06f, state.overlayMiniPlayerScale, 0f)
        assertEquals(318f, state.overlayMiniPlayerBounds.width, 0.001f)
        state.dragMiniPlayerBy(-20f, miniPlayerScale = 1.08f)
        assertEquals(1.06f, state.overlayMiniPlayerScale, 0f)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun returningToCollapsedBoundaryKeepsTheFirstCaptureUntilRelease() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.02f)
        state.dragMiniPlayerBy(-120f, miniPlayerScale = 1.04f)
        assertTrue(state.isTransitionActive)

        state.dragMiniPlayerBy(120f, miniPlayerScale = 1.06f)

        assertEquals(0f, state.progress, 0f)
        assertTrue(state.isMounted)
        assertTrue(state.isTransitionActive)
        assertTrue(state.isDragging)
        assertTrue(state.miniPlayerAcceptsInput)
        assertEquals(1.04f, state.overlayMiniPlayerScale, 0f)
        assertEquals(1.04f, state.capturedMiniPlayerPressScale!!, 0f)
        state.dragMiniPlayerBy(-20f, miniPlayerScale = 1.08f)
        assertEquals(1.04f, state.overlayMiniPlayerScale, 0f)
        state.dragMiniPlayerBy(20f, miniPlayerScale = 1.1f)
        assertEquals(0f, state.progress, 0f)
        assertEquals(1.04f, state.capturedMiniPlayerPressScale!!, 0f)
        state.dragMiniPlayerBy(-20f, miniPlayerScale = 1.1f)
        assertEquals(1.04f, state.overlayMiniPlayerScale, 0f)
        assertTrue(state.isMounted)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun upwardFlingAfterReturningToZeroKeepsTheFirstCapture() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.02f)
        state.dragMiniPlayerBy(-80f, miniPlayerScale = 1.04f)
        state.dragMiniPlayerBy(80f, miniPlayerScale = 1.06f)

        state.endMiniPlayerDrag(-900f, miniPlayerScale = 1.1f)

        assertTrue(state.targetOpen)
        assertTrue(state.isMounted)
        assertEquals(1.04f, state.overlayMiniPlayerScale, 0f)
        assertEquals(null, state.capturedMiniPlayerPressScale)
    }

    @Test
    fun newGestureCapturesAgainAfterThePreviousGestureIsReleased() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.02f)
        state.dragMiniPlayerBy(-80f, miniPlayerScale = 1.04f)
        state.dragMiniPlayerBy(80f, miniPlayerScale = 1.06f)
        state.endMiniPlayerDrag(900f, miniPlayerScale = 1.1f)
        assertFalse(state.isMounted)
        assertEquals(null, state.capturedMiniPlayerPressScale)

        state.beginMiniPlayerDrag(miniPlayerScale = 1.03f)
        assertEquals(null, state.capturedMiniPlayerPressScale)
        state.dragMiniPlayerBy(-20f, miniPlayerScale = 1.07f)

        assertEquals(1.07f, state.capturedMiniPlayerPressScale!!, 0f)
        assertEquals(1.07f, state.overlayMiniPlayerScale, 0f)
    }

    @Test
    fun zeroProgressFlingUsesReleaseScaleAndPreservesDirectionSettlement() {
        for (velocity in listOf(-900f, 900f)) {
            val state = PlayerSheetTransitionState()
            state.updateWindowSize(portrait)
            reportBounds(state, portrait)
            reportFrames(state, portrait)
            state.beginMiniPlayerDrag(miniPlayerScale = 1.01f)
            state.dragMiniPlayerBy(120f, miniPlayerScale = 1.02f)
            state.dragMiniPlayerBy(-60f, miniPlayerScale = 1.04f)
            assertEquals(0f, state.progress, 0f)

            state.endMiniPlayerDrag(velocity, miniPlayerScale = 1.08f)

            assertFalse(state.isDragging)
            if (velocity < 0f) {
                assertTrue(state.targetOpen)
                assertTrue(state.isMounted)
                assertEquals(1.08f, state.overlayMiniPlayerScale, 0f)
            } else {
                assertFalse(state.targetOpen)
                assertFalse(state.isMounted)
                assertTrue(state.miniPlayerAcceptsInput)
            }
            assertTrue(state.sharedLayersReady)
        }
    }

    @Test
    fun pressedClickStartsFromVisibleBoundsWithoutInvalidatingRecordedFrames() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        state.updateMiniPlayerContentBounds(Rect(0f, 8f, 300f, 56f), portrait)
        state.updateMiniPlayerControlsBounds(Rect(200f, 8f, 288f, 56f), portrait)
        reportFrames(state, portrait)

        state.open(miniPlayerScale = 1.1f)

        assertTrue(state.sharedLayersReady)
        assertEquals(Rect(0f, 0f, 300f, 64f), state.miniPlayerBounds)
        assertEquals(-15f, state.overlayMiniPlayerBounds.left, 0.001f)
        assertEquals(330f, state.overlayMiniPlayerBounds.width, 0.001f)
        assertEquals(70.4f, state.overlayMiniPlayerBounds.height, 0.001f)
        assertEquals(state.overlayMiniPlayerBounds, sharedContainerRect(
            state.overlayMiniPlayerBounds, state.fullPlayerBounds, state.progress,
        ))
        assertEquals(-6.2f, state.overlayMiniArtworkBounds.left, 0.001f)
        assertEquals(52.8f, state.overlayMiniArtworkBounds.width, 0.001f)
        assertEquals(330f, state.overlayMiniPlayerContentBounds.width, 0.001f)
        assertEquals(96.8f, state.overlayMiniPlayerControlsBounds.width, 0.001f)

        // A second activation or the released live mini player cannot reset the source.
        state.open(miniPlayerScale = 1f)
        assertEquals(1.1f, state.overlayMiniPlayerScale, 0.001f)
    }

    @Test
    fun cancelingPressedExpansionKeepsTheReleaseFrameSize() {
        for (distance in listOf(-120f, -0.01f)) {
            val state = PlayerSheetTransitionState()
            state.updateWindowSize(portrait)
            reportBounds(state, portrait)
            reportFrames(state, portrait)
            state.beginMiniPlayerDrag(miniPlayerScale = 1.1f)
            state.dragBy(distance)
            val visibleSource = state.overlayMiniPlayerBounds
            val progress = state.progress

            state.cancelDrag()

            assertFalse(state.targetOpen)
            assertEquals(progress, state.progress, 0f)
            assertEquals(visibleSource, state.overlayMiniPlayerBounds)
            assertTrue(state.sharedLayersReady)
        }
    }

    @Test
    fun completedPressedExpansionDismissesToTheOrdinaryMiniPlayerSize() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginMiniPlayerDrag(miniPlayerScale = 1.1f)
        state.dragBy(-1000f)
        state.endDrag(-900f)
        assertTrue(state.isFullyExpanded)

        state.close()

        assertEquals(1f, state.overlayMiniPlayerScale, 0f)
        assertEquals(state.miniPlayerBounds, state.overlayMiniPlayerBounds)
    }

    @Test
    fun canceledPressShrinksContinuouslyAndReopeningPreservesItsCurrentSource() {
        val clock = BroadcastFrameClock()
        runBlocking(clock) {
            var frameNanos = 0L
            suspend fun frame() {
                yield()
                frameNanos += 16_666_667L
                clock.sendFrame(frameNanos)
                yield()
            }
            suspend fun finish(animation: Job) {
                repeat(180) {
                    if (!animation.isActive) return
                    frame()
                }
                assertTrue("Player spring did not settle", animation.isCompleted)
            }
            val state = PlayerSheetTransitionState()
            state.updateWindowSize(portrait)
            reportBounds(state, portrait)
            reportFrames(state, portrait)
            state.beginMiniPlayerDrag(miniPlayerScale = 1.1f)
            state.dragBy(-120f)
            state.cancelDrag()
            val closing = launch { state.animateToTarget() }
            repeat(6) { frame() }
            assertTrue(state.overlayMiniPlayerScale > 1f)
            assertTrue(state.overlayMiniPlayerScale < 1.1f)
            val sourceBeforeReopening = state.overlayMiniPlayerBounds

            state.open()
            assertEquals(sourceBeforeReopening, state.overlayMiniPlayerBounds)
            closing.cancelAndJoin()
            finish(launch { state.animateToTarget() })
            assertTrue(state.isFullyExpanded)
            state.close()
            finish(launch { state.animateToTarget() })

            assertEquals(0f, state.progress, 0f)
            assertEquals(1f, state.overlayMiniPlayerScale, 0f)
            assertEquals(state.miniPlayerBounds, state.overlayMiniPlayerBounds)
            assertTrue(state.sharedLayersReady)
        }
    }

    @Test
    fun missingFramesDoNotBlockLogicalSettlement() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportContainerBounds(state, portrait)

        assertTrue(state.canSettle)
        assertFalse(state.sharedLayersReady)
        state.open()
        assertTrue(state.targetOpen)
    }

    @Test
    fun lyricsPageWithoutArtworkBoundsKeepsSharedAnimationReadyAcrossRequests() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        state.updateFullPlayerArtworkPageSelected(false)
        reportContainerBounds(state, portrait)
        reportFrames(state, portrait)

        assertTrue(state.sharedLayersReady)
        assertFalse(state.separateArtworkOverlayReady)

        state.open()
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)
        state.close()
        assertTrue(state.canSettle)
        assertTrue(state.sharedLayersReady)

        state.open()
        assertTrue(state.sharedLayersReady)
        assertFalse(state.separateArtworkOverlayReady)
    }

    @Test
    fun missingFullArtworkBoundsDisableOnlyTheArtworkOverlay() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        state.updateMiniPlayerBounds(Rect(0f, 0f, 300f, 64f), portrait)
        state.updateMiniArtworkBounds(Rect(8f, 8f, 56f, 56f), portrait)
        state.updateFullPlayerBounds(
            Rect(0f, 0f, portrait.width.toFloat(), portrait.height.toFloat()),
            portrait,
        )
        reportFrames(state, portrait)

        assertTrue(state.sharedLayersReady)
        assertFalse(state.hasArtworkBounds)
        assertFalse(state.separateArtworkOverlayReady)
        assertTrue(state.fullPlayerAcceptsInput)
    }

    @Test
    fun closingWithoutRecordedFramesKeepsVisibleProgressUntilFramesRecover() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(IntSize(400, 800))
        assertFalse(state.sharedLayersReady)
        state.close()
        assertTrue(state.isMounted)
        assertTrue(state.fullPlayerHostMounted)
        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun unreadyClosingHostYieldsToTheMiniPlayerBelowTheHandoff() {
        val state = PlayerSheetTransitionState(initialProgress = 0.2f)
        state.updateWindowSize(portrait)
        state.close()

        assertTrue(state.isMounted)
        assertTrue(state.fullPlayerHostMounted)
        assertFalse(state.fullPlayerDrawsAboveRoot)
        assertFalse(state.fullPlayerDrawsInPlace)
        assertTrue(state.miniPlayerAcceptsInput)
    }

    @Test
    fun expandedPlayerUsesTheLiveEndpointWithoutRecordedFrames() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        reportContainerBounds(state, portrait)

        assertFalse(state.sharedLayersReady)
        assertTrue(state.fullPlayerDrawsAboveRoot)
        assertTrue(state.fullPlayerDrawsInPlace)
        assertTrue(state.fullPlayerAcceptsInput)
        assertTrue(state.isFullyExpanded)
    }

    @Test
    fun collapsedEndpointUnmountsWithoutRecordedFrames() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)

        assertEquals(0f, state.progress, 0f)
        assertFalse(state.isMounted)
        assertFalse(state.fullPlayerHostMounted)
        assertTrue(state.miniPlayerAcceptsInput)
    }

    @Test
    fun openedPlayerHostStaysResidentWhenTheSheetIsCollapsed() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportContainerBounds(state, portrait)

        state.open()
        state.close()

        assertTrue(state.fullPlayerHostMounted)
    }

    @Test
    fun firstUpwardDragRetainsFullHostAfterReleaseWithoutCallingOpen() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        assertFalse(state.fullPlayerHostMounted)

        state.beginMiniPlayerDrag()
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.dragBy(-800f)
        state.endDrag(velocityY = -900f)

        assertTrue(state.targetOpen)
        assertFalse(state.isDragging)
        assertEquals(1f, state.progress, 0f)
        assertFalse(state.isTransitionActive)
        assertTrue(state.fullPlayerHostMounted)
        assertTrue(state.fullPlayerDrawsInPlace)
        assertTrue(state.fullPlayerAcceptsInput)
        assertFalse(state.miniPlayerAcceptsInput)
    }

    @Test
    fun firstShortUpwardDragRetainsHostDuringSpringSettlement() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        state.beginMiniPlayerDrag()
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.dragBy(-120f)
        state.endDrag(velocityY = -900f)

        assertTrue(state.targetOpen)
        assertTrue(state.isTransitionActive)
        assertTrue(state.fullPlayerHostMounted)
        assertTrue(state.fullPlayerDrawsAboveRoot)
    }

    @Test
    fun firstUpwardDragKeepsFullPlayerHostMountedAfterRelease() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        state.updateMiniPlayerBounds(Rect(0f, 736f, 300f, 800f), portrait)
        state.updateFullPlayerBounds(Rect(0f, 0f, 400f, 800f), portrait)

        state.beginMiniPlayerDrag()
        state.dragBy(-800f)
        state.endDrag(velocityY = -900f)

        assertTrue(state.targetOpen)
        assertFalse(state.isDragging)
        assertTrue(state.fullPlayerHostMounted)
    }

    private val portrait = IntSize(400, 800)
    private val landscape = IntSize(800, 400)

    private fun reportBounds(state: PlayerSheetTransitionState, window: IntSize) {
        reportContainerBounds(state, window)
        state.updateMiniArtworkBounds(Rect(8f, 8f, 56f, 56f), window)
        state.updateFullArtworkBounds(Rect(32f, 64f, 232f, 264f), window)
    }

    private fun reportContainerBounds(state: PlayerSheetTransitionState, window: IntSize) {
        state.updateMiniPlayerBounds(Rect(0f, 0f, 300f, 64f), window)
        state.updateFullPlayerBounds(Rect(0f, 0f, window.width.toFloat(), window.height.toFloat()), window)
    }

    private fun reportFrames(state: PlayerSheetTransitionState, window: IntSize) {
        val generation = state.currentFrameRecordingGeneration
        state.markMiniFrameRecorded(window, generation, IntSize(300, 64))
        state.markFullFrameRecorded(window, generation, window)
    }

    @Test
    fun resizeRejectsOldGeometryAndWaitsForBothCurrentFrames() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)
        state.updateWindowSize(landscape)
        assertFalse(state.sharedLayersReady)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        assertFalse(state.hasArtworkBounds)
        reportBounds(state, landscape)
        assertFalse(state.sharedLayersReady)
        val generation = state.currentFrameRecordingGeneration
        state.markFullFrameRecorded(landscape, generation, portrait)
        state.markMiniFrameRecorded(landscape, generation, IntSize(300, 64))
        assertFalse(state.sharedLayersReady)
        state.markFullFrameRecorded(landscape, generation, landscape)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun activeTransitionUsesLatestArtworkEndpoints() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)

        state.beginFullPlayerDrag()
        state.dragBy(120f)
        state.updateMiniPlayerBounds(Rect(0f, 704f, 300f, 768f), portrait)
        assertEquals(Rect(8f, 8f, 56f, 56f), state.overlayMiniArtworkBounds)
        state.updateMiniArtworkBounds(Rect(20f, 720f, 68f, 768f), portrait)
        state.updateFullArtworkBounds(Rect(32f, 80f, 232f, 280f), portrait)

        assertEquals(Rect(20f, 720f, 68f, 768f), state.overlayMiniArtworkBounds)
        assertEquals(Rect(32f, 80f, 232f, 280f), state.overlayFullArtworkBounds)
    }

    @Test
    fun resizingAnExpandedPlayerKeepsItsLiveEndpointInteractive() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(landscape)
        reportBounds(state, landscape)
        reportFrames(state, landscape)
        assertTrue(state.sharedLayersReady)
        assertTrue(state.targetOpen)
        assertTrue(state.fullPlayerHostMounted)
        assertTrue(state.fullPlayerAcceptsInput)

        state.updateWindowSize(portrait)

        assertTrue(state.targetOpen)
        assertTrue(state.fullPlayerHostMounted)
        assertFalse(state.sharedLayersReady)
        assertTrue(state.fullPlayerAcceptsInput)
        assertTrue(state.fullPlayerDrawsAboveRoot)

        reportBounds(state, portrait)
        reportFrames(state, portrait)

        assertTrue(state.sharedLayersReady)
        assertTrue(state.fullPlayerHostMounted)
        assertTrue(state.fullPlayerAcceptsInput)
    }

    @Test
    fun hiddenPreparationHostStaysBehindTheRootUntilFramesAreReady() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        state.open()

        assertTrue(state.fullPlayerHostMounted)
        assertFalse(state.fullPlayerDrawsAboveRoot)

        reportBounds(state, portrait)
        reportFrames(state, portrait)

        assertTrue(state.fullPlayerDrawsAboveRoot)
    }

    @Test
    fun resizeKeepsClosingProgressUntilCurrentGeometryIsReady() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        state.beginFullPlayerDrag()
        state.dragBy(184f)
        state.endDrag(velocityY = 1f)
        val progressBeforeResize = state.progress
        state.updateMiniArtworkBounds(Rect(20f, 20f, 68f, 68f), portrait)
        state.updateFullArtworkBounds(Rect(40f, 40f, 340f, 340f), portrait)
        assertEquals(Rect(20f, 20f, 68f, 68f), state.overlayMiniArtworkBounds)
        assertEquals(Rect(40f, 40f, 340f, 340f), state.overlayFullArtworkBounds)
        state.updateWindowSize(landscape)
        assertEquals(progressBeforeResize, state.progress)
        assertFalse(state.targetOpen)
        assertFalse(state.sharedLayersReady)
        assertEquals(Rect.Zero, state.overlayFullArtworkBounds)
    }

    @Test
    fun collapsedPlayerRetainsPreparedFullArtworkAndFrames() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)

        state.open()
        state.close()

        assertTrue(state.sharedLayersReady)
        assertTrue(state.fullPlayerBounds != Rect.Zero)
        assertTrue(state.fullArtworkBounds != Rect.Zero)
        assertTrue(state.miniArtworkBounds != Rect.Zero)
    }

    @Test
    fun resizeRejectsAnOldGenerationWhenTheWindowReturnsToTheSameSize() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        val firstPortraitGeneration = state.currentFrameRecordingGeneration
        reportBounds(state, portrait)
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)

        state.updateWindowSize(landscape)
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        state.markMiniFrameRecorded(
            portrait,
            firstPortraitGeneration,
            IntSize(300, 64),
        )
        state.markFullFrameRecorded(
            portrait,
            firstPortraitGeneration,
            portrait,
        )

        assertFalse(state.sharedLayersReady)
        reportFrames(state, portrait)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun openingAnUnreadyPlayerRequestsFreshFrameRecording() {
        val state = PlayerSheetTransitionState()
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        val staleGeneration = state.currentFrameRecordingGeneration

        state.open()

        val retryGeneration = state.currentFrameRecordingGeneration
        assertTrue(retryGeneration > staleGeneration)
        state.markMiniFrameRecorded(portrait, staleGeneration, IntSize(300, 64))
        state.markFullFrameRecorded(portrait, staleGeneration, portrait)
        assertFalse(state.sharedLayersReady)

        state.markMiniFrameRecorded(portrait, retryGeneration, IntSize(300, 64))
        state.markFullFrameRecorded(portrait, retryGeneration, portrait)
        assertTrue(state.sharedLayersReady)
    }

    @Test
    fun currentGenerationAllowsOnePixelLayerRoundingDifference() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateWindowSize(portrait)
        reportBounds(state, portrait)
        val generation = state.currentFrameRecordingGeneration

        state.markMiniFrameRecorded(portrait, generation, IntSize(299, 63))
        state.markFullFrameRecorded(portrait, generation, IntSize(399, 799))

        assertTrue(state.sharedLayersReady)
    }
}
