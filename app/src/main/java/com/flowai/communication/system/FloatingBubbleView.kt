package com.flowai.communication.system

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The floating bubble itself.
 *
 * Drawn rather than laid out so the window can stay `WRAP_CONTENT` around a small circle. That
 * matters on Android 12+: an overlay window must be transparent outside its interactive area or
 * the system rejects touches passing through it ("untrusted touch events"). Keeping the window
 * tight around the opaque circle avoids that entirely.
 */
class FloatingBubbleView(
    context: Context,
    private val onTap: () -> Unit = {},
    private val onDragEnd: () -> Unit = {}
) : View(context) {

    /** Constructors the tooling expects; the service uses the callback form above. */
    @Suppress("unused")
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(context)

    @Suppress("unused")
    constructor(context: Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) : this(context)

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUBBLE_COLOR }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    init {
        val size = dp(SIZE_DP)
        glyph.textSize = size * 0.42f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(SIZE_DP).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val r = (minOf(width, height) / 2f) - ring.strokeWidth
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, circle)
        canvas.drawCircle(cx, cy, r, ring)
        val baseline = cy - (glyph.descent() + glyph.ascent()) / 2f
        canvas.drawText("F", cx, baseline, glyph)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                alpha = PRESSED_ALPHA
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && abs(dx) + abs(dy) > dp(DRAG_SLOP_DP)) dragging = true
                if (dragging) {
                    // Reported to the owner, which moves the window.
                    onDrag(dx, dy)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                alpha = 1f
                if (dragging) onDragEnd() else if (event.actionMasked == MotionEvent.ACTION_UP) onTap()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Set by the window owner to translate the overlay window. */
    var onDrag: (Float, Float) -> Unit = { _, _ -> }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val SIZE_DP = 52f
        private const val DRAG_SLOP_DP = 8f
        private const val PRESSED_ALPHA = 0.6f
        private val BUBBLE_COLOR = Color.parseColor("#315CD5")
    }
}
