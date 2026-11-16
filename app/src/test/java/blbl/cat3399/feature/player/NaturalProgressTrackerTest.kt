package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalProgressTrackerTest {
    private val durationMs = 100_000L

    private fun feedContinuous(
        tracker: NaturalProgressTracker,
        fromMs: Long,
        toMs: Long,
        startAtMs: Long = 0L,
        tickMs: Long = 250L,
        speed: Float = 1f,
    ): Double {
        var now = startAtMs
        var pos = fromMs
        var ratio = tracker.onSample(
            key = "aid1:cid1",
            positionMs = pos,
            durationMs = durationMs,
            nowMs = now,
            isPlaying = true,
            playbackSpeed = speed,
            canCountAsNatural = true,
        )
        while (pos < toMs) {
            pos = (pos + tickMs).coerceAtMost(toMs)
            now += tickMs
            ratio = tracker.onSample(
                key = "aid1:cid1",
                positionMs = pos,
                durationMs = durationMs,
                nowMs = now,
                isPlaying = true,
                playbackSpeed = speed,
                canCountAsNatural = true,
            )
        }
        return ratio
    }

    @Test
    fun continuousPlaybackReachesNinetyPercent() {
        val tracker = NaturalProgressTracker()
        val ratio = feedContinuous(tracker, fromMs = 0L, toMs = 92_000L)
        assertEquals(0.92, ratio, 0.01)
    }

    @Test
    fun seekToEndDoesNotCountAsNaturalPlayback() {
        val tracker = NaturalProgressTracker()
        feedContinuous(tracker, fromMs = 0L, toMs = 8_000L)
        // 拖动到 95%：位置跳变，不计入覆盖。
        val ratio =
            tracker.onSample(
                key = "aid1:cid1",
                positionMs = 95_000L,
                durationMs = durationMs,
                nowMs = 8_250L,
                isPlaying = true,
                playbackSpeed = 1f,
                canCountAsNatural = true,
            )
        assertEquals(0.08, ratio, 0.01)
        // 之后继续正常播放到结尾，也只能覆盖 95% 之后那一段。
        val finalRatio = feedContinuous(tracker, fromMs = 95_000L, toMs = 100_000L, startAtMs = 8_250L)
        assertEquals(0.13, finalRatio, 0.01)
    }

    @Test
    fun holdSeekSamplesAreIgnored() {
        val tracker = NaturalProgressTracker()
        feedContinuous(tracker, fromMs = 0L, toMs = 10_000L)
        var now = 10_250L
        var ratio = 0.1
        for (pos in 10_000L..50_000L step 1_000L) {
            now += 250L
            ratio =
                tracker.onSample(
                    key = "aid1:cid1",
                    positionMs = pos,
                    durationMs = durationMs,
                    nowMs = now,
                    isPlaying = true,
                    playbackSpeed = 4f,
                    canCountAsNatural = false,
                )
        }
        assertEquals(0.1, ratio, 0.01)
    }

    @Test
    fun rewatchingTheSameRangeIsNotDoubleCounted() {
        val tracker = NaturalProgressTracker()
        feedContinuous(tracker, fromMs = 0L, toMs = 50_000L, startAtMs = 0L)
        // 退回 20% 后重复观看同一段，覆盖率仍按唯一区间计算。
        val ratio = feedContinuous(tracker, fromMs = 20_000L, toMs = 60_000L, startAtMs = 50_250L)
        assertEquals(0.6, ratio, 0.01)
    }

    @Test
    fun newCidResetsCoverage() {
        val tracker = NaturalProgressTracker()
        feedContinuous(tracker, fromMs = 0L, toMs = 95_000L)
        val ratio =
            tracker.onSample(
                key = "aid1:cid2",
                positionMs = 0L,
                durationMs = durationMs,
                nowMs = 95_250L,
                isPlaying = true,
                playbackSpeed = 1f,
                canCountAsNatural = true,
            )
        assertEquals(0.0, ratio, 0.01)
    }

    @Test
    fun consumeIfFirstTimeOnlyFiresOnce() {
        val tracker = NaturalProgressTracker()
        assertEquals(true, tracker.consumeIfFirstTime())
        assertEquals(false, tracker.consumeIfFirstTime())
    }
}
