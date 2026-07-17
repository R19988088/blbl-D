package blbl.cat3399.feature.player

internal enum class PlayerVerticalFocusRegion {
    UP_QUICK,
    COLLECTION_THUMBS,
    VIDEO_SHOT_PREVIEW,
    SEEK,
    CONTROLS,
    OTHER,
}

internal enum class PlayerVerticalDirection {
    UP,
    DOWN,
}

internal fun resolvePlayerVerticalFocusTarget(
    current: PlayerVerticalFocusRegion,
    direction: PlayerVerticalDirection,
    hasCollectionThumbs: Boolean,
    hasVideoShotPreview: Boolean,
    hasUpQuick: Boolean,
    isOsdHidden: Boolean = false,
): PlayerVerticalFocusRegion? {
    if (direction == PlayerVerticalDirection.UP && isOsdHidden) {
        return PlayerVerticalFocusRegion.CONTROLS
    }
    return when (direction) {
        PlayerVerticalDirection.UP ->
            when (current) {
                PlayerVerticalFocusRegion.COLLECTION_THUMBS,
                PlayerVerticalFocusRegion.VIDEO_SHOT_PREVIEW,
                -> PlayerVerticalFocusRegion.UP_QUICK.takeIf { hasUpQuick }

                PlayerVerticalFocusRegion.SEEK ->
                    when {
                        hasCollectionThumbs -> PlayerVerticalFocusRegion.COLLECTION_THUMBS
                        hasVideoShotPreview -> PlayerVerticalFocusRegion.VIDEO_SHOT_PREVIEW
                        else -> null
                    }

                else -> null
            }

        PlayerVerticalDirection.DOWN ->
            when (current) {
                PlayerVerticalFocusRegion.UP_QUICK ->
                    when {
                        hasCollectionThumbs -> PlayerVerticalFocusRegion.COLLECTION_THUMBS
                        hasVideoShotPreview -> PlayerVerticalFocusRegion.VIDEO_SHOT_PREVIEW
                        else -> PlayerVerticalFocusRegion.SEEK
                    }

                PlayerVerticalFocusRegion.COLLECTION_THUMBS,
                PlayerVerticalFocusRegion.VIDEO_SHOT_PREVIEW,
                -> PlayerVerticalFocusRegion.SEEK

                PlayerVerticalFocusRegion.SEEK -> PlayerVerticalFocusRegion.CONTROLS
                else -> null
            }
    }
}
