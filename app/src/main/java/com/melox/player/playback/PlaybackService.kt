 package com.melox.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.melox.player.MainActivity
import com.melox.player.MeloxApplication
import com.melox.player.R
import com.melox.player.data.playback.PlaybackSnapshotStore
import com.melox.player.data.playback.MiniPlaybackSnapshotStore
import com.melox.player.data.repository.SettingsRepository
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackSnapshot
import com.melox.player.model.PLAYBACK_SPEED_VALUES
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Owns background audio playback and exposes it to Android system media controls. */
internal const val ACTION_CLOSE_APPLICATION_COMMAND =
    "com.melox.player.action.CLOSE_APPLICATION_COMMAND"
internal const val ACTION_SET_PAUSE_FADE = "com.melox.player.action.SET_PAUSE_FADE"
internal const val ACTION_SET_PLAYBACK_INTENT = "com.melox.player.action.SET_PLAYBACK_INTENT"
internal const val ACTION_SET_PLAYBACK_SPEED = "com.melox.player.action.SET_PLAYBACK_SPEED"
internal const val ACTION_SET_HIGH_PRECISION_OUTPUT = "com.melox.player.action.SET_HIGH_PRECISION_OUTPUT"
internal const val EXTRA_PAUSE_FADE = "pause_fade"
internal const val EXTRA_PLAYBACK_INTENT = "playback_intent"
internal const val EXTRA_PLAYBACK_SPEED = "playback_speed"
internal const val EXTRA_HIGH_PRECISION_OUTPUT = "high_precision_output"
internal const val EXTRA_FLOAT_OUTPUT_ACTIVE = "float_output_active"
internal const val EXTRA_CLOSE_AFTER_EXTENSION = "close_after_extension"
internal const val EXTRA_EXTENSION_EXPECTED_INDEX = "extension_expected_index"

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var snapshotStore: PlaybackSnapshotStore
    private lateinit var miniSnapshotStore: MiniPlaybackSnapshotStore
    private lateinit var settingsRepository: SettingsRepository
    private var snapshotDebounceJob: Job? = null
    private var snapshotRestorePending = false
    private var snapshotRestoreSeed: PlaybackSnapshot? = null
    private val snapshotWriteMutex = Mutex()
    private val snapshotWriteSequence = AtomicLong()
    private val lastSnapshotWriteSequence = AtomicLong()
    private var lastSnapshotWriteSucceeded = false
    private var artworkLoadJob: Job? = null
    private var requestedArtworkKey: PlaybackArtworkKey? = null
    private var isClosing = false
    private val cyclePlaybackModeCommand = SessionCommand(ACTION_CYCLE_PLAYBACK_MODE, Bundle.EMPTY)
    private val closeApplicationCommand = SessionCommand(ACTION_CLOSE_APPLICATION_COMMAND, Bundle.EMPTY)
    private val pauseFadeCommand = SessionCommand(ACTION_SET_PAUSE_FADE, Bundle.EMPTY)
    private val playbackIntentCommand = SessionCommand(ACTION_SET_PLAYBACK_INTENT, Bundle.EMPTY)
    private val playbackSpeedCommand = SessionCommand(ACTION_SET_PLAYBACK_SPEED, Bundle.EMPTY)
    private val highPrecisionOutputCommand = SessionCommand(ACTION_SET_HIGH_PRECISION_OUTPUT, Bundle.EMPTY)
    private var playbackVolumeFade: PlaybackVolumeFade? = null
    private var activePlayer: ExoPlayer? = null
    private var highPrecisionOutput = true
    private var floatOutputActive = false
    private var outputGeneration = 0
    private var pauseFadeEnabled = false
    private var deferredPlaybackSpeed: Float? = null
    private var deferredHighPrecisionOutput: Boolean? = null
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player !== activePlayer) return
            playbackVolumeFade?.onEvents()
            if (floatOutputActive && player.playbackParameters.speed != 1f) {
                player.setPlaybackSpeed(1f)
                return
            }
            if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                refreshMediaButtonPreferences(player.currentPlaybackMode())
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                if (player.mediaItemCount == 0) setFloatOutputActive(false)
                scheduleArtworkUpdate(player)
            }
            scheduleSnapshotWrite(
                player = player,
                immediate = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED),
            )
        }
    }
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) = Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    (if (controller.isTrusted) {
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    } else {
                        MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
                    }).buildUpon().apply {
                        if (controller.isTrusted) {
                            add(cyclePlaybackModeCommand)
                            add(closeApplicationCommand)
                            add(pauseFadeCommand)
                            add(playbackIntentCommand)
                            add(playbackSpeedCommand)
                            add(highPrecisionOutputCommand)
                        }
                    }.build(),
                )
                .build(),
        )

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ) = when (customCommand.customAction) {
            ACTION_CYCLE_PLAYBACK_MODE -> {
                cyclePlaybackMode(session.player)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            ACTION_CLOSE_APPLICATION_COMMAND -> {
                if (args.getBoolean(EXTRA_CLOSE_AFTER_EXTENSION)) {
                    closeAfterExtendedTimer(args.getInt(EXTRA_EXTENSION_EXPECTED_INDEX, -1))
                } else {
                    closeApplication()
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            ACTION_SET_PAUSE_FADE -> {
                pauseFadeEnabled = args.getBoolean(EXTRA_PAUSE_FADE)
                playbackVolumeFade?.setEnabled(pauseFadeEnabled)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            ACTION_SET_PLAYBACK_INTENT -> {
                playbackVolumeFade?.requestPlaybackIntent(args.getBoolean(EXTRA_PLAYBACK_INTENT))
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            ACTION_SET_PLAYBACK_SPEED -> {
                val speed = args.getFloat(EXTRA_PLAYBACK_SPEED, 1f)
                if (speed in PLAYBACK_SPEED_VALUES && (!floatOutputActive || speed == 1f)) {
                    if (snapshotRestorePending) deferredPlaybackSpeed = speed
                    else activePlayer?.setPlaybackSpeed(speed)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                } else {
                    Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
            }

            ACTION_SET_HIGH_PRECISION_OUTPUT -> {
                val enabled = args.getBoolean(EXTRA_HIGH_PRECISION_OUTPUT, true)
                if (snapshotRestorePending) deferredHighPrecisionOutput = enabled
                else setHighPrecisionOutput(enabled)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification_melox) }
        setMediaNotificationProvider(notificationProvider)
        snapshotStore = PlaybackSnapshotStore(this)
        miniSnapshotStore = MiniPlaybackSnapshotStore(this)
        settingsRepository = SettingsRepository(this)
        val startupSnapshot = miniSnapshotStore.load()
        snapshotRestoreSeed = startupSnapshot
        PlaybackModeMemory.set(startupSnapshot?.playbackMode ?: PlaybackMode.ORDER)
        val player = createPlayer(highPrecisionOutput)
        activePlayer = player
        playbackVolumeFade = PlaybackVolumeFade(player, serviceScope)
        startupSnapshot?.let { snapshot ->
            player.setMediaItems(
                snapshot.queue.map(PlaybackQueueItem::toMediaItem),
                snapshot.currentIndex,
                snapshot.positionMs,
            )
            player.shuffleModeEnabled = false
            player.repeatMode = snapshot.playbackMode.toPlayerRepeatMode()
        }
        restoreSnapshot(player, startupSnapshot)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setSessionExtras(Bundle().apply { putBoolean(EXTRA_FLOAT_OUTPUT_ACTIVE, false) })
            .setCallback(mediaSessionCallback)
            .setMediaButtonPreferences(mediaButtonPreferences(player.currentPlaybackMode()))
            .build()
        scheduleArtworkUpdate(player)
        (application as MeloxApplication).fairMemoryManager.savePlayback = ::saveMemoryCheckpoint
        serviceScope.launch {
            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                activePlayer?.takeIf(Player::isPlaying)?.let(::scheduleSnapshotWrite)
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.let { player ->
            if (player.shouldPersistCurrentSnapshot()) {
                persistSnapshotBlocking(player)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        playbackVolumeFade?.release()
        playbackVolumeFade = null
        mediaSession?.run {
            if (player.shouldPersistCurrentSnapshot()) {
                persistSnapshotBlocking(player)
            }
            player.release()
            release()
        }
        activePlayer = null
        serviceScope.cancel()
        mediaSession = null
        (application as MeloxApplication).fairMemoryManager.savePlayback = null
        super.onDestroy()
    }

    private fun createPlayer(highPrecision: Boolean): ExoPlayer {
        val generation = ++outputGeneration
        val renderersFactory = PrecisionRenderersFactory(this, highPrecision) { active ->
            serviceScope.launch {
                if (generation == outputGeneration) setFloatOutputActive(active)
            }
        }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, PlaybackExtractorsFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(playerListener)
            }
    }

    private fun setHighPrecisionOutput(enabled: Boolean) {
        val oldPlayer = activePlayer ?: return
        if (enabled == highPrecisionOutput) return
        val session = mediaSession ?: return
        val nextPlayer = createPlayer(enabled)
        val items = (0 until oldPlayer.mediaItemCount).map(oldPlayer::getMediaItemAt)
        val currentIndex = oldPlayer.currentMediaItemIndex
        if (currentIndex in items.indices) {
            nextPlayer.setMediaItems(items, currentIndex, oldPlayer.currentPosition.coerceAtLeast(0L))
        } else if (items.isNotEmpty()) {
            nextPlayer.setMediaItems(items)
        }
        nextPlayer.shuffleModeEnabled = oldPlayer.shuffleModeEnabled
        nextPlayer.repeatMode = oldPlayer.repeatMode
        nextPlayer.setPlaybackSpeed(oldPlayer.playbackParameters.speed)
        val oldFade = playbackVolumeFade
        val keepPlaying = oldPlayer.playWhenReady && oldFade?.hasPendingPause != true
        val shouldPrepare = oldPlayer.playbackState != Player.STATE_IDLE && items.isNotEmpty()
        nextPlayer.playWhenReady = keepPlaying
        session.setPlayer(nextPlayer)
        activePlayer = nextPlayer
        highPrecisionOutput = enabled
        setFloatOutputActive(false)
        oldPlayer.pause()
        oldFade?.release()
        playbackVolumeFade = PlaybackVolumeFade(nextPlayer, serviceScope).apply {
            setEnabled(pauseFadeEnabled)
        }
        oldPlayer.removeListener(playerListener)
        oldPlayer.release()
        artworkLoadJob?.cancel()
        artworkLoadJob = null
        requestedArtworkKey = null
        if (shouldPrepare) {
            nextPlayer.prepare()
        }
        scheduleArtworkUpdate(nextPlayer)
        scheduleSnapshotWrite(nextPlayer, immediate = true)
    }

    private fun setFloatOutputActive(active: Boolean) {
        if (floatOutputActive == active) return
        floatOutputActive = active
        mediaSession?.setSessionExtras(Bundle().apply {
            putBoolean(EXTRA_FLOAT_OUTPUT_ACTIVE, active)
        })
        if (active && activePlayer?.playbackParameters?.speed != 1f) {
            activePlayer?.setPlaybackSpeed(1f)
            serviceScope.launch {
                if (highPrecisionOutput && floatOutputActive) {
                    settingsRepository.setPlaybackSpeed(1f)
                }
            }
        }
    }

    private fun restoreSnapshot(
        player: Player,
        startupSnapshot: PlaybackSnapshot?,
    ) {
        snapshotRestorePending = true
        serviceScope.launch {
            // A controller command issued while the disk read was running wins over restoration.
            val snapshot = withContext(Dispatchers.IO) { snapshotStore.loadUnvalidated() }
            if (isClosing) return@launch
            if (!player.isAwaitingSnapshotRestore(startupSnapshot)) {
                completeSnapshotRestore(player)
                return@launch
            }
            if (snapshot == null) {
                PlaybackModeMemory.set(PlaybackMode.ORDER)
                withContext(Dispatchers.IO) { miniSnapshotStore.clear() }
                player.clearMediaItems()
                completeSnapshotRestore(player)
                return@launch
            }
            PlaybackModeMemory.set(snapshot.playbackMode)
            val previewCurrentItem = player.currentPlaybackQueue()
                .getOrNull(player.currentMediaItemIndex)
            val restoredCurrentIndex = previewCurrentItem?.let { currentItem ->
                snapshot.queue.indexOfFirst { item -> item.isSameQueueSlot(currentItem) }
            }?.takeIf { it >= 0 } ?: snapshot.currentIndex
            val shouldPlay = player.playWhenReady
            val restorePositionMs = if (previewCurrentItem != null) {
                player.currentPosition
            } else {
                snapshot.positionMs
            }.coerceAtLeast(0L)
            val activeSnapshot = snapshot.copy(
                currentIndex = restoredCurrentIndex,
                positionMs = restorePositionMs,
            )
            player.setMediaItems(
                activeSnapshot.queue.map { it.toMediaItem() },
                activeSnapshot.currentIndex,
                restorePositionMs,
            )
            player.shuffleModeEnabled = false
            player.repeatMode = snapshot.playbackMode.toPlayerRepeatMode()
            player.prepare()
            if (shouldPlay) player.play() else player.pause()
            withContext(Dispatchers.IO) {
                miniSnapshotStore.save(activeSnapshot)
            }
            if (isClosing) return@launch

            val validatedSnapshot = withContext(Dispatchers.IO) {
                snapshotStore.validate(activeSnapshot)
            }
            if (isClosing) return@launch
            val reconciledSnapshot = reconcileValidatedPlaybackSnapshot(
                restoredSnapshot = activeSnapshot,
                validatedSnapshot = validatedSnapshot,
                currentQueue = player.currentPlaybackQueue(),
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                playbackMode = player.currentPlaybackMode(),
            )
            if (reconciledSnapshot == null) {
                completeSnapshotRestore(player)
                return@launch
            }
            if (!hasSameQueueSlots(reconciledSnapshot.queue, activeSnapshot.queue)) {
                val keepPlaying = player.playWhenReady
                if (reconciledSnapshot.queue.isEmpty()) {
                    player.clearMediaItems()
                } else {
                    player.setMediaItems(
                        reconciledSnapshot.queue.map { it.toMediaItem() },
                        reconciledSnapshot.currentIndex,
                        reconciledSnapshot.positionMs,
                    )
                    player.shuffleModeEnabled = false
                    player.repeatMode = reconciledSnapshot.playbackMode.toPlayerRepeatMode()
                    player.prepare()
                    if (keepPlaying) player.play() else player.pause()
                }
            }
            completeSnapshotRestore(player)
        }
    }

    private fun completeSnapshotRestore(player: Player) {
        snapshotRestorePending = false
        snapshotRestoreSeed = null
        scheduleSnapshotWrite(player, immediate = true)
        deferredHighPrecisionOutput?.let { enabled ->
            deferredHighPrecisionOutput = null
            setHighPrecisionOutput(enabled)
        }
        deferredPlaybackSpeed?.let { speed ->
            deferredPlaybackSpeed = null
            if (!floatOutputActive || speed == 1f) activePlayer?.setPlaybackSpeed(speed)
        }
    }

    private fun scheduleSnapshotWrite(
        player: Player,
        immediate: Boolean = false,
    ) {
        if (isClosing || snapshotRestorePending) return
        val snapshot = player.toSnapshot()
        val sequence = snapshotWriteSequence.incrementAndGet()
        if (immediate) {
            snapshotDebounceJob?.cancel()
            snapshotDebounceJob = null
            serviceScope.launch { persistSnapshot(snapshot, sequence) }
        } else {
            snapshotDebounceJob?.cancel()
            snapshotDebounceJob = serviceScope.launch {
                delay(SNAPSHOT_DEBOUNCE_MS)
                persistSnapshot(snapshot, sequence)
            }
        }
    }

    private fun scheduleArtworkUpdate(player: Player) {
        if (isClosing) return
        val currentIndex = player.currentMediaItemIndex
        val currentItem = currentIndex
            .takeIf { it in 0 until player.mediaItemCount }
            ?.let(player::getMediaItemAt)
        if (currentItem == null) {
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            requestedArtworkKey = null
            return
        }
        val queueItem = currentItem.toPlaybackQueueItem()
        val artworkKey = queueItem.toPlaybackArtworkKey()
        if (requestedArtworkKey != artworkKey) {
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            requestedArtworkKey = null
        }
        if (player.hasArtwork() || currentItem.mediaMetadata.hasArtwork()) return
        if (requestedArtworkKey == artworkKey) return

        requestedArtworkKey = artworkKey
        artworkLoadJob = serviceScope.launch {
            val artworkData = withContext(Dispatchers.IO) {
                loadPlaybackArtworkData(queueItem.contentUri)
            } ?: return@launch
            val activeIndex = player.currentMediaItemIndex
            val activeItem = activeIndex
                .takeIf { it in 0 until player.mediaItemCount }
                ?.let(player::getMediaItemAt)
                ?: return@launch
            if (
                activeItem.toPlaybackQueueItem().toPlaybackArtworkKey() != artworkKey ||
                player.hasArtwork() ||
                activeItem.mediaMetadata.hasArtwork()
            ) {
                return@launch
            }
            player.replaceMediaItem(activeIndex, activeItem.withArtworkData(artworkData))
        }
    }

    private fun Player.hasArtwork(): Boolean = mediaMetadata.hasArtwork()

    private fun androidx.media3.common.MediaMetadata.hasArtwork(): Boolean =
        artworkData != null || artworkUri != null

    private suspend fun persistSnapshot(
        snapshot: PlaybackSnapshot,
        sequence: Long,
    ) = withContext(Dispatchers.IO) {
        snapshotWriteMutex.withLock {
            if (sequence <= lastSnapshotWriteSequence.get()) {
                return@withLock lastSnapshotWriteSucceeded
            }
            val fullSaved = runCatching { snapshotStore.save(snapshot) }.isSuccess
            val miniSaved = runCatching { miniSnapshotStore.save(snapshot) }.isSuccess
            (fullSaved && miniSaved).also { saved ->
                // Even a partial write must not let an older queued snapshot replace it.
                lastSnapshotWriteSequence.set(sequence)
                lastSnapshotWriteSucceeded = saved
            }
        }
    }

    private suspend fun saveMemoryCheckpoint(): Boolean = withContext(Dispatchers.Main.immediate) {
        // Never replace the full disk queue with the bounded startup preview, or
        // race the restore path's mini-snapshot write. The receiver owns the timeout.
        while (snapshotRestorePending && !isClosing && mediaSession != null) delay(25L)
        val player = mediaSession?.player ?: return@withContext false
        if (isClosing) return@withContext false
        val snapshot = player.toSnapshot()
        val sequence = snapshotWriteSequence.incrementAndGet()
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = null
        persistSnapshot(snapshot, sequence)
    }

    private fun persistSnapshotBlocking(player: Player) {
        val snapshot = player.toSnapshot()
        val sequence = snapshotWriteSequence.incrementAndGet()
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = null
        runBlocking(Dispatchers.IO) {
            persistSnapshot(snapshot, sequence)
        }
    }

    private fun Player.toSnapshot(): PlaybackSnapshot {
        val rawQueue = currentPlaybackQueue()
        val playbackMode = currentPlaybackMode()
        val queue = rawQueue.map { item -> item.copy(playbackMode = playbackMode) }
        return PlaybackSnapshot(
            queue = queue,
            currentIndex = currentMediaItemIndex.takeIf { queue.isNotEmpty() } ?: -1,
            positionMs = currentPosition.coerceAtLeast(0L),
            playbackMode = playbackMode,
        )
    }

    private fun Player.isAwaitingSnapshotRestore(
        startupSnapshot: PlaybackSnapshot?,
    ): Boolean = if (startupSnapshot == null) {
        mediaItemCount == 0
    } else {
        hasSameQueueSlots(currentPlaybackQueue(), startupSnapshot.queue)
    }

    private fun Player.shouldPersistCurrentSnapshot(): Boolean =
        !snapshotRestorePending || !isAwaitingSnapshotRestore(snapshotRestoreSeed)

    private fun cyclePlaybackMode(player: Player) {
        if (player.mediaItemCount == 0 || isClosing) return
        val targetMode = nextPlaybackMode(player.currentPlaybackMode())
        val reordered = reorderQueueForPlaybackMode(
            queue = player.currentPlaybackQueue(),
            currentIndex = player.currentMediaItemIndex,
            targetMode = targetMode,
        )
        PlaybackModeMemory.set(targetMode)
        player.shuffleModeEnabled = false
        player.repeatMode = targetMode.toPlayerRepeatMode()
        player.applyPlaybackQueue(
            targetQueue = reordered.queue,
            targetCurrentIndex = reordered.currentIndex,
        )
        refreshMediaButtonPreferences(targetMode)
    }

    private fun closeApplication() {
        if (isClosing) return
        isClosing = true
        Process.killProcess(Process.myPid())
    }

    private fun closeAfterExtendedTimer(expectedIndex: Int) {
        if (isClosing) return
        isClosing = true
        mediaSession?.player?.let { player ->
            playbackVolumeFade?.setEnabled(false)
            player.pause()
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex in 0 until player.mediaItemCount) {
                val targetIndex = nextItemIndexAfterExtendedTimer(
                    currentIndex = currentIndex,
                    expectedIndex = expectedIndex,
                    itemCount = player.mediaItemCount,
                )
                player.seekToDefaultPosition(targetIndex)
            }
            if (player.shouldPersistCurrentSnapshot()) persistSnapshotBlocking(player)
        }
        Process.killProcess(Process.myPid())
    }

    private fun refreshMediaButtonPreferences(mode: PlaybackMode) {
        mediaSession?.setMediaButtonPreferences(mediaButtonPreferences(mode))
    }

    private fun mediaButtonPreferences(mode: PlaybackMode): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setSessionCommand(cyclePlaybackModeCommand)
            .setCustomIconResId(mode.notificationIconResId())
            .setDisplayName(getString(mode.notificationLabelResId()))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setSessionCommand(closeApplicationCommand)
            .setCustomIconResId(R.drawable.ic_notification_close)
            .setDisplayName(getString(R.string.notification_close))
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private companion object {
        const val ACTION_CYCLE_PLAYBACK_MODE =
            "com.melox.player.action.CYCLE_PLAYBACK_MODE"
        const val SNAPSHOT_DEBOUNCE_MS = 350L
        const val SNAPSHOT_INTERVAL_MS = 5_000L
    }
}

internal fun nextItemIndexAfterExtendedTimer(
    currentIndex: Int,
    expectedIndex: Int,
    itemCount: Int,
): Int = if (itemCount > 0 && currentIndex == expectedIndex) {
    (currentIndex + 1) % itemCount
} else {
    currentIndex
}

private fun PlaybackMode.notificationIconResId(): Int = when (this) {
    PlaybackMode.ORDER -> R.drawable.ic_notification_list_loop
    PlaybackMode.REPEAT_ONE -> R.drawable.ic_notification_repeat_one
    PlaybackMode.RANDOM -> R.drawable.ic_notification_shuffle
}

private fun PlaybackMode.notificationLabelResId(): Int = when (this) {
    PlaybackMode.ORDER -> R.string.notification_playback_mode_list_loop
    PlaybackMode.REPEAT_ONE -> R.string.notification_playback_mode_repeat_one
    PlaybackMode.RANDOM -> R.string.notification_playback_mode_shuffle
}

internal fun reconcileValidatedPlaybackSnapshot(
    restoredSnapshot: PlaybackSnapshot,
    validatedSnapshot: PlaybackSnapshot?,
    currentQueue: List<PlaybackQueueItem>,
    currentIndex: Int,
    positionMs: Long,
    playbackMode: PlaybackMode,
): PlaybackSnapshot? {
    if (!hasSameQueueSlots(currentQueue, restoredSnapshot.queue)) return null
    val validatedQueue = validatedSnapshot?.queue.orEmpty().map { item ->
        item.copy(playbackMode = playbackMode)
    }
    if (validatedQueue.isEmpty()) {
        return PlaybackSnapshot(
            queue = emptyList(),
            currentIndex = -1,
            positionMs = 0L,
            playbackMode = playbackMode,
        )
    }
    val currentItem = currentQueue.getOrNull(currentIndex)
    val retainedCurrentIndex = currentItem?.let { item ->
        validatedQueue.indexOfFirst { candidate -> candidate.isSameQueueSlot(item) }
    } ?: -1
    return PlaybackSnapshot(
        queue = validatedQueue,
        currentIndex = retainedCurrentIndex.takeIf { it >= 0 } ?: 0,
        positionMs = if (retainedCurrentIndex >= 0) positionMs.coerceAtLeast(0L) else 0L,
        playbackMode = playbackMode,
    )
}

internal fun hasSameQueueSlots(
    first: List<PlaybackQueueItem>,
    second: List<PlaybackQueueItem>,
): Boolean = first.size == second.size && first.indices.all { index ->
    first[index].isSameQueueSlot(second[index])
}

private fun PlaybackQueueItem.isSameQueueSlot(other: PlaybackQueueItem): Boolean =
    mediaId == other.mediaId &&
        contentUri == other.contentUri &&
        sourceOrder == other.sourceOrder
