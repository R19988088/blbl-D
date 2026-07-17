package blbl.cat3399.feature.player

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun PlayerActivity.seekRelative(deltaMs: Long) {
    seekRelative(deltaMs, danmakuImmediate = true)
}

internal fun PlayerActivity.seekRelative(deltaMs: Long, danmakuImmediate: Boolean) {
    val exo = player ?: return
    val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
    val next = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
    exo.seekTo(next)
    requestDanmakuSegmentsForPosition(next, immediate = danmakuImmediate)
}

internal fun PlayerActivity.startProgressLoop() {
    progressJob?.cancel()
    progressJob = lifecycleScope.launch {
        while (isActive) {
            updateProgressUi()
            delay(250)
        }
    }
}

internal fun PlayerActivity.toggleControls() {
    if (isTouchLocked()) return
    val willShow = osdMode == PlayerActivity.OsdMode.Hidden
    if (!willShow) {
        binding.settingsPanel.visibility = View.GONE
        binding.commentsPanel.visibility = View.GONE
    }
    setControlsVisible(willShow)
}

internal fun PlayerActivity.setControlsVisible(visible: Boolean) {
    val show = (visible || isSidePanelVisible() || isSponsorSubmitPanelVisible()) && !isTouchLocked()
    seekOsdHideJob?.cancel()
    seekOsdHideJob = null
    seekOsdToken = 0L
    transientSeekOsdVisible = false
    osdMode = if (show) PlayerActivity.OsdMode.Full else PlayerActivity.OsdMode.Hidden

    controlsVisibilityAnimator?.cancel()
    controlsVisibilityAnimator = null
    if (show) {
        if (binding.bottomBar.visibility != View.VISIBLE) {
            binding.bottomBar.alpha = 0f
            binding.bottomBar.translationY = binding.bottomBar.resources.displayMetrics.density * 8f
            binding.bottomBar.visibility = View.VISIBLE
        }
        controlsVisibilityAnimator =
            binding.bottomBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(PlayerActivity.CONTROLS_VISIBILITY_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { controlsVisibilityAnimator = null }
        controlsVisibilityAnimator?.start()
    } else if (binding.bottomBar.visibility == View.VISIBLE) {
        controlsVisibilityAnimator =
            binding.bottomBar.animate()
                .alpha(0f)
                .translationY(binding.bottomBar.resources.displayMetrics.density * 8f)
                .setDuration(PlayerActivity.CONTROLS_VISIBILITY_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (osdMode == PlayerActivity.OsdMode.Hidden) binding.bottomBar.visibility = View.GONE
                    binding.bottomBar.translationY = 0f
                    controlsVisibilityAnimator = null
                }
        controlsVisibilityAnimator?.start()
    } else {
        binding.bottomBar.alpha = 0f
        binding.bottomBar.translationY = 0f
    }

    binding.controlsRow.visibility = if (show) View.VISIBLE else View.GONE
    binding.tvTime.visibility = if (show) View.VISIBLE else View.GONE
    updateTopBarUi()
    updateUpQuickCardUi()
    if (show) {
        applyBottomBarFullLayout()
    } else {
        binding.recyclerCollectionThumbs.visibility = View.GONE
        binding.videoShotPreview.visibility = View.GONE
        videoShotFetchJob?.cancel()
    }
    refreshCollectionThumbsStrip(show = show)
    syncPlayerInfoPanelVisibility()
    updatePersistentBottomProgressBarVisibility()
    onTouchOverlayStateChanged()
    if (visible) noteUserInteraction() else autoHideJob?.cancel()
}

internal fun PlayerActivity.isTopBarContentVisible(): Boolean = osdMode != PlayerActivity.OsdMode.Hidden

internal fun PlayerActivity.updateClockVisibility() {
    val showClock = isTopBarContentVisible() || BiliClient.prefs.playerPersistentClockEnabled
    binding.tvClock.visibility = if (showClock) View.VISIBLE else View.GONE
}

internal fun PlayerActivity.updateTopBarUi() {
    val showContent = isTopBarContentVisible()
    binding.topBar.visibility = View.VISIBLE
    if (showContent) {
        binding.topBar.setBackgroundResource(R.drawable.bg_player_top_scrim_strong)
    } else {
        binding.topBar.background = null
    }
    val contentVisibility = if (showContent) View.VISIBLE else View.INVISIBLE
    binding.btnBack.visibility = contentVisibility
    binding.titleRow.visibility = contentVisibility
    updateClockVisibility()
}

internal fun PlayerActivity.showSeekOsd() {
    if (isSidePanelVisible() || isSponsorSubmitPanelVisible()) return
    if (osdMode == PlayerActivity.OsdMode.Full) {
        // Full OSD already has the progress bar; keep it alive.
        noteUserInteraction()
        return
    }

    val exo = player
    if (exo != null) {
        val duration = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
        val pos = resolvePlayerUiPositionMs(exo.currentPosition, holdScrubPreviewPosMs, keySeekPreviewPosMs)
        val bufPos = resolveSeekUiBufferedPositionMs(exo.bufferedPosition, pos)
        showSeekOsd(posMs = pos, durationMs = duration, bufferedPosMs = bufPos)
        return
    }

    transientSeekOsdVisible = true
    updatePersistentBottomProgressBarVisibility()
    scheduleHideSeekOsd()
}

internal fun PlayerActivity.showSeekOsd(posMs: Long, durationMs: Long, bufferedPosMs: Long) {
    if (isSidePanelVisible() || isSponsorSubmitPanelVisible()) return
    val duration = durationMs.coerceAtLeast(0L)
    val pos = posMs.coerceAtLeast(0L)
    val bufPos = bufferedPosMs.coerceAtLeast(0L)

    val hasVideoShot =
        currentVideoShot != null &&
            BiliClient.prefs.playerVideoShotPreviewSize != AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF

    if (osdMode == PlayerActivity.OsdMode.Full) {
        // Full OSD: update the real SeekBar + time (useful for hold-scrub preview).
        binding.tvTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
        val enabled = duration > 0L
        binding.seekProgress.isEnabled = enabled
        if (enabled) {
            val bufferedProgress =
                ((bufPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX)
                    .toInt()
                    .coerceIn(0, PlayerActivity.SEEK_MAX)
            val pNow = ((pos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
            binding.seekProgress.secondaryProgress = bufferedProgress
            binding.seekProgress.progress = pNow

            // === 新增：在 Full 模式下更新并显示缩略图，跟随主进度条 ===
            if (hasVideoShot) {
                binding.videoShotPreview.visibility = View.VISIBLE
                updateVideoShotPreview(pNow, PlayerActivity.SEEK_MAX, pos, binding.seekProgress)
            }
        }
        noteUserInteraction()
        return
    }

    binding.tvSeekOsdTime.text = "${formatHms(pos)} / ${formatHms(duration)}"

    val enabled = duration > 0L
    binding.progressSeekOsd.isEnabled = enabled
    if (enabled) {
        val bufferedProgress =
            ((bufPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX)
                .toInt()
                .coerceIn(0, PlayerActivity.SEEK_MAX)
        val pNow = ((pos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
        binding.progressSeekOsd.secondaryProgress = bufferedProgress
        binding.progressSeekOsd.progress = pNow

        // === 新增：在瞬时 OSD 模式下更新并显示缩略图，跟随瞬时进度条 ===
        if (hasVideoShot) {
            binding.videoShotPreview.visibility = View.VISIBLE
            updateVideoShotPreview(pNow, PlayerActivity.SEEK_MAX, pos, binding.progressSeekOsd)
        }
    } else {
        binding.progressSeekOsd.secondaryProgress = 0
        binding.progressSeekOsd.progress = 0
        binding.videoShotPreview.visibility = View.GONE
    }

    transientSeekOsdVisible = true
    updatePersistentBottomProgressBarVisibility()
    scheduleHideSeekOsd()
}

internal fun PlayerActivity.ensureBottomBarConstraintSets() {
    if (bottomBarFullConstraints != null) return
    bottomBarFullConstraints =
        ConstraintSet().also { set ->
            set.clone(binding.bottomBar)
            set.setVisibility(R.id.controls_row, View.VISIBLE)
            set.setVisibility(R.id.tv_time, View.VISIBLE)
        }
}

internal fun PlayerActivity.applyBottomBarFullLayout() {
    ensureBottomBarConstraintSets()
    bottomBarFullConstraints?.applyTo(binding.bottomBar)
}

internal fun PlayerActivity.scheduleHideSeekOsd() {
    seekOsdHideJob?.cancel()
    val token = SystemClock.uptimeMillis()
    seekOsdToken = token
    seekOsdHideJob =
        lifecycleScope.launch {
            delay(PlayerActivity.SEEK_OSD_HIDE_DELAY_MS)
            if (seekOsdToken != token) return@launch
            transientSeekOsdVisible = false

            binding.videoShotPreview.visibility = View.GONE
            videoShotFetchJob?.cancel() // 及时取消异步请求

            updatePersistentBottomProgressBarVisibility()
        }
}

internal fun PlayerActivity.updatePersistentBottomProgressBarVisibility() {
    val enabled = session.persistentBottomProgressEnabled
    val showControls = osdMode != PlayerActivity.OsdMode.Hidden || isSidePanelVisible() || isSponsorSubmitPanelVisible()
    val persistentV = if (enabled && !showControls && !transientSeekOsdVisible) View.VISIBLE else View.GONE
    if (binding.progressPersistentBottom.visibility != persistentV) binding.progressPersistentBottom.visibility = persistentV

    val seekOsdV = if (!showControls && transientSeekOsdVisible) View.VISIBLE else View.GONE
    if (binding.seekOsdContainer.visibility != seekOsdV) binding.seekOsdContainer.visibility = seekOsdV
}

internal fun PlayerActivity.restartAutoHideTimer() {
    autoHideJob?.cancel()
    val exo = player ?: return
    if (isTouchLocked()) return
    if (osdMode != PlayerActivity.OsdMode.Full) return
    if (isSidePanelVisible()) return
    if (isSponsorSubmitPanelVisible()) return
    if (scrubbing) return
    if (!exo.isPlaying) return
    val token = lastInteractionAtMs
    autoHideJob = lifecycleScope.launch {
        delay(PlayerActivity.AUTO_HIDE_MS)
        if (token != lastInteractionAtMs) return@launch
        setControlsVisible(false)
    }
}

internal fun PlayerActivity.noteUserInteraction() {
    lastInteractionAtMs = SystemClock.uptimeMillis()
    restartAutoHideTimer()
}

internal fun PlayerActivity.cancelDeferredKeySeekPreview(resetScrubbing: Boolean = true) {
    keyScrubEndJob?.cancel()
    keyScrubEndJob = null
    keyScrubPendingSeekToMs = null
    keySeekPreviewPosMs = null
    if (resetScrubbing) scrubbing = false
    clearKeySeekBufferingOverlaySuppression()
    updateBufferingOverlay()
}

private fun PlayerActivity.commitDeferredKeySeekPreview() {
    val pendingSeekTo = keyScrubPendingSeekToMs
    keyScrubEndJob?.cancel()
    keyScrubEndJob = null
    keyScrubPendingSeekToMs = null
    keySeekPreviewPosMs = null
    scrubbing = false

    val engine = player
    if (pendingSeekTo != null && engine != null) {
        engine.seekTo(pendingSeekTo)
        requestDanmakuSegmentsForPosition(pendingSeekTo, immediate = true)
        requestReportProgressOnce(reason = "user_seek_end")
        val duration = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
        if (duration > 0L) {
            showSeekOsd(posMs = pendingSeekTo, durationMs = duration, bufferedPosMs = engine.bufferedPosition)
        }
    }

    updateBufferingOverlay()
    restartAutoHideTimer()
}

internal fun PlayerActivity.scheduleKeyScrubEnd(delayMs: Long = PlayerActivity.KEY_SCRUB_END_DELAY_MS) {
    keyScrubEndJob?.cancel()
    keyScrubEndJob =
        lifecycleScope.launch {
            delay(delayMs)
            commitDeferredKeySeekPreview()
        }
}

internal fun PlayerActivity.hasControlsFocus(): Boolean =
    binding.topBar.hasFocus() ||
        binding.cardUpQuick.hasFocus() ||
        binding.recyclerCollectionThumbs.hasFocus() ||
        binding.bottomBar.hasFocus() ||
        binding.videoShotPreview.hasFocus() ||
        binding.sponsorSubmitPanel.hasFocus() ||
        binding.playerInfoPanel.hasFocus() ||
        binding.settingsPanel.hasFocus() ||
        binding.commentsPanel.hasFocus()

internal fun PlayerActivity.hasControlsFocusOutsideSeekBar(): Boolean {
    if (osdMode != PlayerActivity.OsdMode.Full) return false
    return binding.topBar.hasFocus() ||
        binding.cardUpQuick.hasFocus() ||
        binding.recyclerCollectionThumbs.hasFocus() ||
        binding.controlsRow.hasFocus() ||
        binding.btnResolution.hasFocus() ||
        (binding.bottomBar.hasFocus() && !binding.seekProgress.isFocused)
}

internal fun PlayerActivity.focusFirstControl() {
    binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
}

private fun PlayerActivity.requestFocusControlNow(view: View?): Boolean {
    val target = view ?: return false
    if (target.visibility != View.VISIBLE) return false
    if (!target.isEnabled) return false
    if (!target.isFocusable) return false
    return target.requestFocus()
}

internal fun PlayerActivity.focusDownKeyOsdTargetControl() {
    val target =
        when (BiliClient.prefs.playerDownKeyOsdFocusTarget) {
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PREV -> binding.btnPrev
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_NEXT -> binding.btnNext
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE -> binding.btnSubtitle
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU -> binding.btnDanmaku
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS -> binding.btnComments
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL -> binding.btnDetail
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP -> binding.btnUp
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE -> binding.btnLike
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN -> binding.btnCoin
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV -> binding.btnFav
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL -> binding.btnListPanel
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SPONSOR_SUBMIT -> binding.btnSponsorSubmit
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED -> binding.btnAdvanced
            else -> binding.btnPlayPause
        }

    binding.controlsRow.post {
        if (requestFocusControlNow(target)) return@post
        if (requestFocusControlNow(binding.btnPlayPause)) return@post
        focusFirstControl()
    }
}

internal fun PlayerActivity.focusSeekBar() {
    binding.seekProgress.post { binding.seekProgress.requestFocus() }
}

internal fun PlayerActivity.canFocusCollectionThumbs(): Boolean {
    if (binding.recyclerCollectionThumbs.visibility != View.VISIBLE) return false
    return (binding.recyclerCollectionThumbs.adapter?.itemCount ?: 0) > 0
}

internal fun PlayerActivity.focusCollectionThumbs(): Boolean {
    val recycler = binding.recyclerCollectionThumbs
    val itemCount = recycler.adapter?.itemCount ?: return false
    if (recycler.visibility != View.VISIBLE || itemCount <= 0) return false
    val targetPosition = collectionThumbsSelectedIndex.takeIf { it in 0 until itemCount } ?: 0
    val requestToken = ++collectionThumbsFocusRequestToken
    return recycler.requestFocusAdapterPositionReliable(
        position = targetPosition,
        smoothScroll = false,
        isAlive = {
            collectionThumbsFocusRequestToken == requestToken &&
                !isFinishing &&
                !isDestroyed &&
                recycler.visibility == View.VISIBLE &&
                (binding.seekProgress.isFocused || binding.cardUpQuick.hasFocus() || recycler.hasFocus())
        },
        onFocused = {},
    )
}

internal fun PlayerActivity.cancelPendingCollectionThumbsFocus() {
    collectionThumbsFocusRequestToken++
}

internal fun PlayerActivity.canFocusVideoShotPreview(): Boolean {
    if (binding.videoShotPreview.visibility != View.VISIBLE) return false
    if (BiliClient.prefs.playerVideoShotPreviewSize == AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF) return false
    return !currentVideoShot?.times.isNullOrEmpty()
}

internal fun PlayerActivity.focusVideoShotPreview() {
    binding.videoShotPreview.post { binding.videoShotPreview.requestFocus() }
}

internal fun PlayerActivity.focusUpQuickCard(): Boolean {
    if (binding.cardUpQuick.visibility != View.VISIBLE) return false
    if (requestFocusControlNow(binding.btnUpQuickProfile)) return true
    if (requestFocusControlNow(binding.btnUpQuickFollow)) return true
    return false
}

internal fun PlayerActivity.moveFocusedVideoShotPreviewSelection(direction: Int): Boolean {
    val safeDirection = direction.coerceIn(-1, 1)
    if (safeDirection == 0) return false
    val durationMs = player?.duration?.takeIf { it > 0L } ?: currentViewDurationMs?.takeIf { it > 0L } ?: return false
    val currentMs =
        resolvePlayerUiPositionMs(
            committedPositionMs = player?.currentPosition ?: 0L,
            holdPreviewPositionMs = holdScrubPreviewPosMs,
            deferredPreviewPositionMs = keySeekPreviewPosMs,
        ).coerceIn(0L, durationMs)
    val targetMs =
        nextVideoShotPreviewTimeMs(currentMs = currentMs, direction = safeDirection)
            ?: accumulateDeferredSeekPreviewPositionMs(
                committedPositionMs = player?.currentPosition ?: currentMs,
                activePreviewPositionMs = currentMs,
                deltaMs = smartSeekStepMs() * safeDirection.toLong(),
                durationMs = durationMs,
            )
    previewVideoShotSelectionAt(targetMs = targetMs, durationMs = durationMs)
    return true
}

internal fun PlayerActivity.commitFocusedVideoShotPreviewSelection(): Boolean {
    val pendingSeekTo = keySeekPreviewPosMs ?: keyScrubPendingSeekToMs ?: return false
    keyScrubPendingSeekToMs = pendingSeekTo
    commitDeferredKeySeekPreview()
    return true
}

private fun PlayerActivity.previewVideoShotSelectionAt(targetMs: Long, durationMs: Long) {
    val clampedMs = targetMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val progress =
        if (durationMs > 0L) {
            ((clampedMs.toDouble() / durationMs.toDouble()) * PlayerActivity.SEEK_MAX)
                .toInt()
                .coerceIn(0, PlayerActivity.SEEK_MAX)
        } else {
            0
        }
    scrubbing = true
    keySeekPreviewPosMs = clampedMs
    keyScrubPendingSeekToMs = clampedMs
    suppressBufferingOverlayDuringKeySeek(PlayerActivity.KEY_STEP_SEEK_COMMIT_DELAY_MS)
    binding.seekProgress.progress = progress
    binding.tvTime.text = "${formatHms(clampedMs)} / ${formatHms(durationMs)}"
    showSeekOsd(
        posMs = clampedMs,
        durationMs = durationMs,
        bufferedPosMs = resolveSeekUiBufferedPositionMs(player?.bufferedPosition ?: 0L, clampedMs),
    )
    scheduleKeyScrubEnd(delayMs = PlayerActivity.KEY_STEP_SEEK_COMMIT_DELAY_MS)
}

private fun PlayerActivity.nextVideoShotPreviewTimeMs(currentMs: Long, direction: Int): Long? {
    val times = currentVideoShot?.times ?: return null
    if (times.isEmpty()) return null
    val guardMs = 50L
    return if (direction > 0) {
        times.firstOrNull { it.toLong() * 1000L > currentMs + guardMs }?.toLong()?.times(1000L)
    } else {
        times.lastOrNull { it.toLong() * 1000L < currentMs - guardMs }?.toLong()?.times(1000L)
    }
}

internal fun PlayerActivity.focusAdvancedControl() {
    binding.btnAdvanced.post { binding.btnAdvanced.requestFocus() }
}

internal fun PlayerActivity.focusSettingsPanel() {
    binding.recyclerSettings.post {
        val child = binding.recyclerSettings.getChildAt(0)
        if (child != null) {
            child.requestFocus()
            return@post
        }

        binding.recyclerSettings.scrollToPosition(0)
        binding.recyclerSettings.post {
            val first = binding.recyclerSettings.getChildAt(0)
            (first ?: binding.recyclerSettings).requestFocus()
        }
    }
}

internal fun PlayerActivity.smartSeek(direction: Int) {
    smartSeek(direction, showControls = true, hintKind = SeekHintKind.Step)
}

internal enum class SeekHintKind {
    Step,
    Hold,
}

internal fun PlayerActivity.isSeekKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2,
        -> true

        else -> false
    }
}

internal fun PlayerActivity.clearKeySeekPending() {
    keySeekHoldDetectJob?.cancel()
    keySeekHoldDetectJob = null
    keySeekPendingKeyCode = 0
    keySeekPendingDirection = 0
}

internal fun PlayerActivity.isConfirmHoldKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> true

        else -> false
    }
}

internal fun PlayerActivity.clearPendingCenterKeyAction() {
    pendingCenterKeyAction = PlayerActivity.PendingCenterKeyAction.None.ordinal
}

internal fun PlayerActivity.setPendingCenterKeyAction(action: PlayerActivity.PendingCenterKeyAction) {
    pendingCenterKeyAction = action.ordinal
}

internal fun PlayerActivity.consumePendingCenterKeyAction(): PlayerActivity.PendingCenterKeyAction {
    val action =
        PlayerActivity.PendingCenterKeyAction.entries.getOrElse(pendingCenterKeyAction) {
            PlayerActivity.PendingCenterKeyAction.None
        }
    clearPendingCenterKeyAction()
    return action
}

internal fun PlayerActivity.beginKeySeekPending(keyCode: Int, direction: Int, showControls: Boolean) {
    clearKeySeekPending()
    keySeekPendingKeyCode = keyCode
    keySeekPendingDirection = direction

    // Delay to decide tap vs hold:
    // - If repeats arrive, dispatchKeyEvent() will start hold immediately and clear this pending state.
    // - If no repeats (some remotes/media keys), start hold after the long-press timeout.
    val timeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    keySeekHoldDetectJob =
        lifecycleScope.launch {
            delay(timeoutMs)
            if (keySeekPendingKeyCode != keyCode || keySeekPendingDirection != direction) return@launch
            if (holdSeekJob != null) return@launch
            if (direction < 0) {
                // Long-press LEFT: always use preview-scrub rewind (independent of hold-seek mode setting).
                startHoldScrub(direction = direction, showControls = showControls)
            } else {
                startHoldSeek(direction = direction, showControls = showControls)
            }
            // Once we enter hold, a later ACTION_UP should only stop the hold (no step seek).
            clearKeySeekPending()
        }
}

internal fun PlayerActivity.holdSeekUsesProgressPreview(direction: Int): Boolean {
    if (direction < 0) return true
    return when (BiliClient.prefs.playerHoldSeekMode) {
        AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB,
        AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME,
        -> true

        else -> false
    }
}

internal fun PlayerActivity.smartSeek(direction: Int, showControls: Boolean, hintKind: SeekHintKind) {
    val now = SystemClock.uptimeMillis()
    val sameDir = direction == smartSeekDirection
    val within = now - smartSeekLastAtMs <= PlayerActivity.SMART_SEEK_WINDOW_MS
    val continued = sameDir && within
    smartSeekDirection = direction
    smartSeekLastAtMs = now

    if (showControls) {
        if (osdMode != PlayerActivity.OsdMode.Full && !isSidePanelVisible()) setControlsVisible(true) else noteUserInteraction()
    } else {
        noteUserInteraction()
    }

    val step = smartSeekStepMs()
    val engine = player ?: return
    val duration = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs
    if (duration == null || duration <= 0L) {
        seekRelative(step * direction)
        smartSeekTotalMs = if (continued) (smartSeekTotalMs + step) else step
        if (hintKind == SeekHintKind.Step) showSeekStepHint(direction, smartSeekTotalMs)
        return
    }

    val target =
        accumulateDeferredSeekPreviewPositionMs(
            committedPositionMs = engine.currentPosition,
            activePreviewPositionMs = keySeekPreviewPosMs,
            deltaMs = step * direction.toLong(),
            durationMs = duration,
        )
    scrubbing = true
    keySeekPreviewPosMs = target
    keyScrubPendingSeekToMs = target
    suppressBufferingOverlayDuringKeySeek(PlayerActivity.KEY_STEP_SEEK_COMMIT_DELAY_MS)
    showSeekOsd(
        posMs = target,
        durationMs = duration,
        bufferedPosMs = resolveSeekUiBufferedPositionMs(engine.bufferedPosition, target),
    )
    updateBufferingOverlay()
    scheduleKeyScrubEnd(delayMs = PlayerActivity.KEY_STEP_SEEK_COMMIT_DELAY_MS)
    smartSeekTotalMs = if (continued) (smartSeekTotalMs + step) else step
    if (hintKind == SeekHintKind.Step) showSeekStepHint(direction, smartSeekTotalMs)
}

internal fun PlayerActivity.smartSeekStepMs(): Long {
    return BiliClient.prefs.playerShortSeekStepSeconds * 1_000L
}

internal fun PlayerActivity.startHoldSeek(direction: Int, showControls: Boolean) {
    // Speed-hold seek is forward-only; long-press LEFT is handled by preview-scrub (startHoldScrub).
    if (direction <= 0) return
    if (holdSeekJob?.isActive == true) return
    val engine = player ?: return

    if (showControls) {
        if (osdMode != PlayerActivity.OsdMode.Full && !isSidePanelVisible()) setControlsVisible(true) else noteUserInteraction()
    } else {
        noteUserInteraction()
    }

    val holdSpeed = holdSeekSpeed()
    val holdMode = BiliClient.prefs.playerHoldSeekMode
    if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SPEED && keyScrubPendingSeekToMs != null) {
        commitDeferredKeySeekPreview()
    }
    holdPrevSpeed = engine.playbackSpeed
    holdPrevPlayWhenReady = engine.playWhenReady
    holdScrubPreviewPosMs = null
    if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB || holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME) {
        val fixedStepMs =
            if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME) {
                holdScrubFixedStepMs()
            } else {
                null
            }
        startHoldScrubSeek(engine = engine, direction = direction, speed = holdSpeed, fixedStepMs = fixedStepMs)
        return
    }
    showSeekHoldHint(direction, holdSpeed)
    engine.setPlaybackSpeed(holdSpeed)
    engine.playWhenReady = true
    holdSeekJob = lifecycleScope.launch { kotlinx.coroutines.awaitCancellation() }
}

