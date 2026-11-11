package dev.soundlock

/** A coarse vendor readback at its floor cannot establish that media is silent. */
class MuteRefresh {
    enum class Status { CLEAR, PENDING, REJECTED }
    var status = Status.CLEAR; private set
    private var lastAttempt: Long? = null

    fun observe(locked: Boolean, volume: Int) {
        status = when {
            !locked || volume == 0 -> Status.CLEAR
            status == Status.REJECTED -> Status.REJECTED
            else -> Status.PENDING
        }
    }

    fun rejected() { status = Status.REJECTED }

    fun needed(now: Long, locked: Boolean, unreliable: Boolean, volume: Int, force: Boolean): Boolean {
        if (!locked) return false
        if (force) return true
        val elapsed = lastAttempt?.let { now - it }
        // Settings callbacks may be caused by our own writes. Bound retries even
        // when the vendor keeps returning a stale positive value.
        if (elapsed != null && elapsed < 100) return false
        return volume > 0 || (unreliable && (elapsed == null || elapsed >= 500))
    }

    fun attempted(now: Long) { lastAttempt = now }
}
