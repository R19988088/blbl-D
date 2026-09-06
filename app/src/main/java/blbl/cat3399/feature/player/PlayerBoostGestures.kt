package blbl.cat3399.feature.player

internal const val PLAYER_HOLD_BOOST_SPEED = 3.0f

internal fun isPlayerTouchBoostZone(x: Float, width: Float): Boolean =
    width > 0f && (x <= width / 3f || x >= width * 2f / 3f)

internal fun isRemoteBoostSecondPress(
    firstKey: Int,
    firstReleaseMs: Long,
    secondKey: Int,
    secondDownMs: Long,
    timeoutMs: Long,
): Boolean = firstKey == secondKey && firstReleaseMs > 0L && secondDownMs - firstReleaseMs in 0L..timeoutMs