internal fun PlayerActivity.startHoldScrub(direction: Int, showControls: Boolean) {
    if (holdSeekJob?.isActive == true) return
    val engine = player ?: return

    if (showControls) {
        if (osdMode != PlayerActivity.OsdMode.Full && !isSidePanelVisible()) setControlsVisible(true) else noteUserInteraction()
    } else {
        noteUserInteraction()
    }

    val holdSpeed = holdSeekSpeed()
    val holdMode = BiliClient.prefs.playerHoldSeekMode
    holdPrevSpeed = engine.playbackSpeed
    holdPrevPlayWhenReady = engine.playWhenReady
    holdScrubPreviewPosMs = null
    val fixedStepMs =
        if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME) {
            holdScrubFixedStepMs()
        } else {
            null
        }
    startHoldScrubSeek(engine = engine, direction = direction, speed = holdSpeed, fixedStepMs = fixedStepMs)
}

internal fun PlayerActivity.startHoldScrubSeek(engine: BlblPlayerEngine, direction: Int, speed: Float, fixedStepMs: Long?) {
    val duration = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
    if (duration <= 0L) {
        // Unknown duration: cannot show an actual progress bar scrub; fall back to speed-hold.
        if (direction <= 0) return
        showSeekHoldHint(direction, speed)
        engine.setPlaybackSpeed(speed)
        engine.playWhenReady = true
        holdSeekJob = lifecycleScope.launch { kotlinx.coroutines.awaitCancellation() }
        return
    }

    val initial = resolvePlayerUiPositionMs(engine.currentPosition, holdScrubPreviewPosMs, keySeekPreviewPosMs).coerceIn(0L, duration)
    cancelDeferredKeySeekPreview(resetScrubbing = false)
    scrubbing = true

    engine.pause()

    holdScrubPreviewPosMs = initial
    showSeekOsd(
        posMs = initial,
        durationMs = duration,
        bufferedPosMs = resolveSeekUiBufferedPositionMs(engine.bufferedPosition, initial),
    )

    val tickMs = PlayerActivity.HOLD_SCRUB_TICK_MS
    val stepMs =
        fixedStepMs
            ?.let {
                (it.coerceAtLeast(1L).toDouble() * tickMs.toDouble() / PlayerActivity.HOLD_SCRUB_FIXED_STEP_BASE_TICK_MS)
                    .roundToInt()
                    .toLong()
            }
            ?: holdScrubStepMs(durationMs = duration, tickMs = tickMs)
    val deltaMs = stepMs.coerceAtLeast(1L) * direction.toLong()
    holdSeekJob =
        lifecycleScope.launch {
            while (isActive) {
                val current = holdScrubPreviewPosMs ?: initial
                val next = (current + deltaMs).coerceIn(0L, duration)
                holdScrubPreviewPosMs = next
                showSeekOsd(
                    posMs = next,
                    durationMs = duration,
                    bufferedPosMs = resolveSeekUiBufferedPositionMs(engine.bufferedPosition, next),
                )
                delay(tickMs)
            }
        }
}

