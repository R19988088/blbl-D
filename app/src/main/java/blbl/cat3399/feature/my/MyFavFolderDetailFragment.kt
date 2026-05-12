package blbl.cat3399.feature.my

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.RefreshDebugOverlay
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.core.ui.setTextSizePxIfChanged
import blbl.cat3399.core.ui.uiScaler
import blbl.cat3399.databinding.FragmentMyFavFolderDetailBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardFavoriteActionMode
import blbl.cat3399.feature.video.VideoCardQuickActionId
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildPagedVideoCardPlaybackHandle
import blbl.cat3399.feature.video.defaultVideoCardPlaylistItem
import blbl.cat3399.feature.video.openVideoDetailFromPlaybackHandle
import blbl.cat3399.feature.video.openVideoFromPlaybackHandle
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.feature.video.showVideoCardRemoteMenuPopup
import blbl.cat3399.core.ui.TabContentFocusTarget
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.feature.favorite.FavoriteFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyFavFolderDetailFragment : Fragment(), RefreshKeyHandler, TabContentFocusTarget {
    private var _binding: FragmentMyFavFolderDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var lastAppliedHeaderSizingScale: Float? = null
    private var baseTitleTextSizePx: Float? = null

    private val mediaId: Long by lazy { requireArguments().getLong(ARG_MEDIA_ID) }
    private val title: String by lazy { requireArguments().getString(ARG_TITLE).orEmpty() }
    private val embeddedInFavoriteTabs: Boolean by lazy { requireArguments().getBoolean(ARG_EMBEDDED_IN_FAVORITE_TABS, false) }

    private val loadedStableKeys = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var pendingFocusFirstItem: Boolean = false
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null
    private val coverPreloadHandler = Handler(Looper.getMainLooper())
    private var coverPreloadRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyFavFolderDetailBinding.inflate(inflater, container, false)
        binding.btnBack.visibility = if (embeddedInFavoriteTabs) View.GONE else View.VISIBLE
        binding.tvTitle.visibility = if (embeddedInFavoriteTabs) View.GONE else View.VISIBLE
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStackImmediate() }
        binding.tvTitle.text = title.ifBlank { getString(R.string.my_fav_default_title) }
        applyHeaderSizing(uiScale = UiScale.factor(requireContext()))

        if (!::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = requireContext(),
                    scope = viewLifecycleOwner.lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.DeleteFavFolderItem(mediaId = mediaId),
                    onOpenDetail = { _, pos -> openDetail(pos) },
                    onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                    onCardRemoved = { stableKey ->
                        _binding?.recycler?.removeVideoCardAndRestoreFocus(
                            adapter = adapter,
                            stableKey = stableKey,
                            isAlive = { _binding != null && isResumed },
                        )
                    },
                    favoriteActionMode = VideoCardFavoriteActionMode.REMOVE,
                )
            adapter =
                VideoCardAdapter(
                    onClick = { _, pos ->
                        val card = adapter.snapshot().getOrNull(pos)
                        if (card?.bvid.isNullOrBlank()) {
                            AppToast.show(requireContext(), "该视频已失效")
                            return@VideoCardAdapter
                        }
                        requireContext().openVideoFromPlaybackHandle(
                            playbackHandle = playbackHandle(),
                            position = pos,
                            openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                        )
                    },
                    onLongClick = { card, pos -> showVideoCardMenu(card, pos, actionController) },
                    actionDelegate = actionController,
                )
        }
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        val spanCount = spanCountForWidth(resources)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.recycler.setItemViewCacheSize(spanCount * 3)
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        scheduleNextCoverPreload(recyclerView)
                    } else {
                        coverPreloadRunnable?.let(coverPreloadHandler::removeCallbacks)
                        coverPreloadRunnable = null
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            if (embeddedInFavoriteTabs) return focusFavoriteTabIfAvailable()
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            if (embeddedInFavoriteTabs) return switchFavoriteTabPrevious()
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onRightEdge() {
                            if (embeddedInFavoriteTabs) switchFavoriteTabNext()
                        }

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        viewportFillMonitor?.release()
        viewportFillMonitor =
            binding.recycler.installGridViewportFillMonitor(
                isEnabled = { _binding != null && isResumed },
                canLoadMore = { !isLoadingMore && !endReached },
                loadMore = { loadNextPage() },
            )
        binding.swipeRefresh.setOnRefreshListener {
            handleFullRefreshKey()
        }

        if (savedInstanceState == null) {
            binding.swipeRefresh.isRefreshing = true
            if (!embeddedInFavoriteTabs) {
                pendingFocusFirstItem = true
                binding.recycler.requestFocus()
            }
            resetAndLoad()
        }

    }

    override fun requestFocusPrimaryItemFromTab(): Boolean = requestFocusFirstCard()

    override fun requestFocusPrimaryItemFromContentSwitch(): Boolean = requestFocusFirstCard()

    override fun requestFocusPrimaryItemFromBackToTab0(): Boolean = requestFocusFirstCard()

    private fun requestFocusFirstCard(): Boolean {
        pendingFocusFirstItem = true
        val b = _binding ?: return true
        if (!isResumed) return true
        if (!::adapter.isInitialized) return false

        val recycler = b.recycler
        if (adapter.itemCount <= 0) {
            recycler.requestFocus()
            return true
        }

        val isUiAlive = { _binding === b && isResumed }
        recycler.requestFocusAdapterPositionReliable(
            position = 0,
            smoothScroll = false,
            isAlive = isUiAlive,
            onFocused = { pendingFocusFirstItem = false },
        )
        return true
    }

    override fun onResume() {
        super.onResume()
        applyBackButtonSizing()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        viewportFillMonitor?.scheduleCheck()
    }

    private fun applyHeaderSizing(uiScale: Float) {
        val b = _binding ?: return
        val scale = uiScale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        if (lastAppliedHeaderSizingScale == scale) return

        val scaler = requireContext().uiScaler(scale)
        val baseTs = baseTitleTextSizePx ?: b.tvTitle.textSize.also { baseTitleTextSizePx = it }
        b.tvTitle.setTextSizePxIfChanged(scaler.scaledPxF(baseTs, minPx = 1f))

        lastAppliedHeaderSizingScale = scale
    }

    private fun applyBackButtonSizing() {
        val sidebarScale = UiScale.factor(requireContext())
        BackButtonSizingHelper.applySidebarSizing(
            view = binding.btnBack,
            resources = resources,
            sidebarScale = sidebarScale,
        )
    }

    override fun handleRefreshKey(): Boolean {
        return refreshFromKey(force = false)
    }

    override fun handleFullRefreshKey(): Boolean {
        val handled = refreshFromKey(force = true)
        RefreshDebugOverlay.show(requireContext(), source = "pull/back refresh", page = this, pageName = "MyFavFolderDetailFragment", handled = handled)
        return handled
    }

    private fun refreshFromKey(force: Boolean): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (!force && b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        pendingFocusFirstItem = !embeddedInFavoriteTabs
        dpadGridController?.parkFocusForDataSetReset()
        resetAndLoad()
        return true
    }

    private fun resetAndLoad() {
        dpadGridController?.parkFocusForDataSetReset()
        loadedStableKeys.clear()
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var targetPage = page
                var loadedPage: Pair<List<blbl.cat3399.core.model.VideoCard>, Boolean>? = null
                while (loadedPage == null) {
                    val res = BiliApi.favFolderResources(mediaId = mediaId, pn = targetPage, ps = 20)
                    if (token != requestToken) return@launch
                    val hasMore = res.hasMore
                    val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(res.items, loadedStableKeys)
                    targetPage++
                    if (visibleItems.isNotEmpty() || !hasMore) {
                        loadedPage = visibleItems to hasMore
                    }
                }
                val (visibleItems, hasMore) = checkNotNull(loadedPage)
                if (token != requestToken) return@launch
                visibleItems.forEach { loadedStableKeys.add(it.stableKey()) }
                if (isRefresh) adapter.submit(visibleItems) else adapter.append(visibleItems)
                maybeFocusFirstItem()
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) { dpadGridController?.consumePendingFocusAfterLoadMore() }
                endReached = !hasMore
                page = targetPage
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyFavDetail", "load failed mediaId=$mediaId", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
                viewportFillMonitor?.scheduleCheck()
            }
        }
    }

    private fun maybeFocusFirstItem(): Boolean {
        if (!pendingFocusFirstItem) return false
        if (_binding == null) return false
        val recycler = binding.recycler
        val isUiAlive = { _binding != null && isResumed }
        recycler.requestFocusFirstItemOrSelfAfterRefresh(
            itemCount = adapter.itemCount,
            smoothScroll = false,
            isAlive = isUiAlive,
            onDone = { pendingFocusFirstItem = false },
        )
        return true
    }

    private fun scheduleNextCoverPreload(recyclerView: RecyclerView) {
        val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return
        coverPreloadRunnable?.let(coverPreloadHandler::removeCallbacks)
        val expectedBinding = _binding ?: return
        coverPreloadRunnable =
            Runnable {
                if (_binding !== expectedBinding || !isResumed) return@Runnable
                val covers =
                    adapter.snapshot()
                        .asSequence()
                        .drop(lastVisible + 1)
                        .take(8)
                        .map { it.coverUrl }
                        .toList()
                ImageLoader.preloadCovers(requireContext(), covers)
            }
        coverPreloadHandler.postDelayed(coverPreloadRunnable!!, 120L)
    }

    private fun focusFavoriteTabIfAvailable(): Boolean {
        if (!embeddedInFavoriteTabs) return false
        val tabLayout = parentFragment?.view?.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun switchFavoriteTabNext(): Boolean = switchFavoriteTab(delta = 1)

    private fun switchFavoriteTabPrevious(): Boolean = switchFavoriteTab(delta = -1)

    private fun switchFavoriteTab(delta: Int): Boolean {
        if (!embeddedInFavoriteTabs) return false
        val tabLayout = parentFragment?.view?.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + delta
        if (next !in 0 until tabLayout.tabCount) return false
        tabLayout.getTabAt(next)?.select() ?: return false
        tabLayout.postIfAttached {
            (parentFragment as? FavoriteFragment)?.requestFocusCurrentFolderContent()
                ?: tabStrip.getChildAt(next)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        coverPreloadRunnable?.let(coverPreloadHandler::removeCallbacks)
        coverPreloadRunnable = null
        _binding = null
        super.onDestroyView()
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromPlaybackHandle(playbackHandle(), position)
    }

    private fun showVideoCardMenu(
        card: blbl.cat3399.core.model.VideoCard,
        position: Int,
        actionController: VideoCardActionController,
    ): Boolean {
        val activity = activity as? ComponentActivity ?: return false
        val actions =
            actionController.manualActions(card, position)
                .filter { it.id != VideoCardQuickActionId.OPEN_DETAIL }
                .take(4)
        activity.showVideoCardRemoteMenuPopup(card, actions) { action ->
            actionController.onActionSelected(card, position, action)
        }
        return true
    }

    private fun playbackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "MyFavFolderDetail:$mediaId",
            cardsProvider = adapter::snapshot,
            nextCursorProvider = { page },
            hasMoreProvider = { !endReached },
        ) { targetPage ->
            val pageNum = targetPage.coerceAtLeast(1)
            val res = BiliApi.favFolderResources(mediaId = mediaId, pn = pageNum, ps = 20)
            VideoCardPlaylistPage(
                cards = res.items,
                nextCursor = pageNum + 1,
                hasMore = res.hasMore,
                canAdvance = res.hasMore && res.items.isNotEmpty(),
            )
        }

    companion object {
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"

        private const val ARG_EMBEDDED_IN_FAVORITE_TABS = "embedded_in_favorite_tabs"

        fun newInstance(
            mediaId: Long,
            title: String,
            embeddedInFavoriteTabs: Boolean = false,
        ): MyFavFolderDetailFragment =
            MyFavFolderDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                    putBoolean(ARG_EMBEDDED_IN_FAVORITE_TABS, embeddedInFavoriteTabs)
                }
            }
    }
}
