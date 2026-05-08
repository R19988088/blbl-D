package blbl.cat3399.feature.player

import android.view.LayoutInflater
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.DialogPlayerRemoteMenuBinding
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

    dialogBinding.btnFavorite.setOnClickListener {
        handle?.dismiss()
        onFavButtonClicked(showControls = false)
    }
    dialogBinding.btnWatchLater.setOnClickListener {
        handle?.dismiss()
        addCurrentVideoToWatchLater()
    }
    dialogBinding.btnBlockUp.setOnClickListener {
        handle?.dismiss()
        blockCurrentUp()
    }

    handle =
        PopupHost.from(this).showModal(
            title = "菜单",
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
