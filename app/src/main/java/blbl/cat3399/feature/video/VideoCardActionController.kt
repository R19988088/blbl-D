package blbl.cat3399.feature.video

import android.content.Context
import androidx.activity.ComponentActivity
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VideoCardDismissBehavior {
    data object LocalNotInterested : VideoCardDismissBehavior

    data object DeleteHistory : VideoCardDismissBehavior

    data object DeleteToView : VideoCardDismissBehavior

    data class DeleteFavFolderItem(
        val mediaId: Long,
    ) : VideoCardDismissBehavior
}

enum class VideoCardFavoriteActionMode {
    ADD,
    REMOVE,
}

class VideoCardActionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dismissBehavior: VideoCardDismissBehavior,
    private val onOpenDetail: (VideoCard, Int) -> Unit,
    private val onOpenUp: (VideoCard) -> Unit,
    private val onCardRemoved: (String) -> Unit = {},
    private val longPressActionProvider: () -> String = { BiliClient.prefs.videoCardLongPressAction },
    private val favoriteActionMode: VideoCardFavoriteActionMode = VideoCardFavoriteActionMode.ADD,
) : VideoCardActionDelegate {
    private val inFlightActionKeys = HashSet<String>()
    private val effectiveFavoriteActionMode =
        when (dismissBehavior) {
            is VideoCardDismissBehavior.DeleteFavFolderItem -> VideoCardFavoriteActionMode.REMOVE
            else -> favoriteActionMode
        }

    override fun resolveLongPressAction(
        card: VideoCard,
        position: Int,
    ): VideoCardConfiguredLongPressAction = VideoCardConfiguredLongPressAction.fromPref(longPressActionProvider())

    override fun manualActions(
        card: VideoCard,
        position: Int,
    ): List<VideoCardQuickAction> {
        return listOf(
            VideoCardQuickAction.favorite(context.getString(favoriteActionLabelRes())),
            VideoCardQuickAction.watchLater(
                context.getString(
                    if (dismissBehavior == VideoCardDismissBehavior.DeleteToView) {
                        R.string.video_card_action_remove_watch_later
                    } else {
                        R.string.video_card_action_watch_later
                    },
                ),
            ),
            VideoCardQuickAction.blockUp(context.getString(R.string.video_card_action_block_up)),
            VideoCardQuickAction.openDetail(context.getString(R.string.video_card_action_open_detail)),
            VideoCardQuickAction.openUp(context.getString(R.string.video_card_action_open_up)),
            VideoCardQuickAction.dismiss(context.getString(dismissActionLabelRes())),
        )
    }

    override fun onActionSelected(
        card: VideoCard,
        position: Int,
        action: VideoCardQuickAction,
    ) {
        when (action.id) {
            VideoCardQuickActionId.FAVORITE -> handleFavoriteAction(card)
            VideoCardQuickActionId.WATCH_LATER ->
                if (dismissBehavior == VideoCardDismissBehavior.DeleteToView) dismissCard(card) else addToWatchLater(card)
            VideoCardQuickActionId.BLOCK_UP -> blockUp(card)
            VideoCardQuickActionId.OPEN_DETAIL -> openDetail(card, position)
            VideoCardQuickActionId.OPEN_UP -> onOpenUp(card)
            VideoCardQuickActionId.DISMISS -> dismissCard(card)
        }
    }

    private fun openFavoriteFolders(card: VideoCard) {
        val activity = context as? ComponentActivity
        if (activity == null) {
            AppToast.show(context, context.getString(R.string.video_card_action_failed))
            return
        }
        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        if (selfMid == null) {
            AppToast.show(context, "请先登录后再收藏")
            return
        }
        val actionKey = buildActionKey(card = card, suffix = "favorite")
        if (!markInFlight(actionKey)) return

        scope.launch {
            try {
                val aid =
                    withContext(Dispatchers.IO) {
                        resolveAid(card)
                    }
                val folders =
                    withContext(Dispatchers.IO) {
                        BiliApi.favFoldersWithState(upMid = selfMid, rid = aid)
                    }
                if (folders.isEmpty()) {
                    AppToast.show(context, "未获取到收藏夹")
                    return@launch
                }

                val initial = folders.filter { it.favState }.map { it.mediaId }.toSet()
                val labels =
                    folders.map { folder ->
                        if (folder.favState) "${folder.title}（已收藏）" else folder.title
                    }
                AppPopup.singleChoice(
                    context = activity,
                    title = "选择收藏夹",
                    items = labels,
                    checkedIndex = 0,
                    negativeText = "撤销",
                ) { index, _ ->
                    val picked = folders.getOrNull(index) ?: return@singleChoice
                    val nextSelected = initial.toMutableSet()
                    if (nextSelected.contains(picked.mediaId)) nextSelected.remove(picked.mediaId) else nextSelected.add(picked.mediaId)
                    val add = (nextSelected - initial).toList()
                    val del = (initial - nextSelected).toList()
                    if (add.isEmpty() && del.isEmpty()) return@singleChoice
                    applyFavoriteSelection(card = card, aid = aid, add = add, del = del)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppToast.show(context, errorMessage(t))
            } finally {
                clearInFlight(actionKey)
            }
        }
    }

    private fun applyFavoriteSelection(
        card: VideoCard,
        aid: Long,
        add: List<Long>,
        del: List<Long>,
    ) {
        val actionKey = buildActionKey(card = card, suffix = "favorite_apply")
        if (!markInFlight(actionKey)) return

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BiliApi.favResourceDeal(rid = aid, addMediaIds = add, delMediaIds = del)
                }
                AppToast.show(context, "收藏已更新")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppToast.show(context, errorMessage(t))
            } finally {
                clearInFlight(actionKey)
            }
        }
    }

    private fun openDetail(
        card: VideoCard,
        position: Int,
    ) {
        if (card.bvid.isBlank()) {
            AppToast.show(context, context.getString(R.string.video_card_action_open_detail_unsupported))
            return
        }
        onOpenDetail(card, position)
    }

    private fun addToWatchLater(card: VideoCard) {
        val safeBvid = card.bvid.trim()
        val safeAid = card.aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) {
            AppToast.show(context, context.getString(R.string.video_card_action_missing_video_id))
            return
        }
        val actionKey = buildActionKey(card = card, suffix = "watch_later")
        if (!markInFlight(actionKey)) return

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BiliApi.toViewAdd(
                        bvid = safeBvid.takeIf { it.isNotBlank() },
                        aid = safeAid,
                    )
                }
                AppToast.show(context, context.getString(R.string.video_card_action_watch_later_done))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppToast.show(context, errorMessage(t))
            } finally {
                clearInFlight(actionKey)
            }
        }
    }

    private fun blockUp(card: VideoCard) {
        val ownerMid = card.ownerMid?.takeIf { it > 0L }
        if (ownerMid == null) {
            AppToast.show(context, "未获取到UP信息")
            return
        }
        val actionKey = buildActionKey(card = card, suffix = "block_up")
        if (!markInFlight(actionKey)) return

        scope.launch {
            try {
                withContext(Dispatchers.IO) { BiliApi.modifyRelation(fid = ownerMid, act = 5) }
                AppToast.show(context, "已拉黑UP")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppToast.show(context, errorMessage(t))
            } finally {
                clearInFlight(actionKey)
            }
        }
    }

    private fun dismissCard(card: VideoCard) {
        when (dismissBehavior) {
            VideoCardDismissBehavior.LocalNotInterested -> {
                val actionKey = buildActionKey(card = card, suffix = "not_interested")
                if (!markInFlight(actionKey)) return

                val stableKey = card.stableKey()
                VideoCardVisibilityFilter.hide(stableKey)
                onCardRemoved(stableKey)
                AppToast.show(context, context.getString(R.string.video_card_action_not_interested_done))

                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BiliApi.videoFeedbackDislike(card)
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        AppLog.w("VideoCardAction", "report not interested failed stableKey=$stableKey", t)
                    } finally {
                        clearInFlight(actionKey)
                    }
                }
            }

            else -> {
                val actionKey = buildActionKey(card = card, suffix = "dismiss")
                if (!markInFlight(actionKey)) return

                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            when (val behavior = dismissBehavior) {
                                VideoCardDismissBehavior.DeleteHistory -> {
                                    BiliApi.historyDelete(kid = buildHistoryKid(card))
                                }

                                VideoCardDismissBehavior.DeleteToView -> {
                                    BiliApi.toViewDelete(aid = resolveAid(card))
                                }

                                is VideoCardDismissBehavior.DeleteFavFolderItem -> {
                                    val aid = resolveAid(card)
                                    BiliApi.favResourceDelete(
                                        aid = aid,
                                        mediaId = behavior.mediaId,
                                    )
                                }

                                VideoCardDismissBehavior.LocalNotInterested -> Unit
                            }
                        }
                        onCardRemoved(card.stableKey())
                        val messageRes =
                            if (dismissBehavior == VideoCardDismissBehavior.DeleteToView) {
                                R.string.video_card_action_remove_watch_later_done
                            } else {
                                R.string.video_card_action_delete_done
                            }
                        AppToast.show(context, context.getString(messageRes))
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        AppToast.show(context, errorMessage(t))
                    } finally {
                        clearInFlight(actionKey)
                    }
                }
            }
        }
    }

    private suspend fun resolveAid(card: VideoCard): Long {
        card.aid?.takeIf { it > 0L }?.let { return it }
        val bvid = card.bvid.trim().takeIf { it.isNotBlank() } ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
        return BiliApi.videoDetail(bvid).aid?.takeIf { it > 0L }
            ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
    }

    private suspend fun buildHistoryKid(card: VideoCard): String {
        return when (card.business?.trim()) {
            "archive" -> "archive_${resolveAid(card)}"
            "pgc" -> {
                val seasonId = card.seasonId?.takeIf { it > 0L } ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_history_kid")
                "pgc_$seasonId"
            }

            else -> throw BiliApiException(apiCode = -400, apiMessage = "missing_history_kid")
        }
    }

    private fun dismissActionLabelRes(): Int {
        return when (dismissBehavior) {
            VideoCardDismissBehavior.LocalNotInterested -> R.string.video_card_action_not_interested
            VideoCardDismissBehavior.DeleteHistory -> R.string.video_card_action_delete_history
            else -> R.string.video_card_action_delete
        }
    }

    private fun handleFavoriteAction(card: VideoCard) {
        if (dismissBehavior is VideoCardDismissBehavior.DeleteFavFolderItem) {
            dismissCard(card)
            return
        }
        when (effectiveFavoriteActionMode) {
            VideoCardFavoriteActionMode.ADD -> openFavoriteFolders(card)
            VideoCardFavoriteActionMode.REMOVE -> dismissCard(card)
        }
    }

    private fun favoriteActionLabelRes(): Int {
        return when (effectiveFavoriteActionMode) {
            VideoCardFavoriteActionMode.ADD -> R.string.video_card_action_favorite
            VideoCardFavoriteActionMode.REMOVE -> R.string.video_card_action_unfavorite
        }
    }

    private fun buildActionKey(
        card: VideoCard,
        suffix: String,
    ): String = "${card.stableKey()}|$suffix"

    private fun markInFlight(actionKey: String): Boolean {
        synchronized(inFlightActionKeys) {
            return inFlightActionKeys.add(actionKey)
        }
    }

    private fun clearInFlight(actionKey: String) {
        synchronized(inFlightActionKeys) {
            inFlightActionKeys.remove(actionKey)
        }
    }

    private fun errorMessage(t: Throwable): String {
        val raw = (t as? BiliApiException)?.apiMessage?.trim().orEmpty().ifBlank { t.message.orEmpty().trim() }
        return when {
            raw.isBlank() -> context.getString(R.string.video_card_action_failed)
            raw == "missing_history_kid" -> context.getString(R.string.video_card_action_missing_video_id)
            raw == "missing_video_id" -> context.getString(R.string.video_card_action_missing_video_id)
            else -> raw
        }
    }
}
