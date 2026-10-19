package dev.soundlock

import org.junit.Assert.*
import org.junit.Test

class VolumeScaleTest {
    @Test fun accessibilityFloorMapsToAnEstimateOfZero() {
        assertEquals(0, VolumeScale.toMedia(1, 1, 15, 160))
        assertEquals(1, VolumeScale.toAccessibility(0, 160, 1, 15))
    }
    @Test fun lowUnlockIsAudibleButNotAnOldHighVolume() {
        val index = VolumeScale.toAccessibility(1, 160, 1, 15)
        assertEquals(2, index)
        assertTrue(VolumeScale.toMedia(index, 1, 15, 160) in 1..12)
    }
    @Test fun upperAndLowerBoundsClampSafely() {
        assertEquals(0, VolumeScale.toMedia(-5, 1, 15, 160))
        assertEquals(160, VolumeScale.toMedia(30, 1, 15, 160))
        assertEquals(15, VolumeScale.toAccessibility(999, 160, 1, 15))
    }
    @Test fun differentVendorRangesKeepMuteAtTheFloor() {
        assertEquals(0, VolumeScale.toMedia(0, 0, 15, 15))
        assertEquals(15, VolumeScale.toMedia(15, 0, 15, 15))
        assertEquals(1, VolumeScale.toAccessibility(1, 15, 0, 15))
    }
}
