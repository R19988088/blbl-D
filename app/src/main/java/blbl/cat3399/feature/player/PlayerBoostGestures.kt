package blbl.cat3399.feature.player

internal const val PLAYER_HOLD_BOOST_SPEED = 3.0f

internal fun playerTouchBoostDirection(x: Float, width: Float): Int =
    when {
        width <= 0f -> 0
        x <= width / 3f -> -1
        x >= width * 2f / 3f -> 1
        else -> 0
    }

internal fun isRemoteBoostSecondPress(
    firstKey: Int,
    firstReleaseMs: Long,
    secondKey: Int,
    secondDownMs: Long,
    timeoutMs: Long,
): Boolean = firstKey == secondKey && firstReleaseMs > 0L && secondDownMs - firstReleaseMs in 0L..timeoutMs
