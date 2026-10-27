package com.melox.player.ui.component.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.melox.player.R
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun AudioEffectPreferences(
    audioSessionId: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val effectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun openEffect(intent: Intent) {
        try {
            effectLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.playback_audio_effect_unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.playback_audio_effect_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    val miSoundIntent = remember {
        Intent().setClassName("com.miui.misound", "com.miui.misound.HeadsetSettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val hasMiSound = remember(context) {
        miSoundIntent.resolveActivityInfo(context.packageManager, 0)?.let { it.enabled && it.exported } == true
    }
    Column(modifier) {
        ArrowPreference(
            title = stringResource(R.string.playback_system_equalizer),
            startAction = {
                Image(
                    painter = painterResource(R.drawable.ic_audio_effect_android),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                )
            },
            onClick = {
                openEffect(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    .apply {
                        if (audioSessionId > 0) putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                    })
            },
        )
        if (hasMiSound) {
            ArrowPreference(
                title = stringResource(R.string.playback_misound),
                startAction = {
                    Image(
                        painter = painterResource(R.drawable.ic_audio_effect_xiaomi),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp).size(24.dp),
                    )
                },
                onClick = { openEffect(miSoundIntent) },
            )
        }
    }
}
