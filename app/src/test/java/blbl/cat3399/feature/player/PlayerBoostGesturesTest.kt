package blbl.cat3399.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBoostGesturesTest {
    @Test
    fun touchBoostCoversBothOuterThirdsButNotTheCenter() {
        assertTrue(isPlayerTouchBoostZone(100f, 900f))
        assertTrue(isPlayerTouchBoostZone(300f, 900f))
        assertFalse(isPlayerTouchBoostZone(450f, 900f))
        assertTrue(isPlayerTouchBoostZone(600f, 900f))
        assertTrue(isPlayerTouchBoostZone(800f, 900f))
        assertFalse(isPlayerTouchBoostZone(0f, 0f))
    }

    @Test
    fun remoteNeedsTwoPressesOfTheSameDirectionWithinTheWindow() {
        assertTrue(isRemoteBoostSecondPress(21, 1000L, 21, 1250L, 300L))
        assertTrue(isRemoteBoostSecondPress(22, 1000L, 22, 1250L, 300L))
        assertFalse(isRemoteBoostSecondPress(21, 1000L, 22, 1250L, 300L))
        assertFalse(isRemoteBoostSecondPress(21, 1000L, 21, 1301L, 300L))
        assertFalse(isRemoteBoostSecondPress(21, 0L, 21, 250L, 300L))
    }
}
