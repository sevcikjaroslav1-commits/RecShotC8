package sk.vespal.recshot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Malé modré plávajúce tlačidlo nad všetkými aplikáciami (režim snímok).
 * Ťuk = snímka, ťahanie = presun (prilepí sa k okraju), potiahnutie na ✕ dole = ukončenie.
 */
class FloatingShotButton(
    private val context: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val onShot: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val blue = Color.parseColor("#2F6FE0")
    private val size = dp(56)
    private val box = dp(64) // priestor aj pre odznak s počtom
    private val margin = dp(10)

    private var root: FrameLayout? = null
    private var badge: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var closeView: FrameLayout? = null
    private var closeParams: WindowManager.LayoutParams? = null

    private var count = 0

    val isShown: Boolean get() = root != null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        val manager = wm ?: return false
        if (!canShow()) return false

        val frame = FrameLayout(context)

        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(blue)
                setStroke(dp(3), Color.argb(230, 255, 255, 255))
            }
            elevation = dp(6).toFloat()
            contentDescription = tr("Odfotiť obrazovku", "Take screenshot")
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_shot)
            setColorFilter(Color.WHITE)
        }
        circle.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        frame.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))

        val b = TextView(context).apply {
            setTextColor(blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(20)
            setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        frame.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP or Gravity.END))

        val p = overlayParams(box, box).apply {
            x = screenW - box - margin
            y = (screenH * 0.4f).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        p.x = (startX + dx).toInt().coerceIn(0, screenW - box)
                        p.y = (startY + dy).toInt().coerceIn(0, screenH - box)
                        try { manager.updateViewLayout(frame, p) } catch (_: Exception) {}
                        highlightClose(isNearClose(p))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val close = isNearClose(p)
                        hideCloseTarget()
                        if (close) {
                            onClose()
                        } else {
                            // prilepiť k bližšiemu okraju
                            p.x = if (p.x + box / 2 < screenW / 2) margin else screenW - box - margin
                            try { manager.updateViewLayout(frame, p) } catch (_: Exception) {}
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        onShot()
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            manager.addView(frame, p)
            root = frame
            badge = b
            params = p
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Skryje tlačidlo (aby nebolo na snímke). */
    fun hideForCapture() {
        root?.visibility = View.INVISIBLE
    }

    /** Znova ukáže tlačidlo; pri úspešnej snímke zvýši počítadlo. */
    fun restore(success: Boolean) {
        root?.visibility = View.VISIBLE
        if (success) {
            count++
            badge?.apply {
                text = count.toString()
                visibility = View.VISIBLE
            }
        }
    }

    fun remove() {
        hideCloseTarget()
        val manager = wm
        root?.let { v -> try { manager?.removeView(v) } catch (_: Exception) {} }
        root = null
        badge = null
        params = null
    }

    // ------------------------------------------------------------ cieľ ✕ na ukončenie

    private fun showCloseTarget() {
        if (closeView != null) return
        val manager = wm ?: return
        val s = dp(64)
        val v = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 14, 15, 19))
                setStroke(dp(2), Color.argb(160, 255, 255, 255))
            }
        }
        val x = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        v.addView(x, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val p = overlayParams(s, s).apply {
            this.x = screenW / 2 - s / 2
            y = screenH - s - dp(72)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            manager.addView(v, p)
            closeView = v
            closeParams = p
        } catch (_: Exception) {
        }
    }

    private fun highlightClose(on: Boolean) {
        val v = closeView ?: return
        val scale = if (on) 1.25f else 1f
        v.scaleX = scale
        v.scaleY = scale
    }

    private fun hideCloseTarget() {
        val v = closeView ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        closeView = null
        closeParams = null
    }

    private fun isNearClose(p: WindowManager.LayoutParams): Boolean {
        val cp = closeParams ?: return false
        val s = dp(64)
        val cx = cp.x + s / 2f
        val cy = cp.y + s / 2f
        val bx = p.x + box / 2f
        val by = p.y + box / 2f
        return hypot(cx - bx, cy - by) < dp(80)
    }
}
