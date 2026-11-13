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
    val volumeMode: VolumeMode = VolumeMode.STANDARD,
    val volumeEstimated: Boolean = false,
    val idleMinutes: Int = 15,
    val allowed: Boolean = false
)
object GuardState {
    var current by mutableStateOf(GuardSnapshot()); internal set
    var service: GuardService? = null; internal set
    fun prefs(context: Context) = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    fun armed(context: Context) = prefs(context).getBoolean("armed", true)
    fun setIdleMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt("idleMinutes", minutes).apply()
        service?.setIdleTimeout(minutes)
        if (service == null) current = current.copy(idleMinutes = minutes)
    }
    fun idleMinutes(context: Context) = prefs(context).getInt("idleMinutes", 15)
    fun allowedPackages(context: Context) =
        prefs(context).getStringSet("allowedPackages", emptySet())?.toSet() ?: emptySet()
    fun setAllowedPackage(context: Context, packageName: String, allowed: Boolean) {
        val next = allowedPackages(context).toMutableSet().apply {
            if (allowed) add(packageName) else remove(packageName)
        }
        prefs(context).edit().putStringSet("allowedPackages", next).apply()
        service?.refreshAllowedPackages()
    }
    fun setArmed(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("armed", enabled).apply()
        service?.setProtection(enabled)
        if (service == null) current = current.copy(armed = enabled)
    }
}
