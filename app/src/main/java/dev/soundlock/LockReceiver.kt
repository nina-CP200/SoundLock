package dev.soundlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            GuardService.ACTION_CONFIRM -> GuardState.service?.requestUnlock()
            GuardService.ACTION_LOCK -> GuardState.service?.lockNow()
            GuardService.ACTION_TIMER -> GuardState.service?.checkTimer()
        }
    }
}