internal fun PlayerActivity.stopHoldSeek() {
    val engine = player
    val scrubTarget = holdScrubPreviewPosMs
    holdScrubPreviewPosMs = null
    if (scrubTarget != null) {
        scrubbing = false
        keyScrubEndJob?.cancel()
        keyScrubEndJob = null
    }
    holdSeekJob?.cancel()
    holdSeekJob = null
    if (engine != null) {
        engine.setPlaybackSpeed(holdPrevSpeed)
        if (scrubTarget != null) {
            engine.seekTo(scrubTarget)
            val duration = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
            if (duration > 0L) showSeekOsd(posMs = scrubTarget, durationMs = duration, bufferedPosMs = engine.bufferedPosition)
        }
        engine.playWhenReady = holdPrevPlayWhenReady
        val pos = (scrubTarget ?: engine.currentPosition).coerceAtLeast(0L)
        requestDanmakuSegmentsForPosition(pos, immediate = true)
    }
    scheduleHideSeekHint()
}

internal fun PlayerActivity.showSeekStepHint(direction: Int, totalMs: Long) {
    val sec = (kotlin.math.abs(totalMs) / 1000L).coerceAtLeast(1L)
    val text = if (direction > 0) "快进 ${sec}s" else "后退 ${sec}s"
    showSeekHint(text, hold = false)
}

