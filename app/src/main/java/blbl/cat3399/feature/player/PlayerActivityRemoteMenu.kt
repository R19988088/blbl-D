package blbl.cat3399.feature.player

import android.content.Intent
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.DialogPlayerRemoteMenuBinding
import blbl.cat3399.feature.following.UpDetailActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PlayerActivity.showRemoteMenuPopup() {
    val dialogBinding = DialogPlayerRemoteMenuBinding.inflate(LayoutInflater.from(this))
    ImageLoader.loadInto(dialogBinding.imgCover, currentPlayerCoverUrl)
    ImageLoader.loadInto(dialogBinding.imgUpAvatar, currentUpAvatar)
    dialogBinding.tvUpId.text = currentUpDisplayText()

    var handle: PopupHandle? = null
    val favoriteMediaId = currentFavoriteMediaIdFromPlaylistSource()
    val showUnfavorite = favoriteMediaId != null || actionFavored

    dialogBinding.btnFavorite.text = getString(
        if (showUnfavorite) R.string.video_card_action_unfavorite else R.string.video_card_action_favorite,
    )
    dialogBinding.btnFavorite.setOnClickListener {
        handle?.dismiss()
        if (favoriteMediaId != null) {
            removeCurrentVideoFromFavoriteFolder(mediaId = favoriteMediaId)
        } else {
            onFavButtonClicked(showControls = false)
        }
    }
    dialogBinding.btnWatchLater.setOnClickListener {
        handle?.dismiss()
        addCurrentVideoToWatchLater()
    }
    dialogBinding.btnBlockUp.setOnClickListener {
        handle?.dismiss()
        blockCurrentUp()
    }
    dialogBinding.btnExtraAction.text = "进入UP主页"
    dialogBinding.btnExtraAction.isVisible = true
    dialogBinding.btnExtraAction.setOnClickListener {
        handle?.dismiss()
        openCurrentUpHome()
    }

    handle =
        PopupHost.from(this).showModal(
            title = currentMainTitle?.trim()?.takeIf { it.isNotBlank() } ?: "视频",
            contentView = dialogBinding.root,
            cancelable = true,
            actions = emptyList(),
            preferredActionRole = null,
            autoFocus = false,
            onModalAttached = {
                dialogBinding.btnFavorite.post { dialogBinding.btnFavorite.requestFocus() }
            },
        )
}

private fun PlayerActivity.currentFavoriteMediaIdFromPlaylistSource(): Long? {
    val source = pageListSource?.trim().orEmpty()
    if (!source.startsWith("MyFavFolderDetail:")) return null
    return source.substringAfter(':', "").trim().toLongOrNull()?.takeIf { it > 0L }
}

private fun PlayerActivity.openCurrentUpHome() {
    val mid = currentUpMid.takeIf { it > 0L }
    if (mid == null) {
        AppToast.show(this, "未获取到 UP 主信息")
        return
    }
    startActivity(
        Intent(this, UpDetailActivity::class.java)
            .putExtra(UpDetailActivity.EXTRA_MID, mid)
            .apply {
                currentUpName?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                currentUpAvatar?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
            },
    )
}

private fun PlayerActivity.currentUpDisplayText(): String {
    val name = currentUpName?.trim().orEmpty()
    val mid = currentUpMid.takeIf { it > 0L }
    return when {
        name.isNotBlank() && mid != null -> "$name\nID: $mid"
        name.isNotBlank() -> name
        mid != null -> "ID: $mid"
        else -> "UP"
    }
}

private fun PlayerActivity.addCurrentVideoToWatchLater() {
    val bvid = currentBvid.trim().takeIf { it.isNotBlank() }
    val aid = currentAid?.takeIf { it > 0L }
    if (bvid == null && aid == null) {
        AppToast.show(this, "未获取到视频信息")
        return
    }
    lifecycleScope.launch {
        try {
            withContext(Dispatchers.IO) { BiliApi.toViewAdd(bvid = bvid, aid = aid) }
            AppToast.show(this@addCurrentVideoToWatchLater, "已加入稍后再看")
        } catch (t: Throwable) {
            if (t is CancellationException) return@launch
            val e = t as? BiliApiException
            AppToast.show(this@addCurrentVideoToWatchLater, e?.apiMessage?.takeIf { it.isNotBlank() } ?: "稍后再看失败")
        }
    }
}

private fun PlayerActivity.blockCurrentUp() {
    val ownerMid = currentUpMid.takeIf { it > 0L }
    if (ownerMid == null) {
        AppToast.show(this, "未获取到UP信息")
        return
    }
    lifecycleScope.launch {
        try {
            withContext(Dispatchers.IO) { BiliApi.modifyRelation(fid = ownerMid, act = 5) }
            AppToast.show(this@blockCurrentUp, "已拉黑UP")
        } catch (t: Throwable) {
            if (t is CancellationException) return@launch
            val e = t as? BiliApiException
            AppToast.show(this@blockCurrentUp, e?.apiMessage?.takeIf { it.isNotBlank() } ?: "拉黑失败")
        }
    }
}

private fun PlayerActivity.removeCurrentVideoFromFavoriteFolder(mediaId: Long) {
    val aid = currentAid?.takeIf { it > 0L }
    if (aid == null) {
        AppToast.show(this, "未获取到 aid，暂不支持取消收藏")
        return
    }
    val requestBvid = currentBvid.trim()
    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    lifecycleScope.launch {
        try {
            val wasFavored = actionFavored
            withContext(Dispatchers.IO) {
                BiliApi.favResourceDelete(aid = aid, mediaId = mediaId)
            }
            val favoredAfterDelete =
                if (selfMid != null && requestBvid.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        runCatching { BiliApi.archiveFavoured(bvid = requestBvid, aid = aid) }.getOrNull()
                    }
                } else {
                    null
                }
            actionFavored = favoredAfterDelete ?: false
            if (wasFavored && !actionFavored) {
                currentPlayerFavCount = currentPlayerFavCount?.let { (it - 1L).coerceAtLeast(0L) }
            }
            updateActionButtonsUi()
            AppToast.show(this@removeCurrentVideoFromFavoriteFolder, "已取消收藏")
        } catch (t: Throwable) {
            if (t is CancellationException) return@launch
            val e = t as? BiliApiException
            AppToast.show(this@removeCurrentVideoFromFavoriteFolder, e?.apiMessage?.takeIf { it.isNotBlank() } ?: "取消收藏失败")
        }
    }
}
