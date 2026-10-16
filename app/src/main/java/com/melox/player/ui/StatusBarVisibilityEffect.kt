package com.melox.player.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun StatusBarVisibilityEffect(activity: Activity?, hidden: Boolean) {
    val window = activity?.window
    val controller = remember(window) {
        window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }
    val originalBehavior = remember(controller) { controller?.systemBarsBehavior }
    DisposableEffect(window, controller) {
        val originallyVisible = window?.decorView?.let(ViewCompat::getRootWindowInsets)
            ?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        onDispose {
            controller?.let {
                it.systemBarsBehavior = originalBehavior
                    ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                if (originallyVisible) {
                    it.show(WindowInsetsCompat.Type.statusBars())
                } else {
                    it.hide(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
    }
    LaunchedEffect(controller, hidden) {
        controller?.let {
            it.systemBarsBehavior = if (hidden) {
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                originalBehavior ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
            if (hidden) {
                it.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                it.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}
