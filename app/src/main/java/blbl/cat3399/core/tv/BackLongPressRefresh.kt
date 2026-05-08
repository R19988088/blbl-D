package blbl.cat3399.core.tv

import android.view.KeyEvent

class BackLongPressRefresh(
    private val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    private var backDown: Boolean = false
    private var refreshTriggered: Boolean = false

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
                backDown = true
                if (!refreshTriggered && event.eventTime - event.downTime >= longPressMs) {
                    refreshTriggered = onRefresh()
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                val shouldRefresh = backDown && !refreshTriggered && event.eventTime - event.downTime >= longPressMs
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
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 700L
    }
}
