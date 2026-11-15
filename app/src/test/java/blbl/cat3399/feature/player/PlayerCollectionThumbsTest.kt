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
}
