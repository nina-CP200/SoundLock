package dev.soundlock

/** In-memory decision only. Never query audio, render UI or perform Binder calls here. */
class VolumeKeyGate {
    enum class Action { DOWN, UP, OTHER }
    data class Decision(val consume: Boolean, val confirm: Boolean = false,
        val mute: Boolean = false, val reason: String)
    private var held = false

    fun handle(action: Action, repeat: Int, locked: Boolean, exempt: Boolean): Decision {
        if (action == Action.UP && held) {
            held = false
            return Decision(true, mute = locked, reason = "consumed-release")
        }
        if (held) return Decision(true, mute = locked, reason = "consumed-repeat")
        if (exempt) return Decision(false, reason = "call-or-alarm")
        if (!locked) return Decision(false, reason = "authorized-or-external")
        if (action != Action.DOWN) return Decision(false, reason = "unpaired-event")
        held = true
        return Decision(true, confirm = repeat == 0, mute = true, reason = "locked-down")
    }
}
