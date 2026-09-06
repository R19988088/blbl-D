package blbl.cat3399.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBoostGesturesTest {
    @Test
    fun touchLeftRewindsAndRightAdvancesWithoutCapturingTheCenter() {
        assertEquals(-1, playerTouchBoostDirection(100f, 900f))
        assertEquals(-1, playerTouchBoostDirection(300f, 900f))
        assertEquals(0, playerTouchBoostDirection(450f, 900f))
        assertEquals(1, playerTouchBoostDirection(600f, 900f))
        assertEquals(1, playerTouchBoostDirection(800f, 900f))
        assertEquals(0, playerTouchBoostDirection(0f, 0f))
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
