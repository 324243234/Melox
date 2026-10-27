package com.melox.player.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melox.player.R
import com.melox.player.model.AppSettings
import com.melox.player.ui.component.AdaptiveTopAppBar
import com.melox.player.ui.component.BlurredBar
import com.melox.player.ui.component.miuixBarColor
import com.melox.player.ui.component.rememberBlurBackdrop
import com.melox.player.ui.component.PageScaffold
import com.melox.player.ui.component.PageCard as Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun InterfaceStyleSettingsScreen(
    settings: AppSettings,
    bottomContentPadding: Dp,
    liquidGlassSupported: Boolean,
    listState: LazyListState,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    onBlurChange: (Boolean) -> Unit,
    onProgressiveTopBarBlurChange: (Boolean) -> Unit,
    onSmallPlayerBarChange: (Boolean) -> Unit,
    onHideBottomBarChange: (Boolean) -> Unit,
    onFloatingBottomBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val blurSupported = liquidGlassSupported
    var blurChecked by remember(settings.blurEnabled) {
        mutableStateOf(settings.blurEnabled)
    }
    var progressiveTopBarBlurChecked by remember(settings.progressiveTopBarBlurEnabled) {
        mutableStateOf(settings.progressiveTopBarBlurEnabled)
    }
    var smallPlayerBarChecked by remember(settings.smallPlayerBar) {
        mutableStateOf(settings.smallPlayerBar)
    }
    var hideBottomBarChecked by remember(settings.hideBottomBar) {
        mutableStateOf(settings.hideBottomBar)
    }
    var floatingBottomBarChecked by remember(settings.floatingBottomBar) {
        mutableStateOf(settings.floatingBottomBar)
    }
    var liquidGlassChecked by remember(settings.liquidGlass) {
        mutableStateOf(settings.liquidGlass)
    }
    var topBarBackdropCaptureCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val topBarBackdrop = rememberBlurBackdrop(captureCoordinates = { topBarBackdropCaptureCoordinates })
    PageScaffold(
        topBar = {
            BlurredBar(
                backdrop = topBarBackdrop,
                blurEnabled = topBarBackdrop != null,
                scrollBehavior = scrollBehavior,
            ) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_interface_style_title),
                    color = topBarBackdrop.miuixBarColor(),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    topBarBackdrop?.let { Modifier.onGloballyPositioned { coordinates -> topBarBackdropCaptureCoordinates = coordinates }.layerBackdrop(it) } ?: Modifier,
                ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = listState,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding() + 12.dp,
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = maxOf(
                        padding.calculateBottomPadding(),
                        bottomContentPadding,
                    ) + 16.dp,
                ),
                overscrollEffect = null,
            ) {
                item(key = "interface_blur") {
                    InterfaceStyleCard {
                        SwitchPreference(
                            checked = blurChecked && blurSupported,
                            onCheckedChange = { checked ->
                                blurChecked = checked
                                onBlurChange(checked)
                            },
                            title = stringResource(R.string.settings_blur_title),
                            summary = stringResource(
                                if (blurSupported) {
                                    R.string.settings_blur_summary
                                } else {
                                    R.string.settings_blur_unsupported
                                },
                            ),
                            enabled = blurSupported,
                        )
                        AnimatedVisibility(
                            visible = blurChecked && blurSupported,
                            enter = fadeIn(animationSpec = tween(200)) +
                                expandVertically(animationSpec = tween(250)),
                            exit = fadeOut(animationSpec = tween(150)) +
                                shrinkVertically(animationSpec = tween(200)),
                            label = "progressiveTopBarBlurPreferenceVisibility",
                        ) {
                            SwitchPreference(
                                checked = progressiveTopBarBlurChecked,
                                onCheckedChange = { checked ->
                                    progressiveTopBarBlurChecked = checked
                                    onProgressiveTopBarBlurChange(checked)
                                },
                                title = stringResource(
                                    R.string.settings_progressive_top_bar_blur_title,
                                ),
                            )
                        }
                    }
                }
                item(key = "interface_bottom_bar") {
                    InterfaceStyleCard {
                        SwitchPreference(
                            checked = floatingBottomBarChecked,
                            onCheckedChange = { checked ->
                                floatingBottomBarChecked = checked
                                onFloatingBottomBarChange(checked)
                            },
                            title = stringResource(R.string.settings_floating_bottom_bar_title),
                            summary = stringResource(R.string.settings_floating_bottom_bar_summary),
                        )
                        AnimatedVisibility(
                            visible = floatingBottomBarChecked,
                            enter = fadeIn(animationSpec = tween(200)) +
                                expandVertically(animationSpec = tween(250)),
                            exit = fadeOut(animationSpec = tween(150)) +
                                shrinkVertically(animationSpec = tween(200)),
                            label = "liquidGlassPreferenceVisibility",
                        ) {
                            SwitchPreference(
                                checked = liquidGlassChecked && liquidGlassSupported,
                                onCheckedChange = { checked ->
                                    liquidGlassChecked = checked
                                    onLiquidGlassChange(checked)
                                },
                                title = stringResource(R.string.settings_liquid_glass_title),
                                summary = stringResource(
                                    if (liquidGlassSupported) {
                                        R.string.settings_liquid_glass_summary
                                    } else {
                                        R.string.settings_liquid_glass_unsupported
                                    },
                                ),
                                enabled = liquidGlassSupported,
                            )
                        }
                        SwitchPreference(
                            checked = smallPlayerBarChecked,
                            onCheckedChange = { checked ->
                                smallPlayerBarChecked = checked
                                onSmallPlayerBarChange(checked)
                            },
                            title = stringResource(R.string.settings_small_player_bar_title),
                        )
                        SwitchPreference(
                            checked = hideBottomBarChecked,
                            onCheckedChange = { checked ->
                                hideBottomBarChecked = checked
                                onHideBottomBarChange(checked)
                            },
                            title = stringResource(R.string.settings_hide_bottom_bar_title),
                            summary = stringResource(R.string.settings_hide_bottom_bar_summary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InterfaceStyleCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        content = { content() },
    )
}
