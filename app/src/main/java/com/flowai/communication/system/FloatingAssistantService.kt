package com.flowai.communication.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.flowai.communication.MainActivity
import com.flowai.communication.R

/**
 * Owns the floating assistant bubble.
 *
 * The bubble is a persistent *entry point*, not a data channel: tapping it only brings the app
 * forward. Reading the conversation still happens through the user-triggered entries (share,
 * selection, and later screenshot OCR), which keeps Just-in-Time Context intact — nothing is
 * captured merely because the bubble is on screen.
 *
 * A foreground service with a visible notification is deliberate: the user must always be able to
 * see that FlowAI is present on top of other apps, and the platform requires the notification for
 * long-lived overlays.
 */
class FloatingAssistantService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: FloatingBubbleView? = null
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
        attachBubble()
    }

    private fun attachBubble() {
        val size = (BUBBLE_SIZE_DP * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // TYPE_APPLICATION_OVERLAY is required from API 26; older types are deprecated.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: never steal input focus from the app underneath.
            // NOT_TOUCH_MODAL: touches outside the bubble reach the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (MARGIN_DP * resources.displayMetrics.density).toInt()
            y = resources.displayMetrics.heightPixels / 3
        }

        val view = FloatingBubbleView(
            context = this,
            onTap = { bringAppForward() },
            onDragEnd = { clampToScreen() }
        ).apply {
            onDrag = { dx, dy ->
                params.x += dx.toInt()
                params.y += dy.toInt()
                runCatching { windowManager.updateViewLayout(this, params) }
            }
        }
        bubble = view
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(TAG, "could not add overlay window", it)
                stopSelf()
            }
    }

    /** Keeps the bubble reachable after a drag, without letting it leave the screen. */
    private fun clampToScreen() {
        val view = bubble ?: return
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

    private fun bringAppForward() {
        Log.i(TAG, "bubble tapped")
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
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        isRunning = false
        Log.i(TAG, "floating assistant stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowAI"
        private const val CHANNEL_ID = "flowai.floating"
        private const val NOTIFICATION_ID = 1001
        private const val BUBBLE_SIZE_DP = 52
        private const val MARGIN_DP = 8

        /** Best-effort view of whether the bubble is on screen, for the home screen toggle. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts the bubble. No-op (returns false) when the overlay permission is missing. */
        fun start(context: Context): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            val intent = Intent(context, FloatingAssistantService::class.java)
            return runCatching { context.startForegroundService(intent) }.isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingAssistantService::class.java)) }
        }
    }
}
