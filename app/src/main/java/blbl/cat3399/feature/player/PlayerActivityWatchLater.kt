package blbl.cat3399.feature.player

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WATCH_LATER_LOG_TAG = "PlayerWatchLater"

/** 自然播放覆盖到整段视频的这个比例后，自动从“稍后再看”里移除。 */
internal const val WATCH_LATER_AUTO_REMOVE_RATIO: Double = 0.9

internal fun PlayerActivity.naturalProgressKey(): String {
    val aid = currentAid?.takeIf { it > 0L }
    return "${aid ?: currentBvid.trim()}:$currentCid"
}

/**
 * 播放进度采样入口（由 updateProgressUi 每 250ms 调用一次）。
 *
 * 拖动进度条、长按快进、倍速跳过都不计入“自然播放”覆盖，只有正常连续播放的部分才算。
 */
internal fun PlayerActivity.maybeTrackWatchLaterProgress(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
) {
    val canCountAsNatural =
        !scrubbing &&
            holdScrubPreviewPosMs == null &&
            keySeekPreviewPosMs == null &&
            holdSeekJob == null

    val ratio =
        watchLaterProgress.onSample(
            key = naturalProgressKey(),
            positionMs = positionMs,
            durationMs = durationMs,
            nowMs = SystemClock.elapsedRealtime(),
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            canCountAsNatural = canCountAsNatural,
        )
    if (ratio < WATCH_LATER_AUTO_REMOVE_RATIO) return
    if (!BiliClient.prefs.playerWatchLaterAutoRemoveEnabled) return
    if (!watchLaterProgress.consumeIfFirstTime()) return
    autoRemoveCurrentVideoFromWatchLater()
}

private fun PlayerActivity.autoRemoveCurrentVideoFromWatchLater() {
    val aid = currentAid?.takeIf { it > 0L }
    val bvid = currentBvid.trim()
    if (aid == null && bvid.isBlank()) return
    val title = currentMainTitle?.trim().orEmpty()

    lifecycleScope.launch {
        val removed =
            withContext(Dispatchers.IO) {
                runCatching {
                    val card =
                        BiliApi.toViewList().firstOrNull { item ->
                            (aid != null && item.aid == aid) || (bvid.isNotBlank() && item.bvid == bvid)
                        } ?: return@runCatching false
                    val targetAid = card.aid?.takeIf { it > 0L } ?: aid ?: return@runCatching false
                    BiliApi.toViewDelete(targetAid)
                    true
                }
            }
        removed
            .onSuccess { ok ->
                AppLog.i(WATCH_LATER_LOG_TAG, "autoRemove ok=$ok aid=${aid ?: -1L} cid=$currentCid")
                if (!ok || isFinishing || isDestroyed) return@onSuccess
                val text = if (title.isBlank()) "已自动移出稍后再看" else "已自动移出稍后再看：$title"
                AppToast.show(this@autoRemoveCurrentVideoFromWatchLater, text)
            }
            .onFailure { t ->
                AppLog.w(WATCH_LATER_LOG_TAG, "autoRemove failed aid=${aid ?: -1L}", t)
            }
    }
}
