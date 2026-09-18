package com.flowai.communication.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import com.flowai.communication.domain.CaptureRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay that lets the user frame the conversation before capture.
 *
 * Region-first is a product requirement, not a convenience: reading only the conversation area
 * means less unrelated screen content is ever recognised, which is the Just-in-Time Context rule.
 * It also improves OCR accuracy by excluding status bars and app chrome.
 *
 * Returns the chosen region through [EXTRA_REGION] as an [CaptureRegion], or RESULT_CANCELED.
 */
class RegionPickerActivity : Activity() {

    private lateinit var pickerView: RegionPickerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickerView = RegionPickerView(this)

        val hint = TextView(this).apply {
            text = getString(com.flowai.communication.R.string.region_hint)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val cancel = Button(this).apply {
            text = getString(com.flowai.communication.R.string.region_cancel)
            setOnClickListener { finishWith(null) }
        }
        val full = Button(this).apply {
            text = getString(com.flowai.communication.R.string.region_full_screen)
            setOnClickListener { finishWith(null) } // null == whole screen downstream
        }
        val confirm = Button(this).apply {
            text = getString(com.flowai.communication.R.string.region_confirm)
            setOnClickListener { finishWith(pickerView.selectedRegion()) }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#CC000000"))
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(full, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(confirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = FrameLayout(this).apply {
            addView(pickerView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(hint, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP })
            addView(actions, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })
        }
        setContentView(root)
        // Purely a selection surface; starting a capture from here must not leak into the region.
        window.setDimAmount(0f)
    }

    private fun finishWith(region: CaptureRegion?) {
        setResult(
            if (region == null) RESULT_CANCELED else RESULT_OK,
            Intent().putExtra(EXTRA_REGION, region)
        )
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REGION = "flowai.region"

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(context: Context): Intent =
            Intent(context, RegionPickerActivity::class.java)
    }
}

/**
 * Drag-to-select surface.
 *
 * Draws a dark scrim everywhere except the selection so the user can see what will be read.
 * A tap without a drag selects nothing, and the caller falls back to full-screen capture.
 */
private class RegionPickerView(context: Context) : View(context) {

    private val scrim = Paint().apply { color = Color.parseColor("#99000000") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#315CD5")
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var dragging = false

    /** Selection in this view's coordinates, clamped to the view bounds. */
    private fun selection(): Rect? {
        if (!dragging) return null
        val left = min(startX, endX).toInt().coerceIn(0, width)
        val right = max(startX, endX).toInt().coerceIn(0, width)
        val top = min(startY, endY).toInt().coerceIn(0, height)
        val bottom = max(startY, endY).toInt().coerceIn(0, height)
        val rect = Rect(left, top, right, bottom)
        return rect.takeIf { it.width() > 0 && it.height() > 0 }
    }

    /** The selection translated into absolute screen pixels for the capture service. */
    fun selectedRegion(): CaptureRegion? {
        val rect = selection() ?: return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val region = CaptureRegion(
            left = loc[0] + rect.left,
            top = loc[1] + rect.top,
            right = loc[0] + rect.right,
            bottom = loc[1] + rect.bottom
        )
        return region.takeIf { it.isValid() }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX = startX; endY = startY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                if (!dragging && (abs(endX - startX) > SLOP || abs(endY - startY) > SLOP)) dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x; endY = event.y
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = selection()
        if (rect == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            return
        }
        // Darken everything except the selection, using four rects so no layer is needed.
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top.toFloat(), scrim)
        canvas.drawRect(0f, rect.bottom.toFloat(), width.toFloat(), height.toFloat(), scrim)
        canvas.drawRect(0f, rect.top.toFloat(), rect.left.toFloat(), rect.bottom.toFloat(), scrim)
        canvas.drawRect(rect.right.toFloat(), rect.top.toFloat(), width.toFloat(), rect.bottom.toFloat(), scrim)
        canvas.drawRect(rect, border)
    }

    private companion object {
        const val SLOP = 12f
    }
}
