package blbl.cat3399.feature.player

internal enum class PlayerVideoListKind {
    PAGE,
    PARTS,
    RECOMMEND,
}

internal fun collectionThumbsListKind(
    showRecommend: Boolean,
    hasParts: Boolean,
    preferEntryPage: Boolean = false,
): PlayerVideoListKind =
    when {
        showRecommend -> PlayerVideoListKind.RECOMMEND
        preferEntryPage -> PlayerVideoListKind.PAGE
        hasParts -> PlayerVideoListKind.PARTS
        else -> PlayerVideoListKind.PAGE
    }
