package dev.soundlock

import org.junit.Assert.*
import org.junit.Test

class VolumeKeyGateTest {
    @Test fun firstPressAfterAutomaticLockIsConsumedBeforeAnyAudioRefresh() {
        val policy = LockPolicy(1_000).apply { updateRoute(LockPolicy.Route.SPEAKER) }
        val gate = VolumeKeyGate()
        assertTrue(policy.unlock(0))
        // Nothing has polled the audio service since unlock. Expire locally,
        // just as the callback does when a deadline wakes late.
        assertTrue(policy.tick(1_000))
        val first = gate.handle(VolumeKeyGate.Action.DOWN, 0, policy.shouldConfirm, false)
        assertTrue(first.consume)
        assertTrue(first.confirm)
        assertTrue(first.mute)
        assertFalse(policy.unlocked)
    }

    @Test fun heldRepeatsAndReleaseStayConsumedWithoutReopeningConfirmation() {
        val gate = VolumeKeyGate()
        assertTrue(gate.handle(VolumeKeyGate.Action.DOWN, 0, true, false).confirm)
        repeat(10) {
            val repeat = gate.handle(VolumeKeyGate.Action.DOWN, it + 1, true, false)
            assertTrue(repeat.consume)
            assertTrue(repeat.mute)
            assertFalse(repeat.confirm)
        }
        val release = gate.handle(VolumeKeyGate.Action.UP, 0, true, false)
        assertTrue(release.consume)
        assertTrue(release.mute)
        assertFalse(release.confirm)
        assertTrue(gate.handle(VolumeKeyGate.Action.DOWN, 0, true, false).confirm)
    }

    @Test fun explicitUnlockDuringGestureDoesNotLetHeldKeyRaiseVolume() {
        val gate = VolumeKeyGate()
        gate.handle(VolumeKeyGate.Action.DOWN, 0, true, false)
        val held = gate.handle(VolumeKeyGate.Action.DOWN, 1, false, false)
        assertTrue(held.consume)
        assertFalse(held.mute)
        assertTrue(gate.handle(VolumeKeyGate.Action.UP, 0, false, false).consume)
        assertFalse(gate.handle(VolumeKeyGate.Action.DOWN, 0, false, false).consume)
    }

    @Test fun callsAlarmsExternalDevicesAndPausedProtectionPassThrough() {
        for ((locked, exempt) in listOf(true to true, false to false, false to true)) {
            val gate = VolumeKeyGate()
            val down = gate.handle(VolumeKeyGate.Action.DOWN, 0, locked, exempt)
            assertFalse(down.consume)
            assertFalse(down.mute)
            assertFalse(down.confirm)
            assertFalse(gate.handle(VolumeKeyGate.Action.UP, 0, locked, exempt).consume)
        }
    }

    @Test fun orphanReleaseDoesNotArmTheNextGesture() {
        val gate = VolumeKeyGate()
        assertFalse(gate.handle(VolumeKeyGate.Action.UP, 0, true, false).consume)
        assertTrue(gate.handle(VolumeKeyGate.Action.DOWN, 0, true, false).confirm)
    }
}
