package com.flowai.communication.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import com.flowai.communication.MainActivity
import com.flowai.communication.R
import com.flowai.communication.system.pet.PetSkins
import com.flowai.communication.system.pet.PetView
import com.flowai.communication.system.pet.PrefsPetSkinStore

/**
 * 在途截屏结果的归属：面板（面板内截屏按钮等旧入口）或回复卡片（桌宠一键 / 重新截屏）。
 * 文件私有：路由只由本服务决定。
 */
private enum class CaptureTarget { PANEL, CARD }

/**
 * Owns the floating desk pet.
 *
 * The pet is the persistent *entry point*, and a single tap on it is the whole capture flow:
 * frame the chat, read its text on device, and the three-style reply card appears over the chat
 * app — the user never leaves the conversation. Long-pressing cycles its skin; the panel itself
 * still opens from the home screen and the notification for paste-in flows. It is not a data
 * channel: nothing is captured merely because the pet is on screen, which keeps Just-in-Time
 * Context intact.
 *
 * A foreground service with a visible notification is deliberate: the user must always be able to
 * see that FlowAI is present on top of other apps, and the platform requires the notification for
 * long-lived overlays.
 */
class FloatingAssistantService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var skinStore: PrefsPetSkinStore

    /** The skin the pet is wearing; mirrors the store so a long press can just advance it. */
    private var currentSkinId: String = PetSkins.DEFAULT_ID
    private var pet: PetView? = null
    private var panel: AssistantPanelWindow? = null
    /** 三风格回复卡片（桌宠一键流程的结果窗口）；null = 当前没有卡片。 */
    private var replyCard: ReplyCardWindow? = null

    /**
     * 在途截屏的结果送给谁：面板（旧入口）或回复卡片（桌宠一键 / 卡片重新截屏）。
     *
     * 每次**发起**截屏时写入，因此不存在陈旧值：任何一个入口启动截屏都会先声明自己的归属。
     */
    @Volatile
    private var captureTarget: CaptureTarget = CaptureTarget.PANEL
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "floating assistant starting")
        if (!OverlayPermission.isGranted(this)) {
            // Nothing to draw without the permission; stop rather than linger invisibly.
            Log.w(TAG, "overlay permission missing; stopping")
            stopSelf()
            return
        }
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        skinStore = PrefsPetSkinStore(this)
        attachPet()
        instance = this
        requestScreenShare()
        if (openPanelOnStart) {
            openPanelOnStart = false
            togglePanel()
        }
        if (openCardOnStart) {
            openCardOnStart = false
            showTestCardInternal()
        }
        if (captureCardOnStart) {
            captureCardOnStart = false
            beginCardCapture()
        }
        // Text handed over before the service existed (selection toolbar opened it).
        pendingText?.let { text ->
            pendingText = null
            showPanelWithText(text)
        }
    }

    /**
     * Asks for screen sharing once, now, while the user is consciously switching the assistant on.
     *
     * The granted session stays alive for as long as the pet runs, so a later capture frames a
     * region and reads it off the current screen at once — no consent dialog mid-flow, and no
     * frame sampled while a dialog is on its way out. Skipped when a session already lives or the
     * user declined before; capturing still offers consent through the normal chain either way.
     */
    private fun requestScreenShare() {
        if (ScreenCaptureService.isActive || ScreenShareGateActivity.wasDeclined(this)) return
        runCatching { startActivity(ScreenShareGateActivity.intent(this)) }
            .onFailure { Log.w(TAG, "could not pre-authorise screen share", it) }
    }

    /**
     * Status bar height reported by the system, so the pet clears system UI on any device.
     *
     * Uses the window-metrics insets rather than a dimension lookup: no reflection, and it follows
     * whatever the current display actually reports (notch, cutout, gesture bar).
     */
    private fun statusBarInset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val insets = windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars())
        return insets.top
    }

    private fun attachPet() {
        val size = (PetView.SIZE_DP * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            // Fixed rather than WRAP_CONTENT: the pet measures itself to exactly this.
            size,
            size,
            // TYPE_APPLICATION_OVERLAY is required from API 26; older types are deprecated.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: never steal input focus from the app underneath.
            // NOT_TOUCH_MODAL: touches outside the pet reach the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = (MARGIN_DP * resources.displayMetrics.density).toInt()
            // Derived from the current display and safe area rather than a position picked for one
            // phone: a third of the way down, but never above the status bar or off the screen.
            x = resources.displayMetrics.widthPixels - size - margin
            val statusBar = statusBarInset()
            val preferred = resources.displayMetrics.heightPixels / 3
            y = preferred.coerceIn(statusBar + margin, (resources.displayMetrics.heightPixels - size).coerceAtLeast(statusBar + margin))
        }

        currentSkinId = skinStore.load()
        val view = PetView(
            context = this,
            onTap = { oneTapCapture() },
            onLongPress = { cycleSkin() },
            onDragEnd = { clampToScreen() }
        ).apply {
            setSkin(PetSkins.byId(currentSkinId))
            onDrag = { dx, dy ->
                params.x += dx.toInt()
                params.y += dy.toInt()
                runCatching { windowManager.updateViewLayout(this, params) }
            }
        }
        pet = view
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(TAG, "could not add overlay window", it)
                stopSelf()
            }
    }

    /**
     * Long-press handler: advance to the next skin and remember it.
     *
     * Cycling rather than showing a picker keeps the whole interaction on the overlay, without
     * pulling the user out of whatever app they were reading. The full wardrobe with previews
     * lives in the app.
     */
    private fun cycleSkin() {
        val next = PetSkins.next(currentSkinId)
        currentSkinId = next.id
        skinStore.save(next.id)
        pet?.setSkin(next)
        pet?.playSkinChange()
        Log.i(TAG, "pet skin -> ${next.id}")
        runCatching {
            Toast.makeText(this, getString(R.string.pet_skin_toast, next.name), Toast.LENGTH_SHORT).show()
        }
    }

    /** Keeps the pet reachable after a drag, without letting it leave the screen. */
    private fun clampToScreen() {
        val view = pet ?: return
        val metrics = resources.displayMetrics
        val margin = (MARGIN_DP * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
        // Let the user fling it to the nearer edge, which is the familiar floating-ball behaviour.
        val snapToRight = params.x + view.width / 2 > metrics.widthPixels / 2
        params.x = if (snapToRight) metrics.widthPixels - view.width - margin else margin
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Opens the in-place assistant panel.
     *
     * Used by the home screen button and the notification, not by the pet tap (that is the one-tap
     * capture in [oneTapCapture]). The panel floats over whatever the user is looking at, which is
     * the point: an assistant *inside* the chat app rather than a separate window to switch to.
     */
    private fun togglePanel() {
        Log.i(TAG, "pet tapped")
        val existing = panel
        if (existing != null) {
            panel = null
            existing.destroy()
            return
        }
        val window = AssistantPanelWindow(
            context = this,
            onClose = { panel = null },
            onStartCapture = {
                // The panel has already hidden itself so it will not appear in the frame.
                captureTarget = CaptureTarget.PANEL
                runCatching {
                    startActivity(CaptureForPanelActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Log.w(TAG, "could not start panel capture", it) }
            }
        )
        panel = window
        window.show()
    }

    /**
     * Tapping the pet is the one-tap capture: frame the chat, read its text **on device**, and the
     * three-style reply card comes back over the chat app — the user never leaves the conversation.
     *
     * A minimized card restores instead of starting a new round (its results are still there); a
     * suspended card means a capture is already in flight and this tap is swallowed.
     */
    private fun oneTapCapture() {
        Log.i(TAG, "pet tapped: one-tap")
        val card = replyCard
        if (card != null && card.isSuspended) return // a capture is already in flight
        if (card != null && card.isMinimized) {
            card.restore()
            return
        }
        beginCardCapture()
    }

    /**
     * 桌宠一键 / 卡片「重新截屏」的正式流程（阶段 2）：
     * 卡片先离场（避免被截进画面，同面板纪律）→ 框选 → 截屏 → 本机 OCR → 原文回到卡片。
     *
     * 结果归属在发起时声明为 [CaptureTarget.CARD]，[deliverCaptureImage] 据此路由。
     */
    private fun beginCardCapture() {
        val card = replyCard ?: newReplyCard()
        if (card.isSuspended) return
        captureTarget = CaptureTarget.CARD
        card.prepareForCapture()
        card.suspendForCapture()
        runCatching {
            startActivity(CaptureForPanelActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            // 截屏没起来：把卡片还回去，别让界面停在"消失"状态。
            Log.w(TAG, "could not start card capture", it)
            captureTarget = CaptureTarget.PANEL
            card.resumeAfterCapture()
        }
    }

    /** 弹出（或复用已存在的）回复卡片并写入阶段 1 样例内容。 */
    private fun showTestCardInternal() {
        val card = replyCard ?: newReplyCard()
        card.showTestContent()
    }

    private fun newReplyCard(): ReplyCardWindow =
        ReplyCardWindow(
            context = this,
            onClose = { replyCard = null },
            // 「重新截屏」走与桌宠一键完全相同的路径。
            onStartCapture = { beginCardCapture() }
        ).also { replyCard = it }

    /** Called when a capture started from the panel returns. */
    fun deliverCapture(text: String?, failure: String?) {
        Log.i(TAG, "deliverCapture: chars=${text?.length ?: 0} target=$captureTarget")
        if (captureTarget == CaptureTarget.CARD) {
            // 卡片已销毁（null）时静默丢弃：用户主动关掉了卡片，结果不该再找地方塞。
            replyCard?.deliverCapture(text, failure)
            return
        }
        val current = panel ?: newPanel()
        current.deliverCapture(text, failure)
    }

    /**
     * Called when a panel capture returns a framed picture: the panel reads and shows it in place.
     *
     * This is what keeps the whole capture loop inside the chat app — no hop into the full app,
     * which on real devices was the fragile step (task switches, intent re-delivery) that left
     * users with nothing after a capture.
     *
     * 桌宠一键发起的截屏（[captureTarget] = CARD）改送回复卡片：卡片只做本机 OCR，
     * 不走面板的分析流水线。
     */
    fun deliverCaptureImage(path: String): Boolean {
        Log.i(TAG, "deliverCaptureImage: target=$captureTarget panelOpen=${panel != null}")
        if (captureTarget == CaptureTarget.CARD) {
            val card = replyCard ?: return false // 卡片没了 → 调用方走自己的兜底路由
            card.deliverCaptureImage(path)
            return true
        }
        val current = panel ?: newPanel()
        current.deliverCaptureImage(path)
        return true
    }

    /** Shows the panel already holding [text]; used by the selection toolbar. */
    fun showPanelWithText(text: String) {
        Log.i(TAG, "showPanelWithText: chars=${text.length}")
        val current = panel ?: newPanel()
        current.showWithText(text)
    }

    private fun newPanel(): AssistantPanelWindow =
        AssistantPanelWindow(
            context = this,
            onClose = { panel = null },
            onStartCapture = {
                captureTarget = CaptureTarget.PANEL
                runCatching {
                    startActivity(CaptureForPanelActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Log.w(TAG, "could not start panel capture", it) }
            }
        ).also { panel = it }

    /** Escape hatch kept for the panel's "open full app" action. */
    private fun bringAppForward() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not bring app forward", it) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // minSdk is 26, so the notification channel API is always available.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.floating_channel_desc) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        panel?.destroy()
        panel = null
        // 卡片随服务销毁：destroy() 会取消阶段 4 起的在途生成请求。
        replyCard?.destroy()
        replyCard = null
        pet?.let { view -> runCatching { windowManager.removeView(view) } }
        pet = null
        isRunning = false
        if (instance === this) instance = null
        // The share session belongs to the floating window: closing the pet releases it.
        ScreenCaptureService.stop(this)
        Log.i(TAG, "floating assistant stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowAI"
        private const val CHANNEL_ID = "flowai.floating"
        private const val NOTIFICATION_ID = 1001
        private const val MARGIN_DP = 8

        /** Best-effort view of whether the pet is on screen, for the home screen toggle. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Live instance, so the app can reach the panel without going through the pet. */
        @Volatile
        private var instance: FloatingAssistantService? = null

        /** Set by [start] so a freshly started service opens the panel right away. */
        @Volatile
        private var openPanelOnStart: Boolean = false

        /** Set by [showTestCard] so a freshly started service pops the reply card right away. */
        @Volatile
        private var openCardOnStart: Boolean = false

        /** Set by [startCardCapture] so a freshly started service begins the card capture at once. */
        @Volatile
        private var captureCardOnStart: Boolean = false

        /** Text to show once the service is up; set by the selection entry point. */
        @Volatile
        private var pendingText: String? = null

        /**
         * Applies a skin chosen in the app to the live pet.
         *
         * False when the service is not running: the choice is already persisted by the caller,
         * so the pet simply wears it the next time it appears.
         */
        fun applySkin(id: String): Boolean {
            val current = instance ?: return false
            val skin = PetSkins.byId(id)
            current.currentSkinId = skin.id
            current.pet?.setSkin(skin)
            current.pet?.playSkinChange()
            Log.i(TAG, "pet skin applied from app -> ${skin.id}")
            return true
        }

        /**
         * Opens the assistant panel without the user having to tap the pet.
         *
         * Needed because injected touches cannot reach an overlay window, so the pet cannot be
         * driven from a test harness — and it also gives the app an in-app way to reach the panel.
         * Returns false when the overlay permission is missing or the service will not start.
         */
        fun openPanel(context: Context): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            val current = instance
            if (current != null) {
                current.togglePanel()
                return true
            }
            return start(context, thenOpenPanel = true)
        }

        /**
         * 弹出三风格回复卡片（阶段 1：写死样例文本，不截屏、不联网）。
         *
         * 与 [openPanel] 同理需要这个入口：adb 无法向 overlay 窗口注入触摸，桌宠单击
         * 只能人工触发——首页需要一个能点的按钮来驱动卡片验收。服务未运行时顺带把桌宠
         * 拉起来。悬浮权限缺失时返回 false。
         */
        fun showTestCard(context: Context): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            val current = instance
            if (current != null) {
                current.showTestCardInternal()
                return true
            }
            openCardOnStart = true
            return start(context)
        }

        /**
         * 触发"截屏 → 本机 OCR → 回复卡片"真实流程（等价桌宠单击，供 adb 可点的测试入口）。
         *
         * 桌宠与卡片按钮都在 overlay 窗口里、无法注入触摸，验收需要一个应用内入口。
         * 服务未运行时顺带拉起桌宠并在 onCreate 后立刻发起截屏。
         */
        fun startCardCapture(context: Context): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            val current = instance
            if (current != null) {
                current.beginCardCapture()
                return true
            }
            captureCardOnStart = true
            return start(context)
        }

        /** Routes a capture result to the live panel. No-op when no panel is open. */
        fun deliverCapture(text: String?, failure: String?): Boolean {
            val current = instance ?: return false
            current.deliverCapture(text, failure)
            return true
        }

        /** Routes a staged capture frame to the live panel for in-place analysis. */
        fun deliverCaptureImage(path: String): Boolean {
            val current = instance ?: return false
            return current.deliverCaptureImage(path)
        }

        /**
         * Shows the panel holding [text] — the selection-toolbar entry point.
         *
         * Returns false when the overlay permission is missing, so the caller can fall back to the
         * full app instead of dropping the user's selection.
         */
        fun showPanelWithText(context: Context, text: String): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            val current = instance
            if (current != null) {
                current.showPanelWithText(text)
                return true
            }
            pendingText = text
            return start(context)
        }

        /** Starts the pet. No-op (returns false) when the overlay permission is missing. */
        fun start(context: Context, thenOpenPanel: Boolean = false): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            openPanelOnStart = thenOpenPanel
            val intent = Intent(context, FloatingAssistantService::class.java)
            return runCatching { context.startForegroundService(intent) }.isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingAssistantService::class.java)) }
        }
    }
}
