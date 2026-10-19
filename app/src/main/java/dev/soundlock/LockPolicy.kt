package dev.soundlock

/** Pure policy; time is always elapsedRealtime, never calendar time. */
class LockPolicy(private val idleTimeoutMs: Long = 15 * 60_000L) {
    enum class Route { SPEAKER, EXTERNAL, UNKNOWN }
    var armed = true; private set
    var unlocked = false; private set
    var route = Route.UNKNOWN; private set
    var playing = false; private set
    var idleSince: Long? = null; private set
    private var positiveVolumeObserved = false

    val shouldMute get() = armed && !unlocked && route != Route.EXTERNAL
    val shouldConfirm get() = armed && !unlocked && route != Route.EXTERNAL
    val deadline get() = if (armed && unlocked && !playing) idleSince?.plus(idleTimeoutMs) else null

    fun setArmed(value: Boolean) { armed = value; lock() }
    fun lock() { unlocked = false; idleSince = null; positiveVolumeObserved = false }
    fun unlock(now: Long): Boolean {
        if (!armed || route != Route.SPEAKER) return false
        unlocked = true
        positiveVolumeObserved = false
        idleSince = if (playing) null else now
        return true
    }
    fun updateRoute(next: Route) {
        // A speaker lease cannot survive an output change, even through UNKNOWN.
        if (route != next) lock()
        route = next
    }
    fun updatePlayback(active: Boolean, now: Long) {
        if (active) idleSince = null
        else if (unlocked && (playing || idleSince == null)) idleSince = now
        playing = active
    }
    fun tick(now: Long): Boolean {
        val due = deadline ?: return false
        if (now < due) return false
        lock()
        return true
    }
    /** Only a return from positive volume revokes a lease. Zero may still be the
     * old value while AudioService applies a just-confirmed volume adjustment. */
    fun observeVolume(volume: Int) {
        if (!armed || !unlocked || route != Route.SPEAKER) return
        if (volume > 0) positiveVolumeObserved = true
        else if (positiveVolumeObserved) lock()
    }
}