internal fun PlayerActivity.showSeekHoldHint(direction: Int, speed: Float) {
    val s = holdSeekSpeedText(speed)
    val text = if (direction > 0) "快进 x$s" else "后退 x$s"
    showSeekHint(text, hold = true)
}

internal fun PlayerActivity.holdSeekSpeed(): Float {
    val v = BiliClient.prefs.playerHoldSeekSpeed
    val fallback = AppPrefs.PLAYER_HOLD_SEEK_SPEED_DEFAULT
    if (!v.isFinite()) return fallback
    return v.coerceIn(1.5f, 4.0f)
}

internal fun PlayerActivity.holdSeekSpeedText(speed: Float): String {
    val v = speed.takeIf { it.isFinite() } ?: AppPrefs.PLAYER_HOLD_SEEK_SPEED_DEFAULT
    val fixed = String.format(Locale.US, "%.2f", v)
    return fixed.trimEnd('0').trimEnd('.')
}

internal fun PlayerActivity.holdScrubFixedStepMs(): Long {
    return BiliClient.prefs.playerHoldScrubFixedStepSeconds * 1_000L
}

internal fun PlayerActivity.holdScrubStepMs(durationMs: Long, tickMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0L)
    if (duration <= 0L) return 0L
    val tick = tickMs.coerceAtLeast(1L)
    val traverseMs = (BiliClient.prefs.playerHoldScrubTraverseSeconds * 1_000L).coerceAtLeast(tick)
    val step = (duration.toDouble() * tick.toDouble() / traverseMs.toDouble()).roundToInt().toLong()
    return step.coerceAtLeast(1L)
}

