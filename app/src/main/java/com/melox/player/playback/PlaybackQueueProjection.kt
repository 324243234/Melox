package com.melox.player.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem

/** Position samples reuse immutable queue metadata until its timeline or mode changes. */
internal class PlaybackQueueProjection {
    private var owner: Player? = null
    private var timeline: Timeline? = null
    private var mode: PlaybackMode? = null
    private var items: List<PlaybackQueueItem> = emptyList()

    fun queue(player: Player, playbackMode: PlaybackMode): List<PlaybackQueueItem> {
        val currentTimeline = player.currentTimeline
        if (owner !== player || timeline !== currentTimeline || mode != playbackMode) {
            owner = player
            timeline = currentTimeline
            mode = playbackMode
            items = player.currentPlaybackQueue().map { item ->
                if (item.playbackMode == playbackMode) item else item.copy(playbackMode = playbackMode)
            }
        }
        return items
    }
}
