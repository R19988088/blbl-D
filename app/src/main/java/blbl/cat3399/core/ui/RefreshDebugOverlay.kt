package blbl.cat3399.core.ui

import android.content.Context
import android.os.SystemClock

object RefreshDebugOverlay {
    fun show(
        context: Context,
        source: String,
        page: Any?,
        handled: Boolean? = null,
        extra: String? = null,
    ) {
        val time = SystemClock.uptimeMillis() % 100_000L
        val pageName = page?.javaClass?.simpleName ?: "null"
        val handledText = handled?.let { " handled=$it" }.orEmpty()
        val extraText = extra?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        AppToast.showDebug(context, "REFRESH DEBUG $time\n$source page=$pageName$handledText$extraText")
    }
}
