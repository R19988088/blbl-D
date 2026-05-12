package blbl.cat3399.core.ui

import android.content.Context
import android.os.SystemClock

object RefreshDebugOverlay {
    private const val MAX_LINES = 8
    private val lines = ArrayDeque<String>()

    fun show(
        context: Context,
        source: String,
        page: Any?,
        pageName: String? = null,
        handled: Boolean? = null,
        extra: String? = null,
    ) {
        val time = SystemClock.uptimeMillis() % 100_000L
        val stablePageName = pageName ?: page?.javaClass?.simpleName ?: "null"
        val handledText = handled?.let { " handled=$it" }.orEmpty()
        val extraText = extra?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        appendAndShow(
            context = context,
            line = "$time $source page=$stablePageName$handledText$extraText",
        )
    }

    fun log(context: Context, pageName: String, message: String) {
        val time = SystemClock.uptimeMillis() % 100_000L
        appendAndShow(context = context, line = "$time $pageName $message")
    }

    private fun appendAndShow(context: Context, line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        AppToast.showDebug(context, "REFRESH\n${lines.joinToString("\n")}")
    }
}
