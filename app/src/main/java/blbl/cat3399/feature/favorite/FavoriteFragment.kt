package blbl.cat3399.feature.favorite

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.model.FavFolder
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.TabContentFocusTarget
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusSelectedTab
import blbl.cat3399.databinding.FragmentFavoriteBinding
import blbl.cat3399.feature.my.MyFavFolderDetailFragment
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.SidebarFocusHost
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class FavoriteFragment : Fragment(), BackPressHandler, RefreshKeyHandler, TabContentSwitchFocusHost {
    private var _binding: FragmentFavoriteBinding? = null
    private val binding get() = _binding!!

    private var folders: List<FavFolder> = emptyList()
    private var selectedIndex: Int = 0
    private var loadToken: Int = 0
    private var pendingFocusContent: Boolean = false

    private fun focusCurrentContentFromTab(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.favorite_container) as? TabContentFocusTarget ?: return false
        val handled = current.requestFocusPrimaryItemFromTab()
        if (handled) pendingFocusContent = false
        return handled
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFavoriteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadFolders()
    }

    override fun onResume() {
        super.onResume()
        if (folders.isEmpty()) loadFolders()
    }

    override fun requestFocusCurrentPagePrimaryItemFromContentSwitch(): Boolean {
        return focusSelectedTab()
    }

    fun requestFocusCurrentFolderContent(): Boolean {
        pendingFocusContent = true
        if (focusCurrentContent()) return true
        binding.favoriteContainer.postIfAlive(isAlive = { _binding != null }) { focusCurrentContent() }
        return true
    }

    override fun handleRefreshKey(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.favorite_container)
        return (current as? RefreshKeyHandler)?.handleRefreshKey() == true
    }

    override fun handleFullRefreshKey(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.favorite_container)
        return (current as? RefreshKeyHandler)?.handleFullRefreshKey() == true
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        if (b.tabLayout.hasFocus()) {
            return (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
        }
        return focusSelectedTab()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun loadFolders() {
        val token = ++loadToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val mid = BiliApi.nav().optJSONObject("data")?.optLong("mid") ?: 0L
                if (mid <= 0L || !BiliClient.cookies.hasSessData()) {
                    AppToast.show(requireContext(), "请先登录后查看收藏")
                    return@launch
                }
                val nextFolders = applyVisibleFolderPrefs(BiliApi.favFolders(upMid = mid))
                if (token != loadToken || _binding == null) return@launch
                if (nextFolders.isEmpty()) {
                    AppToast.show(requireContext(), "未获取到收藏夹")
                    return@launch
                }
                applyFolders(nextFolders)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                context?.let { AppToast.show(it, "加载收藏失败") }
            }
        }
    }

    private fun applyFolders(nextFolders: List<FavFolder>) {
        val b = _binding ?: return
        val currentMediaId = folders.getOrNull(selectedIndex)?.mediaId
        folders = nextFolders
        selectedIndex = currentMediaId?.let { mediaId -> folders.indexOfFirst { it.mediaId == mediaId }.takeIf { it >= 0 } } ?: 0

        b.tabLayout.removeAllTabs()
        folders.forEach { folder ->
            b.tabLayout.addTab(b.tabLayout.newTab().setText(folder.title.ifBlank { getString(R.string.my_fav_default_title) }))
        }
        b.tabLayout.clearOnTabSelectedListeners()
        b.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectedIndex = tab.position
                    showSelectedFolder()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) {
                    requestFocusCurrentFolderContent()
                }
            },
        )
        b.tabLayout.getTabAt(selectedIndex)?.select()
        installTabFocusHandlers()
        showSelectedFolder()
    }

    private fun applyVisibleFolderPrefs(allFolders: List<FavFolder>): List<FavFolder> {
        val selected = BiliClient.prefs.favoriteVisibleFolderIds.takeIf { it.isNotEmpty() }?.toSet() ?: return allFolders
        return allFolders.filter { it.mediaId.toString() in selected }.ifEmpty { allFolders }
    }

    private fun showSelectedFolder() {
        val folder = folders.getOrNull(selectedIndex) ?: return
        if (_binding == null || childFragmentManager.isStateSaved) return
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(
                R.id.favorite_container,
                MyFavFolderDetailFragment.newInstance(
                    mediaId = folder.mediaId,
                    title = folder.title,
                    embeddedInFavoriteTabs = true,
                ),
            )
            .commit()
        if (pendingFocusContent) {
            binding.favoriteContainer.postIfAlive(isAlive = { _binding != null }) { focusCurrentContent() }
        }
    }

    private fun installTabFocusHandlers() {
        val b = _binding ?: return
        val tabLayout = b.tabLayout
        tabLayout.postIfAlive(isAlive = { _binding === b }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus })
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return@setOnKeyListener focusCurrentContentFromTab()
                    }
                    false
                }
            }
        }
    }

    private fun focusCurrentContent(): Boolean {
        val current = childFragmentManager.findFragmentById(R.id.favorite_container) as? TabContentFocusTarget ?: return false
        val handled = current.requestFocusPrimaryItemFromContentSwitch()
        if (handled) pendingFocusContent = false
        return handled
    }

    private fun focusSelectedTab(): Boolean {
        val b = _binding ?: return false
        return b.tabLayout.requestFocusSelectedTab(fallbackPosition = selectedIndex) { _binding != null }
    }

    companion object {
        fun newInstance() = FavoriteFragment()
    }
}
