package blbl.cat3399.core.tv

import android.view.KeyEvent

class BackLongPressRefresh(
    private val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    private var backDown: Boolean = false
    private var refreshTriggered: Boolean = false
    private var backDownAtMs: Long = 0L

    fun handle(
        event: KeyEvent,
        canRefresh: Boolean = true,
        onRefresh: () -> Boolean,
        onShortBack: () -> Boolean,
    ): Boolean {
        if (!canRefresh || event.keyCode != KeyEvent.KEYCODE_BACK) {
            reset()
            return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!backDown) {
                    backDown = true
                    backDownAtMs = event.downTime.takeIf { it > 0L } ?: event.eventTime
                }
                val heldLongEnough =
                    event.repeatCount > 0 ||
                        event.eventTime - backDownAtMs >= longPressMs
                if (!refreshTriggered && heldLongEnough) {
                    refreshTriggered = onRefresh()
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                val shouldRefresh =
                    backDown &&
                        !refreshTriggered &&
                        event.eventTime - backDownAtMs >= longPressMs
                if (shouldRefresh) refreshTriggered = onRefresh()
                val handled = if (refreshTriggered) true else onShortBack()
                reset()
                handled
            }

            else -> false
        }
    }

    fun reset() {
        backDown = false
        refreshTriggered = false
        backDownAtMs = 0L
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 700L
    }
}
