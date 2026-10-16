package com.melox.player.playback

import androidx.media3.common.Player

/** Runs after MediaSession has executed the controller's complete command batch. */
internal fun Player.resumeAfterSystemTrackSkip(commands: Player.Commands) {
    val skipped = commands.contains(Player.COMMAND_SEEK_TO_NEXT) ||
        commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS) ||
        commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) ||
        commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    resumeAfterSystemTrackSkip(skipped, commands.contains(Player.COMMAND_PLAY_PAUSE))
}

internal fun Player.resumeAfterSystemTrackSkip(skipped: Boolean, playPauseRequested: Boolean) {
    if (!skipped || playPauseRequested || mediaItemCount == 0 || playWhenReady) return
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}
