package blbl.cat3399.feature.player

internal enum class PlayerVideoListKind {
    PAGE,
    PARTS,
    RECOMMEND,
}

internal fun collectionThumbsListKind(showRecommend: Boolean, hasParts: Boolean): PlayerVideoListKind =
    when {
        showRecommend -> PlayerVideoListKind.RECOMMEND
        hasParts -> PlayerVideoListKind.PARTS
        else -> PlayerVideoListKind.PAGE
    }
