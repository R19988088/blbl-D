package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAutoNextTitleTest {
    @Test
    fun keepsShortTitlesAfterWhitespaceNormalization() {
        assertEquals("第12话", formatAutoNextHintTitle("  第12话  ", fallbackTitle = "推荐视频"))
    }

    @Test
    fun truncatesTitlesToMaxCharsBeforeEllipsis() {
        val maxChars = PlayerActivity.AUTO_NEXT_TITLE_MAX_CHARS
        val longTitle = "123456789012345678901234567890"
        assertEquals(longTitle.take(maxChars) + "...", formatAutoNextHintTitle(longTitle, fallbackTitle = "推荐视频"))
    }

    @Test
    fun fallsBackWhenTitleIsBlank() {
        assertEquals("推荐视频", formatAutoNextHintTitle("  \n\t  ", fallbackTitle = "推荐视频"))
    }
}