internal fun accumulateDeferredSeekPreviewPositionMs(
    committedPositionMs: Long,
    activePreviewPositionMs: Long?,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val duration = durationMs.coerceAtLeast(0L)
    val base = (activePreviewPositionMs ?: committedPositionMs).coerceAtLeast(0L)
    return (base + deltaMs).coerceIn(0L, duration)
}

internal fun resolvePlayerUiPositionMs(
    committedPositionMs: Long,
    holdPreviewPositionMs: Long?,
    deferredPreviewPositionMs: Long?,
): Long {
    return (holdPreviewPositionMs ?: deferredPreviewPositionMs ?: committedPositionMs).coerceAtLeast(0L)
}

internal fun PlayerActivity.resolveSeekUiBufferedPositionMs(
    committedBufferedPositionMs: Long,
    uiPositionMs: Long,
): Long {
    val buffered = committedBufferedPositionMs.coerceAtLeast(0L)
    val hasPreview = holdScrubPreviewPosMs != null || keySeekPreviewPosMs != null
    if (hasPreview && player?.kind == PlayerEngineKind.IjkPlayer) {
        return uiPositionMs.coerceAtLeast(0L)
    }
    return buffered
}

internal fun PlayerActivity.showSeekHint(
    text: String,
    hold: Boolean,
    hideDelayMs: Long = PlayerActivity.SEEK_HINT_HIDE_DELAY_MS,
) {
    // Auto-next preview hint has the highest priority: while it's visible, ignore any other hint requests.
    // This keeps the UX stable in the last 2 seconds (no flicker from volume/brightness/shortcuts/etc).
    if (autoNextHintVisible) {
        val locked = autoNextHintText
        if (!locked.isNullOrEmpty() && text != locked) return
    }
    binding.tvSeekHint.text = text
    binding.tvSeekHint.visibility = View.VISIBLE
    seekHintJob?.cancel()
    if (!hold) scheduleHideSeekHint(hideDelayMs)
}

