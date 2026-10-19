package dev.soundlock

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Maps the accessibility range including its non-zero minimum to media volume.
 * This is necessarily coarse: some OPlus devices expose 160 media steps and 15 a11y steps. */
object VolumeScale {
    fun toMedia(value: Int, minimum: Int, maximum: Int, mediaMaximum: Int): Int {
        require(maximum > minimum && mediaMaximum > 0)
        return (((value.coerceIn(minimum, maximum) - minimum).toDouble() /
            (maximum - minimum)) * mediaMaximum).roundToInt()
    }
    fun toAccessibility(media: Int, mediaMaximum: Int, minimum: Int, maximum: Int): Int {
        require(maximum > minimum && mediaMaximum > 0)
        if (media <= 0) return minimum
        // Round UP so an explicitly authorized low level never maps back to mute.
        return (minimum + ceil(media.coerceAtMost(mediaMaximum).toDouble() /
            mediaMaximum * (maximum - minimum)).toInt()).coerceAtMost(maximum)
    }
}

class MediaVolumeControl(private val context: Context, private val audio: AudioManager) {
    data class Sample(val volume: Int, val maximum: Int, val estimated: Boolean,
        val media: Int, val accessibility: Int, val accessibilityMin: Int, val accessibilityMax: Int)
    private val oplus = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.lowercase() in setOf("oppo", "oneplus", "realme", "oplus")
    }
    private var useAccessibility = false
    var lastCommand: String = "none"; private set
    val unreliableZero get() = useAccessibility
    val mode get() = if (useAccessibility) "无障碍音量兼容" else "标准媒体音量"

    fun refreshMode() {
        val services = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        // Never touch a separately enabled speech/accessibility volume channel.
        val separateSpeechVolume = services.any {
            it.flags and AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME != 0
        }
        useAccessibility = oplus && !separateSpeechVolume &&
            audio.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY) >
            audio.getStreamMinVolume(AudioManager.STREAM_ACCESSIBILITY)
    }
    fun read(): Sample {
        val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val accessibility = audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
        val minimum = audio.getStreamMinVolume(AudioManager.STREAM_ACCESSIBILITY)
        val accessibilityMax = audio.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY)
        val mapped = if (useAccessibility)
            VolumeScale.toMedia(accessibility, minimum, accessibilityMax, maximum) else media
        // A positive direct media read always wins. A mapped floor is only an
        // estimate, never proof of silence; enforcement must still refresh zero.
        return Sample(maxOf(media, mapped), maximum, useAccessibility && media == 0,
            media, accessibility, minimum, accessibilityMax)
    }
    fun mute() {
        // Issue the command even when the background media read spuriously returns zero.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        if (useAccessibility) {
            // Request zero on the aliased stream; do not raise it to a nominal
            // nonzero minimum. The framework may report its floor as 1 after mute.
            audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, 0, 0)
            lastCommand = "mute: media=0, accessibility=0"
        } else lastCommand = "mute: media=0"
    }
    fun unlockLow() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        if (useAccessibility) {
            val sample = read()
            val index = VolumeScale.toAccessibility(1, sample.maximum,
                sample.accessibilityMin, sample.accessibilityMax)
            audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, index, 0)
            lastCommand = "unlock: media=1, accessibility=$index"
        } else lastCommand = "unlock: media=1"
    }
}
