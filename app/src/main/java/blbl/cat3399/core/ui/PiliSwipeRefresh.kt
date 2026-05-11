package blbl.cat3399.core.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import blbl.cat3399.R
import java.util.WeakHashMap
import kotlin.math.roundToInt

object PiliSwipeRefresh {
    private const val REFRESH_DISPLACEMENT_DP = 40f
    private val configuredHeights = WeakHashMap<SwipeRefreshLayout, Int>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    configureActivity(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    configureActivity(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun configureActivity(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.getTag(R.id.tag_pili_swipe_refresh_global_layout) == null) {
            val listener =
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    configureTree(decor)
                }
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            decor.setTag(R.id.tag_pili_swipe_refresh_global_layout, listener)
        }
        decor.post { configureTree(decor) }
    }

    private fun configureTree(view: View) {
        if (view is SwipeRefreshLayout) {
            configure(view)
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            configureTree(view.getChildAt(index))
        }
    }

    private fun configure(refresh: SwipeRefreshLayout) {
        val triggerDistance = REFRESH_DISPLACEMENT_DP.dp(refresh).coerceAtLeast(1)
        if (configuredHeights[refresh] == triggerDistance) return
        configuredHeights[refresh] = triggerDistance

        val context = refresh.context
        val accent = ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_blue)
        val background = ThemeColor.resolve(context, R.attr.blblContentBackground, R.color.blbl_surface)
        refresh.setColorSchemeColors(accent)
        refresh.setProgressBackgroundColorSchemeColor(background)
        refresh.setProgressViewOffset(
            false,
            0,
            REFRESH_DISPLACEMENT_DP.dp(refresh),
        )

        refresh.setDistanceToTriggerSync(triggerDistance)
    }

    private fun Float.dp(view: View): Int =
        (this * view.resources.displayMetrics.density).roundToInt()
}
