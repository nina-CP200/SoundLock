package dev.soundlock

import org.junit.Assert.*
import org.junit.Test

class LockPolicyTest {
    private fun speaker() = LockPolicy().apply { updateRoute(LockPolicy.Route.SPEAKER) }
    @Test fun defaultsFailClosed() {
        val p = LockPolicy()
        assertTrue(p.shouldMute)
        assertFalse(p.unlock(0))
    }
    @Test fun fifteenMinutesStartsAfterPlaybackStops() {
        val p = speaker()
        p.updatePlayback(true, 100)
        assertTrue(p.unlock(200))
        assertNull(p.deadline)
        assertFalse(p.tick(3_600_000))
        p.updatePlayback(false, 3_600_000)
        assertEquals(4_500_000L, p.deadline)
        assertFalse(p.tick(4_499_999))
        assertTrue(p.tick(4_500_000))
        assertTrue(p.shouldMute)
    }
    @Test fun resumingPlaybackClearsOldDeadline() {
        val p = speaker()
        p.unlock(100)
        p.updatePlayback(true, 500)
        assertNull(p.deadline)
        assertFalse(p.tick(1_000_000))
        p.updatePlayback(false, 1_000_001)
        assertEquals(1_900_001L, p.deadline)
    }
    @Test fun repeatedIdleChecksDoNotRestartTimer() {
        val p = speaker()
        p.unlock(100)
        p.updatePlayback(false, 500)
        p.updatePlayback(false, 1000)
        assertEquals(900_100L, p.deadline)
    }
    @Test fun headphonesBypassLockButDisconnectRevokesLease() {
        val p = speaker()
        p.unlock(100)
        p.updateRoute(LockPolicy.Route.EXTERNAL)
        assertFalse(p.shouldMute)
        assertFalse(p.unlocked)
        p.updateRoute(LockPolicy.Route.SPEAKER)
        assertTrue(p.shouldMute)
        assertTrue(p.shouldConfirm)
    }
    @Test fun unknownRouteCannotKeepAuthorization() {
        val p = speaker()
        p.unlock(100)
        p.updateRoute(LockPolicy.Route.UNKNOWN)
        assertTrue(p.shouldMute)
        assertFalse(p.unlock(200))
        p.updateRoute(LockPolicy.Route.SPEAKER)
        assertFalse(p.unlocked)
    }
    @Test fun returnToZeroVolumeRevokesAuthorization() {
        val p = speaker()
        p.unlock(100)
        p.observeVolume(1)
        p.observeVolume(0)
        assertTrue(p.shouldConfirm)
        assertNull(p.deadline)
    }
    @Test fun delayedSystemVolumeUpdateDoesNotRevokeConfirmation() {
        val p = speaker()
        assertTrue(p.unlock(100))
        repeat(5) { p.observeVolume(0) }
        assertTrue(p.unlocked)
        assertFalse(p.shouldConfirm)
        p.observeVolume(1)
        assertTrue(p.unlocked)
        p.observeVolume(0)
        assertTrue(p.shouldConfirm)
    }
    @Test fun confirmedButStillSilentLeaseExpires() {
        val p = speaker()
        p.unlock(100)
        p.observeVolume(0)
        assertFalse(p.tick(900_099))
        assertTrue(p.tick(900_100))
        assertTrue(p.shouldMute)
    }
    @Test fun pauseDoesNotRestoreOldLease() {
        val p = speaker()
        p.unlock(100)
        p.setArmed(false)
        assertFalse(p.shouldMute)
        assertFalse(p.shouldConfirm)
        p.setArmed(true)
        assertTrue(p.shouldMute)
    }
    @Test fun noisySignalLocksEvenWhileExternal() {
        val p = speaker()
        p.unlock(100)
        p.updateRoute(LockPolicy.Route.EXTERNAL)
        p.lock()
        p.updateRoute(LockPolicy.Route.SPEAKER)
        assertFalse(p.unlocked)
        assertTrue(p.shouldMute)
    }
}
