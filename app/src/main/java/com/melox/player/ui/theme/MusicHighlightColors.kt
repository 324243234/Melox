package com.melox.player.ui.theme

import androidx.compose.ui.graphics.Color

// User-specified sRGB colors for fixed breadcrumb highlights.
internal fun referenceMusicHighlightColors(isDark: Boolean): Pair<Color, Color> =
    if (isDark) {
        Color(0xFFCD9B21) to Color(0xFF322400)
    } else {
        Color(0xFFEEB652) to Color(0xFFFFF6D9)
    }
