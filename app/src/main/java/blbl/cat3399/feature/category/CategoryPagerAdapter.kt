package blbl.cat3399.feature.category

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Zone
import blbl.cat3399.feature.video.VideoGridFragment
import android.os.SystemClock

class CategoryPagerAdapter(
    fragment: Fragment,
    private val tabs: List<CategoryTab>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        val tab = tabs[position]
        AppLog.d(
            "Category", "createFragment pos=$position title=${tab.title} key=${tab.key} t=${SystemClock.uptimeMillis()}",
        )
        return when (tab.kind) {
            CategoryTabKind.HOME -> VideoGridFragment.newRecommend()
            CategoryTabKind.POPULAR -> VideoGridFragment.newPopular()
            CategoryTabKind.ZONE -> VideoGridFragment.newRegion(tab.zone!!.tid!!)
        }
    }

    override fun getItemId(position: Int): Long = tabs[position].key.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = tabs.any { it.key.hashCode().toLong() == itemId }
}

enum class CategoryTabKind { HOME, POPULAR, ZONE }

data class CategoryTab(val key: String, val title: String, val kind: CategoryTabKind, val zone: Zone? = null)
