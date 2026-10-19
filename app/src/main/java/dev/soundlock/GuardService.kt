package dev.soundlock

import java.io.FileDescriptor
import java.io.PrintWriter
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.*
import android.os.*
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class GuardService : AccessibilityService() {
    private val policy = LockPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var alarm: AlarmManager
    private lateinit var volumeControl: MediaVolumeControl
    private val muteRefresh = MuteRefresh()
    private var forceMute = true
    private var pendingLockFeedback = false
    private lateinit var capsule: GuardCapsule
    private var problem: String? = null
    private var scheduledDeadline: Long? = null
    private val keyGate = VolumeKeyGate()
    private var cachedAudioMode = AudioManager.MODE_NORMAL
    private val audioModeListener = AudioManager.OnModeChangedListener { mode ->
        cachedAudioMode = mode
        if (ready) inspect()
    }
    private var keyWorkQueued = false
    private var keyConfirmationPending = false
    private var consumedKeyEvents = 0L
    private var lastKeyCallbackUs = 0L
    private var maxKeyCallbackUs = 0L
    private var lastKeyDeliveryDelayMs = 0L
    private var lastKeyDecision = "none"
    private val keyWork = Runnable {
        keyWorkQueued = false
        if (ready) {
            val confirm = keyConfirmationPending
            keyConfirmationPending = false
            inspect()
            if (confirm && policy.shouldConfirm && canInteract() &&
                cachedAudioMode == AudioManager.MODE_NORMAL && !interruptingAudio) capsule.showConfirmation()
        }
    }
    private var ready = false
    private var interruptingAudio = false
    private var volumeKeyEvents = 0L
    private var lockFeedbackCount = 0L
    private var lastUnlockResult = "none"
    private var lastUnlockError: String? = null
    val unlockFailureMessage get() = lastUnlockError ?: "未能解锁，请重试"
    private val alarmIntent by lazy {
        PendingIntent.getBroadcast(this, 1, Intent(this, LockReceiver::class.java).setAction(ACTION_TIMER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { if (ready) inspect() }
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { if (ready) inspect() }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { if (ready) inspect() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { if (ready) inspect() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!ready) return
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    // Do not wait for the routing query to catch up with the disconnect.
                    policy.lock()
                    capsule.dismiss()
                    if (policy.armed) muteMedia()
                    publish()
                    handler.postDelayed({ if (ready) inspect() }, 100)
                }
                Intent.ACTION_SCREEN_OFF -> { capsule.dismiss(); inspect() }
                else -> inspect()
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!ready) return
            inspect()
            // Backup for volume panel changes that don't notify Settings observers.
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            handler.postDelayed(this, if (policy.armed && (interactive || policy.playing)) 500 else 2_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        audio = getSystemService(AudioManager::class.java)
        alarm = getSystemService(AlarmManager::class.java)
        volumeControl = MediaVolumeControl(this, audio).apply { refreshMode() }
        capsule = GuardCapsule(this, ::confirmUnlock, { unlockSuccessMessage }, { unlockFailureMessage })
        policy.setArmed(GuardState.armed(this))
        GuardState.service = this
        ready = true
        cachedAudioMode = audio.mode
        audio.addOnModeChangedListener(mainExecutor, audioModeListener)
        audio.registerAudioPlaybackCallback(playback, handler)
        audio.registerAudioDeviceCallback(devices, handler)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_EXPORTED)
        inspect()
        handler.post(tick)
    }

    private fun route(): LockPolicy.Route {
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val output = audio.getAudioDevicesForAttributes(media)
        if (output.isEmpty()) return LockPolicy.Route.UNKNOWN
        if (output.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE || it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) return LockPolicy.Route.SPEAKER
        // Only explicitly recognized external routes bypass the lock.
        val external = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_DOCK_ANALOG, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
        return if (output.all { it.type in external }) LockPolicy.Route.EXTERNAL else LockPolicy.Route.UNKNOWN
    }

    private fun inspect() {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        try {
            problem = null
            val previousRoute = policy.route
            policy.updateRoute(route())
            if (previousRoute != policy.route) capsule.dismiss()
            val configs = audio.activePlaybackConfigurations
            interruptingAudio = configs.any { it.audioAttributes.usage in setOf(
                AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING) }
            val active = configs.any { it.audioAttributes.usage in setOf(
                AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN) } || audio.isMusicActive
            policy.updatePlayback(active, now)
            volumeControl.refreshMode()
            val volume = volumeControl.read().volume
            policy.observeVolume(volume)
            val expired = policy.tick(now)
            if (muteRefresh.needed(now, policy.shouldMute, volumeControl.unreliableZero, volume,
                    expired || forceMute)) muteMedia()
            if (!policy.shouldMute) forceMute = false
            reconcileCapsule()
            scheduleTimer()
            publish()
        } catch (e: RuntimeException) {
            policy.updateRoute(LockPolicy.Route.UNKNOWN)
            policy.lock()
            if (policy.armed) muteMedia()
            problem = "音频状态读取失败，请重新开启保护"
            reconcileCapsule()
            publish()
        }
    }

    private fun muteMedia() {
        // Record before the write: Settings observers can be triggered by it.
        muteRefresh.attempted(SystemClock.elapsedRealtime())
        forceMute = false
        try {
            volumeControl.mute()
            forceMute = false
            problem = if (volumeControl.read().volume != 0) "正在将媒体音量归零；尚未确认静音" else null
            // The existing bounded polling loop verifies delayed vendor updates.
            // Do not spawn another retry loop for every failed mute attempt.
        } catch (e: RuntimeException) {
            forceMute = false
            problem = "系统拒绝调整媒体音量，保护未完全生效"
        }
    }

    fun setProtection(enabled: Boolean) {
        policy.setArmed(enabled)
        forceMute = enabled
        capsule.dismiss()
        inspect()
    }
    fun lockNow() {
        if (!ready) return
        val wasUnlocked = policy.unlocked
        forceMute = true
        policy.lock()
        capsule.dismiss()
        inspect()
        // publish handles actual transitions; an explicit re-lock also gets feedback.
        if (!wasUnlocked && policy.armed) { pendingLockFeedback = true; publish() }
    }
    private fun showLockFeedback() {
        if (problem == null && canInteract() && audio.mode == AudioManager.MODE_NORMAL && !interruptingAudio) {
            lockFeedbackCount++
            capsule.showMessage("外放已锁定", false)
        }
    }
    fun checkTimer() { if (ready) inspect() }
    fun refreshSettings() {
        if (!ready) return
        scheduledDeadline = null
        inspect()
        updateNotification(GuardState.current)
    }

    private fun reconcileCapsule() {
        val interactive = canInteract() && audio.mode == AudioManager.MODE_NORMAL && !interruptingAudio
        capsule.updateAvailability(policy.shouldConfirm && policy.route == LockPolicy.Route.SPEAKER, interactive)
    }

    fun requestUnlock() {
        inspect()
        if (policy.shouldConfirm && canInteract()) capsule.showConfirmation()
    }
    fun confirmUnlock(): Boolean {
        inspect()
        lastUnlockError = when {
            !canInteract() -> "请先解锁手机屏幕，再按音量加键重试"
            !policy.armed -> "保护已暂停，音量键可直接调节"
            policy.route != LockPolicy.Route.SPEAKER -> "输出设备已变化，请按音量加键重试"
            else -> null
        }
        if (lastUnlockError != null || !policy.unlock(SystemClock.elapsedRealtime())) {
            lastUnlockResult = "rejected: $lastUnlockError"
            return false
        }
        try {
            // Confirmation authorizes just one low media-volume step, never an old loud level.
            volumeControl.unlockLow()
            // AudioService/vendor processing can be asynchronous. A still-zero
            // readback is not a rejection of the user's explicit authorization.
            val reportedVolume = volumeControl.read().volume
            policy.observeVolume(reportedVolume)
            lastUnlockResult = "authorized, immediateVolume=$reportedVolume"
            scheduleTimer()
            publish()
            return true
        } catch (e: RuntimeException) {
            policy.lock()
            muteMedia()
            lastUnlockError = "系统拒绝调整媒体音量，保持锁定；可在此重试"
            lastUnlockResult = "exception: " + e.javaClass.simpleName + ": " + e.message
            problem = lastUnlockError
            publish()
            return false
        }
    }
    val unlockSuccessMessage get() = if (volumeControl.read().volume > 0) "外放已解锁" else "已解锁，可调高音量"
    fun showUnlockFeedback() { if (canInteract()) capsule.showMessage(unlockSuccessMessage, true) }
    private fun canInteract() = ready && getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!ready || event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        val started = SystemClock.elapsedRealtimeNanos()
        volumeKeyEvents++
        lastKeyDeliveryDelayMs = (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)
        // A due lease must expire before this key is classified, even if the
        // timer/queued audio inspection has not run since the screen woke up.
        if (policy.tick(SystemClock.elapsedRealtime())) forceMute = true
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> VolumeKeyGate.Action.DOWN
            KeyEvent.ACTION_UP -> VolumeKeyGate.Action.UP
            else -> VolumeKeyGate.Action.OTHER
        }
        val decision = keyGate.handle(action, event.repeatCount, policy.shouldConfirm,
            cachedAudioMode != AudioManager.MODE_NORMAL || interruptingAudio)
        lastKeyDecision = decision.reason
        if (decision.consume) {
            consumedKeyEvents++
            if (decision.mute) forceMute = true
            keyConfirmationPending = keyConfirmationPending || decision.confirm
        }
        // onKeyEvent has a system deadline. Return the consume decision first;
        // route queries, volume writes and window rendering run in a later task.
        if (!keyWorkQueued) {
            keyWorkQueued = true
            handler.post(keyWork)
        }
        lastKeyCallbackUs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000
        maxKeyCallbackUs = maxOf(maxKeyCallbackUs, lastKeyCallbackUs)
        return decision.consume
    }

    private fun scheduleTimer() {
        val deadline = policy.deadline
        if (deadline == scheduledDeadline) return
        alarm.cancel(alarmIntent)
        scheduledDeadline = deadline
        if (deadline != null) {
            try {
                if (alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarmIntent)
                else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarmIntent)
            } catch (e: SecurityException) {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarmIntent)
            }
        }
    }
    private fun publish() {
        val volumeSample = runCatching { volumeControl.read() }.getOrNull()
        val next = GuardSnapshot(true, policy.armed, policy.unlocked, policy.route, policy.playing, policy.deadline,
            volumeSample?.volume ?: 0,
            volumeSample?.maximum ?: 15, problem, volumeControl.mode, volumeSample?.estimated ?: false)
        val old = GuardState.current
        GuardState.current = next
        // Edge-triggered: routine polling must never renew this transient message.
        if (old.connected && next.armed && !next.unlocked && (old.unlocked || !old.armed)) pendingLockFeedback = true
        if (!next.armed || next.unlocked) pendingLockFeedback = false
        if (pendingLockFeedback && next.problem == null && (!policy.shouldMute || volumeSample?.volume == 0)) {
            pendingLockFeedback = false
            showLockFeedback()
        }
        if (old.copy(volume = next.volume, playing = next.playing, deadline = next.deadline) != next) {
            updateNotification(next)
            TileService.requestListeningState(this, ComponentName(this, LockTileService::class.java))
        }
    }
    private fun updateNotification(state: GuardSnapshot) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!state.armed) { nm.cancel(2); return }
        nm.createNotificationChannel(NotificationChannel("protection", "保护状态", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lock = PendingIntent.getBroadcast(this, 2, Intent(this, LockReceiver::class.java).setAction(ACTION_LOCK), PendingIntent.FLAG_IMMUTABLE)
        val confirm = PendingIntent.getBroadcast(this, 4, Intent(this, LockReceiver::class.java).setAction(ACTION_CONFIRM), PendingIntent.FLAG_IMMUTABLE)
        val title = when { state.problem != null -> "保护需要检查"; state.route == LockPolicy.Route.EXTERNAL -> "外接设备使用中"; state.unlocked -> "外放已解锁"; else -> "外放已锁定" }
        val notification = Notification.Builder(this, "protection").setSmallIcon(R.drawable.ic_lock).setContentTitle(title)
            .setContentText(if (state.unlocked) "停止播放 15 分钟后自动上锁" else "点击后确认解锁").setContentIntent(if (state.route == LockPolicy.Route.SPEAKER && !state.unlocked) confirm else open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (state.route == LockPolicy.Route.SPEAKER && !state.unlocked) notification.addAction(Notification.Action.Builder(null, "确认解锁", confirm).build())
        notification.addAction(Notification.Action.Builder(null, "立即上锁", lock).build())
        nm.notify(2, notification.build())
    }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("SoundLock version=${BuildConfig.VERSION_NAME} ready=$ready armed=${policy.armed} unlocked=${policy.unlocked}")
        writer.println("route=${policy.route} playing=${policy.playing} volumeKeys=$volumeKeyEvents problem=$problem")
        writer.println("keyConsumed=$consumedKeyEvents keyDecision=$lastKeyDecision callbackUs=$lastKeyCallbackUs maxCallbackUs=$maxKeyCallbackUs deliveryDelayMs=$lastKeyDeliveryDelayMs")
        writer.println("lastUnlock=$lastUnlockResult volume=${GuardState.current.volume} lockFeedbacks=$lockFeedbackCount")
        if (ready) writer.println("volumeMode=${volumeControl.mode} sample=${runCatching { volumeControl.read() }.getOrNull()} command=${volumeControl.lastCommand}")
        if (ready) writer.println("capsule=${capsule.status} canInteract=${canInteract()} audioMode=${audio.mode} interruptingAudio=$interruptingAudio")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { lockNow() }
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() {
        if (!ready) return
        policy.lock()
        if (policy.armed && policy.route != LockPolicy.Route.EXTERNAL) muteMedia()
        ready = false
        handler.removeCallbacksAndMessages(null)
        alarm.cancel(alarmIntent)
        capsule.dismiss()
        audio.removeOnModeChangedListener(audioModeListener)
        audio.unregisterAudioPlaybackCallback(playback)
        audio.unregisterAudioDeviceCallback(devices)
        contentResolver.unregisterContentObserver(observer)
        unregisterReceiver(receiver)
        getSystemService(NotificationManager::class.java).cancel(2)
        GuardState.service = null
        GuardState.current = GuardSnapshot(armed = GuardState.armed(this))
    }
    companion object {
        const val ACTION_LOCK = "dev.soundlock.LOCK"
        const val ACTION_CONFIRM = "dev.soundlock.CONFIRM"
        const val ACTION_TIMER = "dev.soundlock.TIMER"
    }
}
