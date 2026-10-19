package dev.soundlock

import org.junit.Assert.*
import org.junit.Test

class MuteRefreshTest {
    @Test fun automaticLockKeepsEnforcingDespiteFalseZeroAtAccessibilityFloor() {
        val policy = LockPolicy(1_000).apply { updateRoute(LockPolicy.Route.SPEAKER) }
        val refresh = MuteRefresh()
        assertTrue(policy.unlock(0))
        assertTrue(policy.tick(1_000))
        assertTrue(refresh.needed(1_000, policy.shouldMute, true, 0, true))
        refresh.attempted(1_000)
        // The next floor read is still zero, even if one audible step escaped.
        val floorEstimate = VolumeScale.toMedia(1, 1, 15, 160)
        assertEquals(0, floorEstimate)
        assertFalse(refresh.needed(1_499, policy.shouldMute, true, floorEstimate, false))
        assertTrue(refresh.needed(1_500, policy.shouldMute, true, floorEstimate, false))
        assertFalse(policy.unlocked)
    }

    @Test fun volumeKeyForcesZeroEvenImmediatelyAfterAutomaticMute() {
        val refresh = MuteRefresh()
        refresh.attempted(1_000)
        // First down, held repeat and release must all be able to reassert zero.
        for (now in listOf(1_001L, 1_050L, 1_070L)) {
            assertTrue(refresh.needed(now, true, true, 0, true))
            refresh.attempted(now)
        }
    }

    @Test fun confirmedUnlockPausedProtectionAndHeadphonesNeverGetPeriodicMute() {
        val policy = LockPolicy().apply { updateRoute(LockPolicy.Route.SPEAKER) }
        val refresh = MuteRefresh()
        policy.unlock(0)
        assertFalse(refresh.needed(5_000, policy.shouldMute, true, 10, true))
        policy.setArmed(false)
        assertFalse(refresh.needed(6_000, policy.shouldMute, true, 10, true))
        policy.setArmed(true)
        policy.updateRoute(LockPolicy.Route.EXTERNAL)
        assertFalse(refresh.needed(7_000, policy.shouldMute, true, 10, true))
    }

    @Test fun ownSettingsCallbacksCannotCauseAnUnboundedWriteLoop() {
        val refresh = MuteRefresh()
        refresh.attempted(1_000)
        repeat(100) { offset ->
            assertFalse(refresh.needed(1_000L + offset, true, true, 10, false))
        }
        assertTrue(refresh.needed(1_100, true, true, 10, false))
    }

    @Test fun reliableZeroDoesNotNeedPeriodicWritesButPositiveVolumeDoes() {
        val refresh = MuteRefresh()
        refresh.attempted(0)
        assertFalse(refresh.needed(5_000, true, false, 0, false))
        assertTrue(refresh.needed(5_000, true, false, 1, false))
    }
}
