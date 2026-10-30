package com.ella.music.ui.player

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the active MV surface synchronized to the audio player's clock.
 */
internal data class MusicVideoPlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playWhenReady: Boolean = false
)

internal object MusicVideoPlaybackBridge {
    private data class Entry(
        val snapshot: MutableStateFlow<MusicVideoPlaybackSnapshot> =
            MutableStateFlow(MusicVideoPlaybackSnapshot()),
        @Volatile var playWhenReady: Boolean = false,
        // A tap reaches the audio controller asynchronously. Keep that direct user intent until
        // the controller publishes the matching state, so an old audio snapshot cannot restart MV.
        @Volatile var pendingPlayWhenReady: Boolean? = null,
        // Keep a short-lived manual override after the matching audio callback as well. The
        // callback order on a recreated MediaController can be play -> pause -> play; clearing the
        // pending flag on the first pause callback would let the final stale `play` restart MV.
        @Volatile var manualOverride: Boolean? = null,
        @Volatile var manualOverrideUntilMs: Long = 0L,
        @Volatile var syncPositionMs: Long? = null,
        @Volatile var syncDurationMs: Long? = null,
        @Volatile var player: Player? = null,
        /** 上一次请求的音频位置，用来识别「用户拖动」造成的大跳。 */
        @Volatile var lastTargetMs: Long = Long.MIN_VALUE,
        /** 上一次下发 seek 的时刻，避免连续 seek 抖动。 */
        @Volatile var lastSeekAtMs: Long = 0L
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun keyFor(source: DynamicCoverSource): String =
        source.playbackOwnerKey.ifBlank { source.failureKey }

    fun snapshot(source: DynamicCoverSource?): StateFlow<MusicVideoPlaybackSnapshot> {
        val key = source?.let(::keyFor).orEmpty()
        return entries.getOrPut(key) { Entry() }.snapshot
    }

    fun attach(source: DynamicCoverSource, player: Player) {
        if (source.role != PlayerVideoRole.MusicVideo) return
        val entry = entries.getOrPut(keyFor(source)) { Entry() }
        entry.player = player
        player.playWhenReady = entry.playWhenReady
        applySync(entry, player)
        publish(source, player)
    }

    fun publish(source: DynamicCoverSource, player: Player) {
        if (source.role != PlayerVideoRole.MusicVideo) return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val entry = entries.getOrPut(keyFor(source)) { Entry() }
        val manualOverride = entry.manualOverride
            ?.takeIf { SystemClock.elapsedRealtime() < entry.manualOverrideUntilMs }
        if (manualOverride == null) {
            entry.manualOverride = null
            entry.manualOverrideUntilMs = 0L
            entry.playWhenReady = player.playWhenReady
        } else if (!manualOverride) {
            entry.playWhenReady = false
        }
        entry.snapshot.value = MusicVideoPlaybackSnapshot(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            playWhenReady = entry.playWhenReady
        )
    }

    fun detach(source: DynamicCoverSource, player: Player) {
        if (source.role != PlayerVideoRole.MusicVideo) return
        entries[keyFor(source)]?.takeIf { it.player === player }?.let { entry ->
            entry.player = null
            // A detached surface cannot be restarted by a queued callback. Drop the manual
            // override with it so reopening the MV after a pause does not inherit a stale
            // three-second pause window.
            entry.manualOverride = null
            entry.manualOverrideUntilMs = 0L
            entry.pendingPlayWhenReady = null
        }
    }

    fun seekToProgress(source: DynamicCoverSource?, progress: Float) {
        val resolvedSource = source ?: return
        val entry = entries[keyFor(resolvedSource)] ?: return
        val player = entry.player ?: return
        val duration = player.duration.takeIf { it > 0L } ?: entry.snapshot.value.durationMs
        if (duration <= 0L) return
        player.seekTo((duration * progress.coerceIn(0f, 1f)).toLong())
        publish(resolvedSource, player)
    }

    fun setPlaying(source: DynamicCoverSource?, playing: Boolean) {
        val resolvedSource = source ?: return
        setPlaying(keyFor(resolvedSource), playing)
    }

    /**
     * Records playback intent before the MV source has finished resolving.  This lets a lyric
     * double-tap pause the future landscape decoder as well as an already attached one. Pausing
     * is immediate; resuming waits for the audio controller so the video cannot start on a stale
     * clock and immediately seek back.
     */
    fun setPlaying(playbackOwnerKey: String, playing: Boolean) {
        if (playbackOwnerKey.isBlank()) return
        val entry = entries.getOrPut(playbackOwnerKey) { Entry() }
        entry.manualOverride = playing
        entry.manualOverrideUntilMs =
            SystemClock.elapsedRealtime() + MANUAL_OVERRIDE_GUARD_MS
        entry.pendingPlayWhenReady = playing
        if (!playing) {
            entry.playWhenReady = false
            entry.player?.playWhenReady = false
            entry.snapshot.value = entry.snapshot.value.copy(playWhenReady = playing)
        }
    }

    fun syncToAudio(
        source: DynamicCoverSource,
        positionMs: Long,
        audioDurationMs: Long?,
        playing: Boolean
    ) {
        if (source.role != PlayerVideoRole.MusicVideo) return
        val entry = entries.getOrPut(keyFor(source)) { Entry() }
        // Keep a negative target: a positive LunaBeat delay means the MV must remain on its
        // first frame until the audio clock reaches that delay.
        entry.syncPositionMs = positionMs
        entry.syncDurationMs = audioDurationMs?.coerceAtLeast(0L)
        val manualOverride = entry.manualOverride
            ?.takeIf { SystemClock.elapsedRealtime() < entry.manualOverrideUntilMs }
            ?: run {
                entry.manualOverride = null
                entry.manualOverrideUntilMs = 0L
                null
            }
        entry.playWhenReady = when (manualOverride) {
            false -> {
                // Keep a manual pause authoritative for the guard window even after the audio
                // controller reports the requested pause. This filters a queued stale play event.
                entry.pendingPlayWhenReady = null
                false
            }
            true -> {
                if (playing) {
                    entry.pendingPlayWhenReady = null
                    entry.manualOverride = null
                    entry.manualOverrideUntilMs = 0L
                    true
                } else {
                    // Audio has not resumed yet. Keep the silent decoder on the shared clock.
                    false
                }
            }
            null -> when (val pendingPlaying = entry.pendingPlayWhenReady) {
                false -> {
                    if (!playing) entry.pendingPlayWhenReady = null
                    false
                }
                true -> {
                    if (playing) {
                        entry.pendingPlayWhenReady = null
                        true
                    } else {
                        // Audio has not resumed yet. Keep the silent decoder on the shared clock.
                        false
                    }
                }
                null -> playing
            }
        }
        entry.player?.let { player ->
            applySync(entry, player)
            publish(source, player)
        }
    }

    private fun applySync(entry: Entry, player: Player) {
        val requestedPosition = entry.syncPositionMs ?: return
        val audioDuration = entry.syncDurationMs ?: Long.MAX_VALUE
        val videoDuration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val nonNegativePosition = requestedPosition.coerceAtLeast(0L)
        val target = if (nonNegativePosition >= videoDuration) {
            (videoDuration - 1L).coerceAtLeast(0L)
        } else {
            nonNegativePosition.coerceAtMost(audioDuration)
        }
        // 正常播放让视频走自己的时钟；只有漂移超过阈值才纠正，避免频繁 seek 造成卡顿。
        // 用户拖动进度条会表现为「目标位置大幅跳变」，此时必须立刻对齐，不能等阈值。
        val now = SystemClock.elapsedRealtime()
        val targetJumped = entry.lastTargetMs == Long.MIN_VALUE ||
            kotlin.math.abs(target - entry.lastTargetMs) >= SEEK_JUMP_MS
        entry.lastTargetMs = target
        val drift = target - player.currentPosition
        val driftAbs = kotlin.math.abs(drift)
        val seekingRecently = now - entry.lastSeekAtMs < SEEK_SETTLE_MS
        val shouldSeek = when {
            targetJumped -> driftAbs > SEEK_MIN_DRIFT_MS
            driftAbs > BIG_DRIFT_MS -> true
            driftAbs > MUSIC_VIDEO_RESYNC_TOLERANCE_MS -> !seekingRecently
            else -> false
        }
        if (shouldSeek) {
            player.seekTo(target)
            entry.lastSeekAtMs = now
        }
        player.playWhenReady = entry.playWhenReady && requestedPosition >= 0L &&
            requestedPosition < videoDuration && requestedPosition < audioDuration
    }

    /** 允许的稳态漂移；超过就纠正。 */
    private const val MUSIC_VIDEO_RESYNC_TOLERANCE_MS = 150L
    /** 超过这个漂移无条件纠正（即使刚 seek 过）。 */
    private const val BIG_DRIFT_MS = 900L
    /** 目标位置跳变超过这个值视为用户拖动，立即对齐。 */
    private const val SEEK_JUMP_MS = 600L
    /** 跳转后仍允许的最小误差，避免无意义的极小 seek。 */
    private const val SEEK_MIN_DRIFT_MS = 80L
    /** 一次 seek 之后的稳定窗口，窗口内不做小幅纠正。 */
    private const val SEEK_SETTLE_MS = 400L
    private const val MANUAL_OVERRIDE_GUARD_MS = 3_000L
}
