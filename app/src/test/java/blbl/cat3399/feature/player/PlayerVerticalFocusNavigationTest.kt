package blbl.cat3399.feature.player

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class PlayerVerticalFocusNavigationTest {
    @Test
    fun hidden_osd_up_should_focus_standard_playback_controls() {
        assertEquals(
            PlayerVerticalFocusRegion.CONTROLS,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.OTHER,
                direction = PlayerVerticalDirection.UP,
                hasCollectionThumbs = true,
                hasVideoShotPreview = true,
                hasUpQuick = true,
                isOsdHidden = true,
            ),
        )
    }

    @Test
    fun seekUp_should_enter_collection_thumbnails_before_other_previews() {
        assertEquals(
            PlayerVerticalFocusRegion.COLLECTION_THUMBS,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.SEEK,
                direction = PlayerVerticalDirection.UP,
                hasCollectionThumbs = true,
                hasVideoShotPreview = true,
                hasUpQuick = true,
            ),
        )
    }

    @Test
    fun collection_thumbnails_should_connect_up_quick_and_seek_controls() {
        assertEquals(
            PlayerVerticalFocusRegion.UP_QUICK,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.COLLECTION_THUMBS,
                direction = PlayerVerticalDirection.UP,
                hasCollectionThumbs = true,
                hasVideoShotPreview = true,
                hasUpQuick = true,
            ),
        )
        assertEquals(
            PlayerVerticalFocusRegion.SEEK,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.COLLECTION_THUMBS,
                direction = PlayerVerticalDirection.DOWN,
                hasCollectionThumbs = true,
                hasVideoShotPreview = true,
                hasUpQuick = true,
            ),
        )
        assertEquals(
            PlayerVerticalFocusRegion.COLLECTION_THUMBS,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.UP_QUICK,
                direction = PlayerVerticalDirection.DOWN,
                hasCollectionThumbs = true,
                hasVideoShotPreview = true,
                hasUpQuick = true,
            ),
        )
    }

    @Test
    fun missing_collection_thumbnails_should_keep_existing_fallbacks() {
        assertEquals(
            PlayerVerticalFocusRegion.VIDEO_SHOT_PREVIEW,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.SEEK,
                direction = PlayerVerticalDirection.UP,
                hasCollectionThumbs = false,
                hasVideoShotPreview = true,
                hasUpQuick = true,
            ),
        )
        assertEquals(
            PlayerVerticalFocusRegion.SEEK,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.UP_QUICK,
                direction = PlayerVerticalDirection.DOWN,
                hasCollectionThumbs = false,
                hasVideoShotPreview = false,
                hasUpQuick = true,
            ),
        )
        assertEquals(
            PlayerVerticalFocusRegion.CONTROLS,
            resolvePlayerVerticalFocusTarget(
                current = PlayerVerticalFocusRegion.SEEK,
                direction = PlayerVerticalDirection.DOWN,
                hasCollectionThumbs = false,
                hasVideoShotPreview = false,
                hasUpQuick = true,
            ),
        )
    }

    @Test
    fun player_layouts_should_declare_the_same_three_section_focus_chain() {
        for (layoutName in listOf("activity_player.xml", "activity_player_texture.xml")) {
            val document =
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(findLayout(layoutName))

            val seek = document.elementById("seek_progress")
            val thumbnails = document.elementById("recycler_collection_thumbs")
            val upProfile = document.elementById("btn_up_quick_profile")
            val upFollow = document.elementById("btn_up_quick_follow")

            assertEquals("$layoutName seek up", "@id/recycler_collection_thumbs", seek.androidAttr("nextFocusUp"))
            assertEquals("$layoutName thumbnails up", "@id/btn_up_quick_profile", thumbnails.androidAttr("nextFocusUp"))
            assertEquals("$layoutName thumbnails down", "@id/seek_progress", thumbnails.androidAttr("nextFocusDown"))
            assertEquals("$layoutName UP profile down", "@id/recycler_collection_thumbs", upProfile.androidAttr("nextFocusDown"))
            assertEquals("$layoutName UP follow down", "@id/recycler_collection_thumbs", upFollow.androidAttr("nextFocusDown"))
        }
    }

    private fun findLayout(name: String): File {
        return listOf(
            File("app/src/main/res/layout/$name"),
            File("src/main/res/layout/$name"),
        ).firstOrNull(File::isFile) ?: error("Missing player layout: $name")
    }

    private fun org.w3c.dom.Document.elementById(id: String): Element {
        val nodes = getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.androidAttr("id") == "@+id/$id") return element
        }
        error("Missing view id: $id")
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
