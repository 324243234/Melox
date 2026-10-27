package com.melox.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.melox.player.model.NavigationTransitionStyle
import com.melox.player.ui.navigation.AospNavigationTransition
import com.melox.player.ui.navigation.secondaryPageEnterMotion
import com.melox.player.ui.navigationBarAnimationSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

class NavigationChromeMotionTest {
    @Test
    fun miuixChromeTracksOfficialPageTravelThroughoutEntry() {
        assertPageTravel(NavigationTransitionStyle.MIUIX, NavTransitions.MiuixDefault.motion.programmatic)
    }

    @Test
    fun aospChromeTracksPageTravelIncludingTheTwoSegmentCurve() {
        assertPageTravel(NavigationTransitionStyle.AOSP, AospNavigationTransition.motion.programmatic)
    }

    @Test
    fun rootSettingHideAndReturnKeepTheirExistingMotion() {
        val hide = navigationBarAnimationSpec<Float>(showNavigation = false, hideMotion = null)
        val show = navigationBarAnimationSpec<Float>(
            showNavigation = true,
            hideMotion = secondaryPageEnterMotion(NavigationTransitionStyle.AOSP),
        )
        assertEquals(240, hide.durationMillis)
        assertEquals(280, show.durationMillis)
        assertEquals(FastOutSlowInEasing, hide.easing)
        assertEquals(FastOutSlowInEasing, show.easing)
    }

    private fun assertPageTravel(style: NavigationTransitionStyle, pageMotion: NavSettleSpec) {
        val motion = pageMotion as NavSettleSpec.Tween
        val spec = navigationBarAnimationSpec<Float>(
            showNavigation = false,
            hideMotion = secondaryPageEnterMotion(style),
        )
        val navigationSpace = TargetBasedAnimation(spec, Float.VectorConverter, 98f, 0f)
        val playerGap = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 18f)
        val pageTravel = TargetBasedAnimation(
            androidx.compose.animation.core.tween<Float>(motion.durationMillis, easing = motion.easing),
            Float.VectorConverter,
            0f,
            1f,
        )
        assertEquals(motion.durationMillis * 1_000_000L, navigationSpace.durationNanos)
        assertTrue(spec.easing !== FastOutSlowInEasing)
        for (millis in (0..motion.durationMillis step 16) + motion.durationMillis) {
            val nanos = millis * 1_000_000L
            val fraction = pageTravel.getValueFromNanos(nanos)
            val chromeTravel = 1f - navigationSpace.getValueFromNanos(nanos) / 98f
            val gapTravel = playerGap.getValueFromNanos(nanos) / 18f
            assertEquals("Navigation at $millis ms", fraction, chromeTravel, 0.00001f)
            assertEquals("Player at $millis ms", fraction, gapTravel, 0.00001f)
        }
    }
}
