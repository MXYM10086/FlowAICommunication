package com.flowai.communication.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.flowai.communication.domain.CaptureRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

        // Window attributes are set explicitly rather than inherited from the theme.
        //
        // `Theme.Translucent.NoTitleBar` carries `windowIsFloating` on several ROMs, which made the
        // picker a small non-focusable window: the activity reported as displayed and even received
        // ACTION_DOWN, yet nothing was drawn and the screen looked frozen. Setting the layout and
        // focus explicitly removes that dependency on theme behaviour.
        window.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            decorView.systemUiVisibility =
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        pickerView = RegionPickerView(this)

        val hint = TextView(this).apply {
            text = getString(com.flowai.communication.R.string.region_hint)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        // The window is laid out edge to edge, so push the hint below the status bar using the
        // system's own insets rather than reflecting on a private dimension resource.
        ViewCompat.setOnApplyWindowInsetsListener(hint) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16), dp(10) + bars.top, dp(16), dp(10))
            insets
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
 * Drag-to-select surface with drag handles for fine-tuning.
 *
 * Two interaction modes, decided by whether a selection already exists:
 *  - **CREATE** (no selection): dragging anywhere rubber-bands a new selection.
 *  - **ADJUST** (selection exists): dragging a handle resizes that edge or corner, dragging the
 *    interior moves the whole selection, and dragging outside it starts a fresh CREATE.
 *
 * Eight handles (four corners + four edge midpoints) appear once a selection exists; the handle
 * being dragged is highlighted. Draws a dark scrim everywhere except the selection so the user can
 * see what will be read. A tap without a drag selects nothing, and the caller falls back to
 * full-screen capture. The selection never shrinks below [MIN_SIZE_DP] and never leaves the view.
 *
 * Everything is Canvas self-drawing inside this one View — no extra view classes, no dependencies.
 */
private class RegionPickerView(context: Context) : View(context) {

    /** What the in-flight gesture does; [CREATE] and [MOVE] plus the eight handle positions. */
    private enum class Drag { NONE, CREATE, MOVE, TL, T, TR, L, R, BL, B, BR }

