package blbl.cat3399.feature.video

import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.DialogPlayerRemoteMenuBinding

fun ComponentActivity.showVideoCardRemoteMenuPopup(
    card: VideoCard,
    actions: List<VideoCardQuickAction>,
    onActionSelected: (VideoCardQuickAction) -> Unit,
) {
    val safeActions = actions.take(3)
    if (safeActions.isEmpty()) return

    val dialogBinding = DialogPlayerRemoteMenuBinding.inflate(LayoutInflater.from(this))
    ImageLoader.loadInto(dialogBinding.imgCover, ImageUrl.cover(card.coverUrl))
    ImageLoader.loadInto(dialogBinding.imgUpAvatar, card.ownerFace)
    dialogBinding.tvUpId.text = card.remoteMenuDisplayText()

    val buttons = listOf(dialogBinding.btnFavorite, dialogBinding.btnWatchLater, dialogBinding.btnBlockUp)
    var handle: PopupHandle? = null
    buttons.forEachIndexed { index, button ->
        val action = safeActions.getOrNull(index)
        button.text = action?.contentDescription?.toString().orEmpty()
        button.isEnabled = action != null
        button.alpha = if (action != null) 1f else 0.4f
        button.setOnClickListener {
            val selected = action ?: return@setOnClickListener
            handle?.dismiss()
            onActionSelected(selected)
        }
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
                buttons.firstOrNull { it.isEnabled }?.post { it.requestFocus() }
            },
        )
}

private fun VideoCard.remoteMenuDisplayText(): String {
    val name = ownerName.trim()
    val mid = ownerMid?.takeIf { it > 0L }
    return when {
        name.isNotBlank() && mid != null -> "$name\nID: $mid"
        name.isNotBlank() -> name
        mid != null -> "ID: $mid"
        else -> "UP"
    }
}
