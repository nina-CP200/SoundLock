package dev.soundlock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.widget.*

/** Accessibility overlay: does not need SYSTEM_ALERT_WINDOW or access to window contents. */
class GuardCapsule(
    private val context: Context,
    private val unlock: () -> Boolean,
    private val successMessage: () -> String,
    private val failureMessage: () -> String
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    private var animation: ValueAnimator? = null
    private enum class Mode { HIDDEN, CONFIRMATION, MESSAGE }
    private var mode = Mode.HIDDEN
    var lastError: String? = null; private set
    val status get() = "${mode.name}, attached=${view?.isAttachedToWindow == true}, error=$lastError"
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val hide = Runnable { dismiss() }

    // Polling may dismiss an invalid prompt, but never opens an overlay.
    fun updateAvailability(offerUnlock: Boolean, interactive: Boolean) {
        if (!interactive || (!offerUnlock && mode == Mode.CONFIRMATION)) dismiss()
    }

    fun showConfirmation() {
        dismiss()
        val root = base()
        val title = TextView(context).apply { text = "解锁外放？"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(8), dp(6), dp(8), dp(2)) }
        root.addView(title)
        val explanation = TextView(context).apply {
            text = "恢复最低一格音量"; textSize = 14f; setTextColor(Color.rgb(198, 211, 212)); setPadding(dp(8), 0, dp(8), dp(8))
        }
        root.addView(explanation)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(button("保持静音", false) { dismiss() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
        actions.addView(button("确认解锁", true) {
            if (unlock()) showMessage(successMessage(), true)
            else {
                explanation.text = failureMessage()
                handler.removeCallbacks(hide)
                handler.postDelayed(hide, 10_000)
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions)
        attach(root, Mode.CONFIRMATION)
        handler.postDelayed(hide, 10_000)
    }
    fun showMessage(message: String, open: Boolean) {
        dismiss()
        val root = base().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = LockDrawing(context).apply { contentDescription = null; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(icon, LinearLayout.LayoutParams(dp(32), dp(40)))
        root.addView(TextView(context).apply {
            text = message; textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(12), dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        attach(root, Mode.MESSAGE)
        if (open && ValueAnimator.areAnimatorsEnabled()) {
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener { icon.progress = it.animatedValue as Float; icon.invalidate() }
                start()
            }
        } else icon.progress = if (open) 1f else 0f
        handler.postDelayed(hide, 1_200)
    }
    private fun base() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 34, 36)); cornerRadius = dp(16).toFloat() }
        elevation = dp(4).toFloat()
        isSoundEffectsEnabled = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 15f; isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        setTextColor(if (primary) Color.rgb(17, 42, 44) else Color.WHITE)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
            GradientDrawable().apply { setColor(if (primary) Color.rgb(128, 212, 217) else Color.rgb(52, 67, 69)); cornerRadius = dp(26).toFloat() }, null)
        isSoundEffectsEnabled = false
        setOnClickListener { action() }
    }
    private fun attach(root: LinearLayout, nextMode: Mode, widthDp: Int = 380) {
        val width = minOf(dp(widthDp), context.resources.displayMetrics.widthPixels - dp(32))
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(16)
            title = "声锁确认"
        }
        try {
            wm.addView(root, params)
            view = root
            mode = nextMode
            lastError = null
            if (ValueAnimator.areAnimatorsEnabled()) {
                root.alpha = 0f; root.translationY = -dp(8).toFloat()
                root.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
        } catch (e: RuntimeException) {
            // A failed overlay never authorizes audio. Keep a diagnostic instead of failing silently.
            lastError = e.javaClass.simpleName + ": " + e.message
            Log.w("SoundLock", "Unable to attach confirmation capsule", e)
            view = null
            mode = Mode.HIDDEN
        }
    }
    fun dismiss() {
        handler.removeCallbacks(hide)
        animation?.cancel(); animation = null
        view?.let { it.animate().cancel(); runCatching { wm.removeViewImmediate(it) } }
        view = null
        mode = Mode.HIDDEN
    }
    private class LockDrawing(context: Context) : View(context) {
        var progress = 0f
        private val shackle = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(128, 212, 217); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = width / 32f
            canvas.save(); canvas.translate(0f, (height - width) / 2f); canvas.scale(unit, unit)
            paint.strokeWidth = 2.4f
            canvas.drawRoundRect(6f, 14f, 26f, 29f, 3f, 3f, paint)
            canvas.save(); canvas.rotate(-36f * progress, 10f, 14f)
            shackle.reset()
            shackle.apply { moveTo(10f, 14f); lineTo(10f, 9f); cubicTo(10f, 1f, 22f, 1f, 22f, 9f); lineTo(22f, 14f - progress * 3f) }
            canvas.drawPath(shackle, paint); canvas.restore()
            canvas.drawLine(16f, 20f, 16f, 23f, paint)
            canvas.restore()
        }
    }
}