    private val density = resources.displayMetrics.density
    private val minSize = MIN_SIZE_DP * density
    private val touchHalf = HANDLE_TOUCH_DP * density / 2f
    private val dotRadius = 5f * density
    private val backRadius = 10f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val scrim = Paint().apply { color = Color.parseColor("#99000000") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#315CD5")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val handleBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000")
    }
    private val handleActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC315CD5")
    }
    private val handleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Selection in this view's coordinates, clamped to the view bounds; null until first drag. */
    private var selection: RectF? = null
    private var drag: Drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** False until a CREATE gesture passes the touch slop, so a plain tap changes nothing. */
    private var creating = false
    /** Selection snapshot taken on ACTION_DOWN; the fixed anchor edges for resize come from it. */
    private val origin = RectF()

    /** The selection translated into absolute screen pixels for the capture service. */
    fun selectedRegion(): CaptureRegion? {
        val rect = selection ?: return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val region = CaptureRegion(
            left = loc[0] + rect.left.roundToInt(),
            top = loc[1] + rect.top.roundToInt(),
            right = loc[0] + rect.right.roundToInt(),
            bottom = loc[1] + rect.bottom.roundToInt()
        )
        return region.takeIf { it.isValid() }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = downX; lastY = downY
                val sel = selection
                drag = when {
                    sel == null -> Drag.CREATE
                    else -> hitHandle(event.x, event.y)
                        ?: if (sel.contains(event.x, event.y)) Drag.MOVE else Drag.CREATE
                }
                if (drag == Drag.CREATE) creating = false
                origin.set(sel ?: RectF())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x; lastY = event.y
                when (drag) {
                    Drag.CREATE -> {
                        if (!creating &&
                            (abs(lastX - downX) > slop || abs(lastY - downY) > slop)
                        ) {
                            creating = true
                            selection = RectF()
                        }
                        if (creating) updateCreate()
                    }
                    Drag.MOVE -> updateMove()
                    Drag.NONE -> Unit
                    else -> updateResize()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                lastX = event.x; lastY = event.y
                if (drag == Drag.CREATE) {
                    // Past the slop the gesture is a real selection: finish it, then make sure it
                    // respects the minimum size even if the finger barely moved.
                    if (creating) {
                        updateCreate()
                        settleCreated()
                    }
                    creating = false
                }
                drag = Drag.NONE
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                creating = false
                drag = Drag.NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Rubber-band from the finger-down point to the current point, clamped to the view. */
    private fun updateCreate() {
        val s = selection ?: return
        s.set(
            min(downX, lastX).coerceIn(0f, width.toFloat()),
            min(downY, lastY).coerceIn(0f, height.toFloat()),
            max(downX, lastX).coerceIn(0f, width.toFloat()),
            max(downY, lastY).coerceIn(0f, height.toFloat())
        )
    }

    /**
     * Grows a too-small fresh selection out to [minSize] towards the finger, then slides it back
     * inside the view when the growth overshot an edge.
     */
    private fun settleCreated() {
        val s = selection ?: return
        val growRight = lastX >= downX
        val growDown = lastY >= downY
        if (s.width() < minSize) {
            if (growRight) s.right = s.left + minSize else s.left = s.right - minSize
        }
        if (s.height() < minSize) {
            if (growDown) s.bottom = s.top + minSize else s.top = s.bottom - minSize
        }
        clampToView(s)
    }

    /** Translates the whole selection by the finger delta, stopping at the view edges. */
    private fun updateMove() {
        val s = selection ?: return
        val maxLeft = max(0f, width - origin.width())
        val maxTop = max(0f, height - origin.height())
        val left = (origin.left + lastX - downX).coerceIn(0f, maxLeft)
        val top = (origin.top + lastY - downY).coerceIn(0f, maxTop)
        s.set(left, top, left + origin.width(), top + origin.height())
    }

    /**
     * Resizes from the drag-start snapshot: a corner handle moves two edges, an edge handle one.
     * The opposite edge stays put as the anchor, so the box can never invert while dragging.
     */
    private fun updateResize() {
        val s = selection ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        when (drag) {
            Drag.TL, Drag.BL, Drag.L -> s.left = dragEdge(lastX, origin.right, viewWidth)
            Drag.TR, Drag.BR, Drag.R -> s.right = dragEdge(lastX, origin.left, viewWidth)
            else -> Unit
        }
        when (drag) {
            Drag.TL, Drag.TR, Drag.T -> s.top = dragEdge(lastY, origin.bottom, viewHeight)
            Drag.BL, Drag.BR, Drag.B -> s.bottom = dragEdge(lastY, origin.top, viewHeight)
            else -> Unit
        }
    }

    /**
     * One edge following the finger along one axis: never past [anchor] (no inversion), never
     * closer to it than [minSize], never beyond [bound] (the view size on that axis).
     */
    private fun dragEdge(moving: Float, anchor: Float, bound: Float): Float =
        if (moving < anchor) {
            moving.coerceIn(0f, max(0f, anchor - minSize))
        } else {
            moving.coerceIn(min(anchor + minSize, bound), bound)
        }

    /** Keeps a fully-formed selection inside the view, preserving its size where possible. */
    private fun clampToView(s: RectF) {
        if (s.left < 0f) s.offset(-s.left, 0f)
        if (s.top < 0f) s.offset(0f, -s.top)
        if (s.right > width) s.offset(width - s.right, 0f)
        if (s.bottom > height) s.offset(0f, height - s.bottom)
        s.left = s.left.coerceIn(0f, width.toFloat())
        s.top = s.top.coerceIn(0f, height.toFloat())
        s.right = s.right.coerceIn(0f, width.toFloat())
        s.bottom = s.bottom.coerceIn(0f, height.toFloat())
    }

    /**
     * Nearest handle whose 48dp touch box contains the point, or null. Corners are listed first so
     * a tie in a cramped selection goes to the corner rather than the edge midpoint.
     */
    private fun hitHandle(x: Float, y: Float): Drag? {
        val s = selection ?: return null
        var best: Drag? = null
        var bestDist = Float.MAX_VALUE
        for ((which, hx, hy) in handlePoints(s)) {
            if (abs(x - hx) <= touchHalf && abs(y - hy) <= touchHalf) {
                val dist = (x - hx) * (x - hx) + (y - hy) * (y - hy)
                if (dist < bestDist) {
                    bestDist = dist
                    best = which
                }
            }
        }
        return best
    }

    private fun handlePoints(s: RectF): List<Triple<Drag, Float, Float>> = listOf(
        Triple(Drag.TL, s.left, s.top),
        Triple(Drag.TR, s.right, s.top),
        Triple(Drag.BL, s.left, s.bottom),
        Triple(Drag.BR, s.right, s.bottom),
        Triple(Drag.T, s.centerX(), s.top),
        Triple(Drag.B, s.centerX(), s.bottom),
        Triple(Drag.L, s.left, s.centerY()),
        Triple(Drag.R, s.right, s.centerY())
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation or window resize can leave the selection hanging off the new bounds.
        selection?.let { s ->
            clampToView(s)
            if (s.width() <= 0f || s.height() <= 0f) selection = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = selection
        if (rect == null || rect.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            return
        }
        // Darken everything except the selection, using four rects so no layer is needed.
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, scrim)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), scrim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, scrim)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, scrim)
        canvas.drawRect(rect, border)
        drawHandles(canvas, rect)
    }

    /** White dot on a translucent black backing; the handle under the finger grows and turns blue. */
    private fun drawHandles(canvas: Canvas, rect: RectF) {
        for ((which, hx, hy) in handlePoints(rect)) {
            // `which` is always a handle constant, so equality alone identifies the active handle.
            val active = drag == which
            val scale = if (active) ACTIVE_SCALE else 1f
            canvas.drawCircle(
                hx, hy, backRadius * scale,
                if (active) handleActive else handleBack
            )
            canvas.drawCircle(hx, hy, dotRadius * scale, handleDot)
        }
    }

    private companion object {
        /** A selection smaller than this is not worth OCRing — and is hard to grab again. */
        const val MIN_SIZE_DP = 50

        /** Handle touch target; meets the 48dp accessibility recommendation. */
        const val HANDLE_TOUCH_DP = 48

        /** Handle under the finger is drawn this much larger than resting handles. */
        const val ACTIVE_SCALE = 1.3f
    }
}
