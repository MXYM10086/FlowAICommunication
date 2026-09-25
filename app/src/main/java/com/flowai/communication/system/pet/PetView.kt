package com.flowai.communication.system.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The desk pet that replaced the plain floating bubble.
 *
 * Everything is drawn on a Canvas — no image assets — so a skin costs a palette instead of APK
 * weight. The pet breathes and blinks on its own, performs an idle action every few seconds
 * (hop, sway, stretch, spin, wink, hearts, doze), bursts particles and ripples when tapped, and
 * reports a long press so the owner can cycle the skin.
 *
 * The window stays tight around the body on purpose: on Android 12+ an overlay window's mostly
 * transparent margins can swallow (or drop) touches meant for the app underneath, so the
 * transparent border is kept small and the pet itself is opaque.
 */
class PetView(
    context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onDragEnd: () -> Unit
) : View(context) {

    /** Constructors the tooling expects; the service uses the callback form above. */
    @Suppress("unused")
    constructor(context: Context) : this(context, {}, {}, {})

    @Suppress("unused")
    constructor(context: Context, attrs: AttributeSet?) : this(context, {}, {}, {})

    /** Set by the window owner to translate the overlay window while dragging. */
    var onDrag: (Float, Float) -> Unit = { _, _ -> }

    private var skin: PetSkin = PetSkins.byId(PetSkins.DEFAULT_ID)

    // ------------------------------------------------------------------ animation state

    private var show: IdleShow? = null
    private val particles = ArrayList<Particle>()
    private val ripples = ArrayList<Ripple>()

    /** Start times of the tap burst and the skin-change flash; far past means "not playing". */
    private var tapAt = Long.MIN_VALUE
    private var skinChangeAt = Long.MIN_VALUE

    private var blinkUntil = 0L
    private var nextBlinkAt = 0L
    private var nextIdleAt = 0L
    private var lastDrawAt = 0L

    private var dragging = false
    private var longPressFired = false
    private var downRawX = 0f
    private var downRawY = 0f

    /** Where the pet is looking, -1..1; set while dragging and decayed back to centre. */
    private var lookX = 0f
    private var lookY = 0f

    private val random = Random()

    // ------------------------------------------------------------------ paints & geometry

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fxFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val path = Path()
    private val rect = RectF()
    private val pose = Pose()

    private var cx = 0f
    private var cy = 0f
    private var bodyRx = 0f
    private var bodyRy = 0f

    init {
        contentDescription = "FlowAI 桌宠"
    }

    // ------------------------------------------------------------------ public API

    /** Swaps the palette (and pose details) and repaints in place. */
    fun setSkin(skin: PetSkin) {
        this.skin = skin
        rebuildBodyShader()
        invalidate()
    }

    /** Plays the "new outfit" flourish: a bright flash plus a ring of the skin's particles. */
    fun playSkinChange() {
        val now = SystemClock.uptimeMillis()
        skinChangeAt = now
        ripples += Ripple(now, 470L, dp(44f), skin.auraColor.toInt(), dp(2.5f))
        ripples += Ripple(now + 90L, 520L, dp(36f), skin.bodyColor.toInt(), dp(3.5f))
        repeat(9) { i ->
            val angle = TAU * i / 9f + random.nextFloat() * 0.6f
            spawnParticle(
                bornAt = now + random.nextInt(70),
                angle = angle,
                speed = dp(55f) + random.nextFloat() * dp(45f),
                shape = shapeOf(skin.particle),
                size = dp(3.5f) + random.nextFloat() * dp(2.5f),
                color = skin.auraColor.toInt(),
                life = 520L + random.nextInt(260)
            )
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ sizing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(SIZE_DP).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f + h * 0.030f
        bodyRx = w * 0.345f
        bodyRy = h * 0.300f
        rebuildBodyShader()
    }

    private fun rebuildBodyShader() {
        if (bodyRy <= 0f) return
        bodyPaint.shader = LinearGradient(
            0f, cy - bodyRy, 0f, cy + bodyRy,
            skin.bodyColor.toInt(), skin.bodyShadowColor.toInt(), Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ frame loop

    private val frame = object : Runnable {
        override fun run() {
            tick()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val now = SystemClock.uptimeMillis()
        // A short grace period so the pet does not start performing the moment it appears.
        nextBlinkAt = now + randomBetween(1200L, 3200L)
        nextIdleAt = now + randomBetween(1600L, 3800L)
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        removeCallbacks(longPressCheck)
        super.onDetachedFromWindow()
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()

        if (now >= nextBlinkAt && show?.kind != Idle.SLEEPY) {
            blinkUntil = now + BLINK_MS
            nextBlinkAt = now + randomBetween(2100L, 5600L)
        }

        show?.let { if (now - it.startedAt >= it.duration) show = null }
        if (show == null && !dragging && now >= nextIdleAt) startRandomShow(now)

        particles.removeAll { now - it.bornAt >= it.life }
        ripples.removeAll { now - it.bornAt >= it.life }

        if (!dragging) {
            lookX *= 0.94f
            lookY *= 0.94f
        }

        // `in 0 until` rather than `<`: the start timestamps are Long.MIN_VALUE before the first
        // play, and subtracting that overflows.
        val busy = show != null || particles.isNotEmpty() || ripples.isNotEmpty() || dragging ||
            (now - tapAt) in 0 until TAP_FX_MS ||
            (now - skinChangeAt) in 0 until SKIN_FX_MS || now < blinkUntil
        // Idle frames are throttled: breathing is slow enough that drawing at ~20 fps costs
        // nothing visually and keeps a permanently visible overlay cheap.
        if (busy || now - lastDrawAt >= IDLE_FRAME_MS) {
            lastDrawAt = now
            invalidate()
        }
        postOnAnimation(frame)
    }

    private fun startRandomShow(now: Long) {
        val roll = random.nextInt(100)
        val kind = when {
            roll < 16 -> Idle.HOP
            roll < 32 -> Idle.SWAY
            roll < 46 -> Idle.STRETCH
            roll < 56 -> Idle.SPIN
            roll < 68 -> Idle.WINK
            roll < 83 -> Idle.HEART
            else -> Idle.SLEEPY
        }
        val duration = durationOf(kind)
        show = IdleShow(kind, now, duration)
        nextIdleAt = now + duration + randomBetween(2400L, 6800L)
        when (kind) {
            Idle.HEART -> spawnHearts(now)
            Idle.SLEEPY -> spawnZzz(now)
            Idle.SPIN -> spawnSpinSparks(now)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ touch

    private val longPressCheck = Runnable {
        if (!dragging) {
            longPressFired = true
            onLongPress()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                longPressFired = false
                alpha = PRESSED_ALPHA
                postDelayed(longPressCheck, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && abs(dx) + abs(dy) > dp(DRAG_SLOP_DP)) {
                    dragging = true
                    removeCallbacks(longPressCheck)
                }
                if (dragging) {
                    if (abs(dx) + abs(dy) > 0.5f) {
                        // The pet looks towards the drag, which makes it feel carried rather
                        // than teleported.
                        lookX = (lookX + dx / dp(24f)).coerceIn(-1f, 1f)
                        lookY = (lookY + dy / dp(24f)).coerceIn(-1f, 1f)
                    }
                    onDrag(dx, dy)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressCheck)
                alpha = 1f
                if (dragging) {
                    onDragEnd()
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !longPressFired) {
                    playTapFx()
                    onTap()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun playTapFx() {
        val now = SystemClock.uptimeMillis()
        tapAt = now
        ripples += Ripple(now, 460L, dp(40f), skin.auraColor.toInt(), dp(2.5f))
        ripples += Ripple(now + 70L, 520L, dp(32f), skin.bodyColor.toInt(), dp(3f))
        repeat(11) { i ->
            val angle = TAU * i / 11f + random.nextFloat() * 0.5f
            spawnParticle(
                bornAt = now,
                angle = angle,
                speed = dp(70f) + random.nextFloat() * dp(70f),
                shape = shapeOf(skin.particle),
                size = dp(3.5f) + random.nextFloat() * dp(3f),
                color = skin.auraColor.toInt(),
                life = 600L + random.nextInt(320)
            )
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ particle spawning

    private fun spawnParticle(
        bornAt: Long,
        angle: Float,
        speed: Float,
        shape: PShape,
        size: Float,
        color: Int,
        life: Long,
        gravity: Float = 1f
    ) {
        particles += Particle(
            x = cx,
            y = cy,
            vx = cos(angle) * speed,
            vy = sin(angle) * speed - dp(26f),
            bornAt = bornAt,
            life = life,
            shape = shape,
            size = size,
            color = color,
            spin = angle,
            gravity = gravity
        )
    }

    private fun spawnHearts(now: Long) {
        repeat(4) { i ->
            particles += Particle(
                x = cx + (random.nextFloat() - 0.5f) * bodyRx * 1.1f,
                y = cy - bodyRy * 0.55f,
                vx = (random.nextFloat() - 0.5f) * dp(16f),
                vy = -dp(30f) - random.nextFloat() * dp(24f),
                bornAt = now + i * 210L,
                life = 950L + random.nextInt(240),
                shape = PShape.HEART,
                size = dp(3.6f) + random.nextFloat() * dp(2f),
                color = skin.auraColor.toInt(),
                spin = 0f,
                gravity = 0.35f
            )
        }
    }

    private fun spawnZzz(now: Long) {
        repeat(3) { i ->
            particles += Particle(
                x = cx + bodyRx * 0.55f + i * dp(2f),
                y = cy - bodyRy * 0.65f,
                vx = dp(5f),
                vy = -dp(15f),
                bornAt = now + i * 700L,
                life = 1400L,
                shape = PShape.Z,
                size = dp(5f) + i * dp(1.4f),
                color = skin.eyeColor.toInt(),
                spin = 0f,
                gravity = 0f
            )
        }
    }

    private fun spawnSpinSparks(now: Long) {
        repeat(6) { i ->
            val angle = TAU * i / 6f
            particles += Particle(
                x = cx + cos(angle) * bodyRx * 0.95f,
                y = cy + sin(angle) * bodyRy * 0.95f,
                vx = cos(angle) * dp(28f),
                vy = sin(angle) * dp(28f) - dp(14f),
                bornAt = now + i * 90L,
                life = 650L,
                shape = shapeOf(skin.particle),
                size = dp(3f),
                color = skin.auraColor.toInt(),
                spin = angle,
                gravity = 0.6f
            )
        }
    }

    // ------------------------------------------------------------------ pose

    private fun computePose(now: Long) {
        pose.reset()

        // Breathing: the pet never looks frozen.
        val breathe = sin((now % BREATH_PERIOD_MS.toLong()) / BREATH_PERIOD_MS * PI * 2.0)
            .toFloat()
        pose.offsetY += dp(1.3f) * breathe
        pose.scaleY *= 1f + 0.018f * breathe
        pose.scaleX *= 1f - 0.010f * breathe

        // Tap: a damped bounce plus squash & stretch, and a briefly delighted face.
        val tapAge = now - tapAt
        if (tapAge in 0 until TAP_FX_MS) {
            val q = tapAge / TAP_FX_MS.toFloat()
            val bounce = sin(q * PI.toFloat() * 2f) * (1f - q) * (1f - q)
            pose.offsetY -= dp(8f) * bounce
            pose.scaleY *= 1f + 0.11f * bounce
            pose.scaleX *= 1f - 0.09f * bounce
            pose.mouthOpen = maxOf(pose.mouthOpen, sin(minOf(1f, q * 1.6f) * PI.toFloat()))
            pose.happy = q < 0.8f
        }

        if (now < blinkUntil && !pose.sleeping) pose.eyeOpen = 0.09f

        show?.let { current ->
            val q = ((now - current.startedAt).toFloat() / current.duration).coerceIn(0f, 1f)
            when (current.kind) {
                Idle.HOP -> {
                    val up = sin(q * PI.toFloat())
                    pose.offsetY -= dp(HOP_HEIGHT_DP) * up
                    pose.shadowScale = 1f - 0.3f * up
                    if (q > 0.78f) {
                        // Landing squash.
                        val land = sin((q - 0.78f) / 0.22f * PI.toFloat())
                        pose.scaleY *= 1f - 0.09f * land
                        pose.scaleX *= 1f + 0.07f * land
                    }
                }
                Idle.SWAY -> pose.rotation += 10f * sin(q * TAU.toFloat())
                Idle.STRETCH -> {
                    val s = sin(q * PI.toFloat())
                    pose.scaleY *= 1f + 0.09f * s
                    pose.scaleX *= 1f - 0.05f * s
                    pose.offsetY -= dp(1.5f) * s
                }
                Idle.SPIN -> pose.rotation += 360f * easeInOut(q)
                Idle.WINK -> if (q in 0.2f..0.86f) pose.wink = true
                Idle.HEART -> {
                    pose.happy = true
                    pose.mouthOpen = 0.22f
                }
                Idle.SLEEPY -> pose.sleeping = true
            }
        }

        pose.lookX = if (dragging) lookX.coerceIn(-1f, 1f) else lookX * 0.5f
        pose.lookY = if (dragging) lookY.coerceIn(-1f, 1f) else lookY * 0.5f
        if (dragging) pose.rotation += lookX * 6f
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        computePose(now)

        drawShadow(canvas)
        drawRipples(canvas, now)

        canvas.save()
        canvas.translate(0f, pose.offsetY)
        canvas.rotate(pose.rotation, cx, cy)
        canvas.scale(pose.scaleX, pose.scaleY, cx, cy)
        drawTail(canvas, now)
        drawEars(canvas)
        canvas.drawOval(cx - bodyRx, cy - bodyRy, cx + bodyRx, cy + bodyRy, bodyPaint)
        drawFace(canvas, now)
        drawFeet(canvas)
        canvas.restore()

        drawParticles(canvas, now)

        // Skin-change flash: a short white bloom over the body.
        val changeAge = now - skinChangeAt
        if (changeAge in 0 until SKIN_FX_MS) {
            val t = changeAge / SKIN_FX_MS.toFloat()
            fxFill.color = 0xFFFFFF
            fxFill.alpha = ((1f - t) * 130f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, bodyRy * (0.9f + 0.35f * t), fxFill)
        }
    }

    private fun drawShadow(canvas: Canvas) {
        fxFill.color = skin.eyeColor.toInt()
        fxFill.alpha = (34 * pose.shadowScale).toInt().coerceIn(0, 255)
        val shadowY = cy + bodyRy + height * 0.017f
        val rx = bodyRx * 0.82f * pose.shadowScale
        val ry = height * 0.020f
        rect.set(cx - rx, shadowY - ry, cx + rx, shadowY + ry)
        canvas.drawOval(rect, fxFill)
    }

    private fun drawRipples(canvas: Canvas, now: Long) {
        for (ripple in ripples) {
            val age = now - ripple.bornAt
            if (age < 0 || age > ripple.life) continue
            val t = age / ripple.life.toFloat()
            val eased = 1f - (1f - t) * (1f - t)
            fxStroke.color = ripple.color
            fxStroke.alpha = ((1f - t) * 170f).toInt().coerceIn(0, 255)
            fxStroke.strokeWidth = ripple.width * (1f - t * 0.6f)
            canvas.drawCircle(cx, cy, ripple.maxRadius * eased, fxStroke)
        }
    }

    private fun drawEars(canvas: Canvas) {
        val ear = skin.earColor.toInt()
        when (skin.accessory) {
            PetAccessory.ROUND_EARS -> {
                val r = width * 0.105f
                val y = cy - height * 0.235f
                val dx = width * 0.175f
                fillPaint.color = ear
                fillPaint.alpha = 255
                canvas.drawCircle(cx - dx, y, r, fillPaint)
                canvas.drawCircle(cx + dx, y, r, fillPaint)
                fillPaint.color = skin.cheekColor.toInt()
                fillPaint.alpha = 110
                canvas.drawCircle(cx - dx, y + r * 0.12f, r * 0.5f, fillPaint)
                canvas.drawCircle(cx + dx, y + r * 0.12f, r * 0.5f, fillPaint)
                fillPaint.alpha = 255
            }
            PetAccessory.POINTED_EARS -> {
                for (side in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(cx + side * width * 0.27f, cy - height * 0.145f)
                    path.lineTo(cx + side * width * 0.145f, cy - height * 0.335f)
                    path.lineTo(cx + side * width * 0.035f, cy - height * 0.150f)
                    path.close()
                    fillPaint.color = ear
                    canvas.drawPath(path, fillPaint)
                    // Inner ear.
                    fillPaint.color = skin.cheekColor.toInt()
                    fillPaint.alpha = 100
                    path.reset()
                    path.moveTo(cx + side * width * 0.215f, cy - height * 0.165f)
                    path.lineTo(cx + side * width * 0.150f, cy - height * 0.285f)
                    path.lineTo(cx + side * width * 0.085f, cy - height * 0.165f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                    fillPaint.alpha = 255
                }
            }
            PetAccessory.LONG_EARS -> {
                val rx = width * 0.055f
                val ry = height * 0.110f
                for (side in intArrayOf(-1, 1)) {
                    val earX = cx + side * width * 0.10f
                    val earY = cy - height * 0.260f
                    canvas.save()
                    canvas.rotate(side * -12f, earX, earY)
                    fillPaint.color = ear
                    rect.set(earX - rx, earY - ry, earX + rx, earY + ry)
                    canvas.drawOval(rect, fillPaint)
                    fillPaint.color = skin.cheekColor.toInt()
                    fillPaint.alpha = 90
                    rect.set(earX - rx * 0.5f, earY - ry * 0.6f, earX + rx * 0.5f, earY + ry * 0.6f)
                    canvas.drawOval(rect, fillPaint)
                    fillPaint.alpha = 255
                    canvas.restore()
                }
            }
            PetAccessory.ANTENNA -> {
                strokePaint.color = ear
                strokePaint.strokeWidth = dp(2.2f)
                path.reset()
                path.moveTo(cx, cy - height * 0.235f)
                path.quadTo(cx + width * 0.05f, cy - height * 0.315f, cx + width * 0.02f, cy - height * 0.350f)
                canvas.drawPath(path, strokePaint)
                fillPaint.color = skin.auraColor.toInt()
                canvas.drawCircle(cx + width * 0.02f, cy - height * 0.352f, width * 0.032f, fillPaint)
            }
            PetAccessory.AHOGE -> {
                strokePaint.color = ear
                strokePaint.strokeWidth = dp(2.4f)
                for (i in -1..1) {
                    path.reset()
                    val base = cx + i * width * 0.055f
                    path.moveTo(base, cy - height * 0.270f)
                    path.quadTo(
                        base + i * width * 0.030f, cy - height * 0.340f,
                        base + i * width * 0.015f, cy - height * 0.385f
                    )
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
    }

    private fun drawTail(canvas: Canvas, now: Long) {
        when (skin.tail) {
            PetTail.NONE -> Unit
            PetTail.FLUFFY -> {
                val sway = sin(now / 520.0).toFloat() * 7f
                val pivotX = cx + width * 0.26f
                val pivotY = cy + height * 0.12f
                canvas.save()
                canvas.rotate(38f + sway, pivotX, pivotY)
                fillPaint.color = skin.earColor.toInt()
                rect.set(
                    pivotX - width * 0.10f, pivotY - height * 0.170f,
                    pivotX + width * 0.10f, pivotY + height * 0.060f
                )
                canvas.drawOval(rect, fillPaint)
                canvas.restore()
            }
            PetTail.THIN -> {
                val sway = sin(now / 430.0).toFloat() * 5f
                strokePaint.color = skin.earColor.toInt()
                strokePaint.strokeWidth = width * 0.042f
                path.reset()
                path.moveTo(cx + width * 0.24f, cy + height * 0.170f)
                path.cubicTo(
                    cx + width * 0.42f, cy + height * 0.120f,
                    cx + width * 0.44f + sway, cy - height * 0.020f,
                    cx + width * 0.36f + sway, cy - height * 0.095f
                )
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    private fun drawFace(canvas: Canvas, now: Long) {
        val eyeY = cy - height * 0.045f
        val eyeDx = width * 0.115f
        val eyeRx = width * 0.033f
        val eyeRy = height * 0.044f * pose.eyeOpen

        if (pose.sleeping || pose.happy) {
            strokePaint.color = skin.eyeColor.toInt()
            strokePaint.strokeWidth = dp(2.4f)
            for (side in intArrayOf(-1, 1)) {
                val x = cx + side * eyeDx
                path.reset()
                if (pose.sleeping) {
                    // Closed eyes curve downwards (u_u).
                    path.moveTo(x - eyeRx, eyeY + eyeRy * 0.35f)
                    path.quadTo(x, eyeY + eyeRy * 1.45f, x + eyeRx, eyeY + eyeRy * 0.35f)
                } else {
                    // Happy eyes curve upwards (^ ^).
                    path.moveTo(x - eyeRx, eyeY + eyeRy * 0.35f)
                    path.quadTo(x, eyeY - eyeRy * 0.85f, x + eyeRx, eyeY + eyeRy * 0.35f)
                }
                canvas.drawPath(path, strokePaint)
            }
        } else {
            for (side in intArrayOf(-1, 1)) {
                val x = cx + side * eyeDx + pose.lookX * width * 0.018f
                val y = eyeY + pose.lookY * height * 0.014f
                if (pose.wink && side == 1) {
                    strokePaint.color = skin.eyeColor.toInt()
                    strokePaint.strokeWidth = dp(2.4f)
                    path.reset()
                    path.moveTo(x - eyeRx, y)
                    path.quadTo(x, y - eyeRy * 1.3f, x + eyeRx, y)
                    canvas.drawPath(path, strokePaint)
                    continue
                }
                fillPaint.color = skin.eyeColor.toInt()
                canvas.drawOval(x - eyeRx, y - eyeRy, x + eyeRx, y + eyeRy, fillPaint)
                if (pose.eyeOpen > 0.5f) {
                    fillPaint.color = 0xFFFFFF
                    canvas.drawCircle(x - eyeRx * 0.35f, y - eyeRy * 0.35f, eyeRx * 0.36f, fillPaint)
                }
            }
        }

        // Cheeks.
        fillPaint.color = skin.cheekColor.toInt()
        fillPaint.alpha = 120
        val cheekY = cy + height * 0.055f
        val cheekDx = width * 0.195f
        val cheekRx = width * 0.065f
        val cheekRy = height * 0.038f
        canvas.drawOval(cx - cheekDx - cheekRx, cheekY - cheekRy, cx - cheekDx + cheekRx, cheekY + cheekRy, fillPaint)
        canvas.drawOval(cx + cheekDx - cheekRx, cheekY - cheekRy, cx + cheekDx + cheekRx, cheekY + cheekRy, fillPaint)
        fillPaint.alpha = 255

        // Mouth.
        val mouthY = cy + height * 0.075f
        val mouthRx = width * 0.045f
        if (pose.mouthOpen > 0.08f) {
            fillPaint.color = skin.eyeColor.toInt()
            val open = pose.mouthOpen
            val mouthRy = mouthRx * (0.35f + 0.65f * open)
            canvas.drawOval(cx - mouthRx * 0.8f, mouthY - mouthRy * 0.2f, cx + mouthRx * 0.8f, mouthY + mouthRy * 1.2f, fillPaint)
        } else {
            strokePaint.color = skin.eyeColor.toInt()
            strokePaint.strokeWidth = dp(2.2f)
            path.reset()
            path.moveTo(cx - mouthRx * 0.9f, mouthY - mouthRx * 0.2f)
            path.quadTo(cx, mouthY + mouthRx * 0.9f, cx + mouthRx * 0.9f, mouthY - mouthRx * 0.2f)
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawFeet(canvas: Canvas) {
        fillPaint.color = skin.bodyShadowColor.toInt()
        val feetY = cy + bodyRy + height * 0.006f
        val dx = width * 0.13f
        for (side in intArrayOf(-1, 1)) {
            rect.set(
                cx + side * dx - width * 0.075f, feetY - height * 0.030f,
                cx + side * dx + width * 0.075f, feetY + height * 0.035f
            )
            canvas.drawOval(rect, fillPaint)
        }
    }

    // ------------------------------------------------------------------ particles

    private fun drawParticles(canvas: Canvas, now: Long) {
        for (particle in particles) {
            val age = now - particle.bornAt
            if (age < 0 || age > particle.life) continue
            val t = age / particle.life.toFloat()
            val alpha = when {
                t < 0.7f -> 255
                else -> (((1f - t) / 0.3f) * 255f).toInt().coerceIn(0, 255)
            }
            val secs = age / 1000f
            val x = particle.x + particle.vx * secs
            val y = particle.y + particle.vy * secs +
                0.5f * dp(GRAVITY_DP) * particle.gravity * secs * secs
            when (particle.shape) {
                PShape.DOT -> {
                    fxFill.color = particle.color
                    fxFill.alpha = alpha
                    canvas.drawCircle(x, y, particle.size, fxFill)
                }
                PShape.STAR -> {
                    fxFill.color = particle.color
                    fxFill.alpha = alpha
                    starPath(x, y, particle.size, particle.size * 0.45f)
                    canvas.drawPath(path, fxFill)
                }
                PShape.HEART -> {
                    fxFill.color = particle.color
                    fxFill.alpha = alpha
                    heartPath(x, y, particle.size)
                    canvas.drawPath(path, fxFill)
                }
                PShape.PETAL -> {
                    canvas.save()
                    canvas.rotate(particle.spin + age / 8f, x, y)
                    fxFill.color = particle.color
                    fxFill.alpha = alpha
                    rect.set(x - particle.size * 1.4f, y - particle.size * 0.7f, x + particle.size * 1.4f, y + particle.size * 0.7f)
                    canvas.drawOval(rect, fxFill)
                    canvas.restore()
                }
                PShape.Z -> {
                    textPaint.color = particle.color
                    textPaint.alpha = alpha
                    textPaint.textSize = particle.size * 2f
                    canvas.drawText("z", x, y, textPaint)
                }
            }
        }
    }

    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float) {
        path.reset()
        val phase = -PI.toFloat() / 2f
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = phase + i * PI.toFloat() / 5f
            val px = cx + cos(angle) * radius
            val py = cy + sin(angle) * radius
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private fun heartPath(cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy + r)
        path.cubicTo(cx - r * 1.6f, cy - r * 0.4f, cx - r * 0.6f, cy - r * 1.5f, cx, cy - r * 0.45f)
        path.cubicTo(cx + r * 0.6f, cy - r * 1.5f, cx + r * 1.6f, cy - r * 0.4f, cx, cy + r)
        path.close()
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun randomBetween(from: Long, to: Long): Long =
        from + (random.nextDouble() * (to - from)).toLong()

    private fun shapeOf(particle: PetParticle): PShape = when (particle) {
        PetParticle.STAR -> PShape.STAR
        PetParticle.HEART -> PShape.HEART
        PetParticle.DOT -> PShape.DOT
        PetParticle.PETAL -> PShape.PETAL
    }

    private fun durationOf(kind: Idle): Long = when (kind) {
        Idle.HOP -> 780L
        Idle.SWAY -> 1700L
        Idle.STRETCH -> 1500L
        Idle.SPIN -> 900L
        Idle.WINK -> 950L
        Idle.HEART -> 1600L
        Idle.SLEEPY -> 3400L
    }

    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

    // ------------------------------------------------------------------ small types

    private enum class Idle { HOP, SWAY, STRETCH, SPIN, WINK, HEART, SLEEPY }

    private enum class PShape { STAR, HEART, DOT, PETAL, Z }

    private class IdleShow(val kind: Idle, val startedAt: Long, val duration: Long)

    private class Particle(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val bornAt: Long,
        val life: Long,
        val shape: PShape,
        val size: Float,
        val color: Int,
        val spin: Float,
        val gravity: Float
    )

    private class Ripple(
        val bornAt: Long,
        val life: Long,
        val maxRadius: Float,
        val color: Int,
        val width: Float
    )

    private class Pose {
        var offsetY = 0f
        var rotation = 0f
        var scaleX = 1f
        var scaleY = 1f
        var eyeOpen = 1f
        var lookX = 0f
        var lookY = 0f
        var happy = false
        var sleeping = false
        var wink = false
        var mouthOpen = 0f
        var shadowScale = 1f

        fun reset() {
            offsetY = 0f
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
            eyeOpen = 1f
            lookX = 0f
            lookY = 0f
            happy = false
            sleeping = false
            wink = false
            mouthOpen = 0f
            shadowScale = 1f
        }
    }

    companion object {
        /** Window size in dp. Tight around the body; see the class docs about transparent margins. */
        const val SIZE_DP = 100f

        private const val DRAG_SLOP_DP = 8f
        private const val LONG_PRESS_MS = 550L
        private const val TAP_FX_MS = 540L
        private const val SKIN_FX_MS = 340L
        private const val BLINK_MS = 130L
        private const val IDLE_FRAME_MS = 50L
        private const val PRESSED_ALPHA = 0.85f
        private const val HOP_HEIGHT_DP = 13f
        private const val GRAVITY_DP = 130f
        /** Breathing period in milliseconds (Double so the phase maths stays in Double). */
        private const val BREATH_PERIOD_MS = 3400.0
        private const val TAU = (PI * 2).toFloat()
    }
}
