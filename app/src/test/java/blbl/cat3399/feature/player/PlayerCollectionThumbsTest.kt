package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerCollectionThumbsTest {
    @Test
    fun listUsesVideoPartsBeforeEntryPageAndRecommendationIsExplicit() {
        assertEquals(PlayerVideoListKind.PARTS, collectionThumbsListKind(false, true))
        assertEquals(PlayerVideoListKind.PAGE, collectionThumbsListKind(false, false))
        assertEquals(PlayerVideoListKind.RECOMMEND, collectionThumbsListKind(true, true))
        assertEquals(PlayerVideoListKind.RECOMMEND, collectionThumbsListKind(true, false))
    }

    @Test
    fun watchLaterEntryPrefersItsOwnPageList() {
        assertEquals(PlayerVideoListKind.PAGE, collectionThumbsListKind(false, true, preferEntryPage = true))
        assertEquals(PlayerVideoListKind.PAGE, collectionThumbsListKind(false, false, preferEntryPage = true))
        assertEquals(PlayerVideoListKind.RECOMMEND, collectionThumbsListKind(true, true, preferEntryPage = true))
    }

    @Test
    fun stripOnlyAppearsWhenUserPressesUp() {
        // 暂停 / 左右拖动：控件可见但用户没按“上”，不显示缩略图列表。
        assertEquals(
            false,
            collectionThumbsStripShouldShow(osdVisible = true, requested = false, hasCards = true, bottomPanelVisible = false),
        )
        // 按“上”：控件 + 列表一起出现，可直接左右切换。
        assertEquals(
            true,
            collectionThumbsStripShouldShow(osdVisible = true, requested = true, hasCards = true, bottomPanelVisible = false),
        )
        // 控件隐藏 / 没有列表 / 底部列表面板已打开时都不显示。
        assertEquals(
            false,
            collectionThumbsStripShouldShow(osdVisible = false, requested = true, hasCards = true, bottomPanelVisible = false),
        )
        assertEquals(
            false,
            collectionThumbsStripShouldShow(osdVisible = true, requested = true, hasCards = false, bottomPanelVisible = false),
        )
        assertEquals(
            false,
            collectionThumbsStripShouldShow(osdVisible = true, requested = true, hasCards = true, bottomPanelVisible = true),
        )
    }
}
