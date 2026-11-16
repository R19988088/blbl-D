package blbl.cat3399.feature.player

/**
 * 记录“自然播放”真正覆盖到的进度区间。
 *
 * 只有连续播放（没有拖动进度条、没有长按快进/倍速跳过）产生的位移才计入覆盖，
 * 所以把进度条拖到 95% 不会被当成“看完了”，重复回看同一段也不会重复计数。
 */
internal class NaturalProgressTracker {
    private var key: String = ""
    private val intervals = ArrayList<LongArray>()
    private var coveredMs: Long = 0L
    private var lastPositionMs: Long = -1L
    private var lastSampleAtMs: Long = -1L
    private var consumed: Boolean = false

    fun reset(key: String) {
        this.key = key
        intervals.clear()
        coveredMs = 0L
        lastPositionMs = -1L
        lastSampleAtMs = -1L
        consumed = false
    }

    /** 发生 seek / discontinuity 后重新对齐采样锚点，避免把跳变算成自然播放。 */
    fun invalidateAnchor() {
        lastPositionMs = -1L
        lastSampleAtMs = -1L
    }

    /** 阈值只处理一次：返回 true 表示本次是第一次触发。 */
    fun consumeIfFirstTime(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    fun coveredMs(): Long = coveredMs

    fun coverageRatio(durationMs: Long): Double {
        if (durationMs <= 0L) return 0.0
        return (coveredMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 采样一次播放进度。
     *
     * @param canCountAsNatural 正在拖动 / 长按快进 / 撤销预览时为 false：只对齐锚点，不计入覆盖。
     * @return 当前覆盖比例（0..1，duration 未知时为 0）。
     */
    fun onSample(
        key: String,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        canCountAsNatural: Boolean,
    ): Double {
        if (key != this.key) reset(key)
        if (durationMs <= 0L || positionMs < 0L) {
            lastPositionMs = positionMs
            lastSampleAtMs = nowMs
            return coverageRatio(durationMs)
        }

        val prevPos = lastPositionMs
        val prevAt = lastSampleAtMs
        lastPositionMs = positionMs
        lastSampleAtMs = nowMs

        if (!canCountAsNatural || !isPlaying || prevPos < 0L || prevAt <= 0L) {
            return coverageRatio(durationMs)
        }

        val delta = positionMs - prevPos
        if (delta <= 0L) return coverageRatio(durationMs)

        val elapsedMs = (nowMs - prevAt).coerceAtLeast(1L).coerceAtMost(MAX_SAMPLE_ELAPSED_MS)
        val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val maxNaturalDelta = (elapsedMs * speed * NATURAL_DELTA_TOLERANCE + NATURAL_DELTA_SLACK_MS).toLong()
        if (delta > maxNaturalDelta) return coverageRatio(durationMs)

        addInterval(prevPos, positionMs.coerceAtMost(durationMs))
        return coverageRatio(durationMs)
    }

    private fun addInterval(startMs: Long, endMs: Long) {
        val start = startMs.coerceAtLeast(0L)
        if (endMs <= start) return
        var mergedStart = start
        var mergedEnd = endMs
        val next = ArrayList<LongArray>(intervals.size + 1)
        var inserted = false
        for (interval in intervals) {
            if (interval[1] < mergedStart) {
                next.add(interval)
                continue
            }
            if (interval[0] > mergedEnd) {
                if (!inserted) {
                    next.add(longArrayOf(mergedStart, mergedEnd))
                    inserted = true
                }
                next.add(interval)
                continue
            }
            mergedStart = minOf(mergedStart, interval[0])
            mergedEnd = maxOf(mergedEnd, interval[1])
        }
        if (!inserted) next.add(longArrayOf(mergedStart, mergedEnd))
        intervals.clear()
        intervals.addAll(next)
        coveredMs = next.sumOf { it[1] - it[0] }
    }

    private companion object {
        const val MAX_SAMPLE_ELAPSED_MS: Long = 1_200L
        const val NATURAL_DELTA_TOLERANCE: Double = 1.6
        const val NATURAL_DELTA_SLACK_MS: Long = 500L
    }
}