internal fun PlayerActivity.scheduleHideSeekHint(hideDelayMs: Long = PlayerActivity.SEEK_HINT_HIDE_DELAY_MS) {
    // Keep the auto-next hint visible until:
    // - user cancels (BACK), or
    // - playback ends and we transition.
    if (autoNextHintVisible) return
    seekHintJob?.cancel()
    seekHintJob =
        lifecycleScope.launch {
            delay(hideDelayMs)
            binding.tvSeekHint.visibility = View.GONE
        }
}

internal fun PlayerActivity.edgeDirection(x: Float, width: Float): Int {
    return when {
        x < width * PlayerActivity.EDGE_TAP_THRESHOLD -> -1
        x > width * (1f - PlayerActivity.EDGE_TAP_THRESHOLD) -> +1
        else -> 0
    }
}

internal fun PlayerActivity.isInteractionKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_GUIDE,
        -> true

        else -> false
    }
}

internal fun PlayerActivity.updatePlayPauseIcon(isPlaying: Boolean) {
    binding.btnPlayPause.setImageResource(
        if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play,
    )
}

internal fun PlayerActivity.updateVideoShotPreview(
    progress: Int,
    max: Int,
    positionMs: Long,
    trackView: View // 传入当前正在使用的进度条控件
) {
    if (BiliClient.prefs.playerVideoShotPreviewSize == AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF) {
        binding.videoShotPreview.visibility = View.GONE
        videoShotFetchJob?.cancel()
        return
    }

    val previewView = binding.videoShotPreview
    val shot = currentVideoShot ?: return
    val cache = videoShotImageCache ?: return
    previewView.setContentAspectRatio(
        width = currentVideoContentWidth ?: shot.fallbackAspectWidth,
        height = currentVideoContentHeight ?: shot.fallbackAspectHeight,
    )

    videoShotFetchJob?.cancel()
    videoShotFetchJob = lifecycleScope.launch {
        delay(16) // 16ms 防抖
        val frame =
            runCatching { shot.getSpriteFrame((positionMs / 1000).toInt(), cache) }
                .getOrNull()
                ?: return@launch

        previewView.spriteFrame = frame
        positionVideoShotPreviewX(previewView, trackView, progress, max)
        previewView.post {
            if (previewView.spriteFrame === frame) {
                positionVideoShotPreviewX(previewView, trackView, progress, max)
            }
        }
    }
}

private fun positionVideoShotPreviewX(
    previewView: View,
    trackView: View,
    progress: Int,
    max: Int,
) {
    val containerWidth = trackView.width
    val previewWidth = previewView.width
    if (containerWidth <= 0 || previewWidth <= 0 || max <= 0) return

    val progressRatio = progress.toDouble() / max.toDouble()
    var rawOffset = (containerWidth * progressRatio) - (previewWidth / 2.0)
    rawOffset += trackView.left

    val minOffset = 0.0
    val parentWidth = (previewView.parent as? View)?.width ?: containerWidth
    val maxOffset = (parentWidth - previewWidth).toDouble()

    previewView.translationX = rawOffset.coerceIn(minOffset, maxOffset).toFloat()
}
