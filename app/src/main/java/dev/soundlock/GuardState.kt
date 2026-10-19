package dev.soundlock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class GuardSnapshot(
    val connected: Boolean = false,
    val armed: Boolean = true,
    val unlocked: Boolean = false,
    val route: LockPolicy.Route = LockPolicy.Route.UNKNOWN,
    val playing: Boolean = false,
    val deadline: Long? = null,
    val volume: Int = 0,
    val maxVolume: Int = 15,
    val problem: String? = null,
    val volumeMode: String = "标准媒体音量",
    val volumeEstimated: Boolean = false
)
object GuardState {
    var current by mutableStateOf(GuardSnapshot()); internal set
    var service: GuardService? = null; internal set
    fun prefs(context: Context) = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    fun armed(context: Context) = prefs(context).getBoolean("armed", true)
    fun setArmed(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("armed", enabled).apply()
        service?.setProtection(enabled)
        if (service == null) current = current.copy(armed = enabled)
    }
}
