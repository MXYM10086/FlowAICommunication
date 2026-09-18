package com.flowai.communication.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import com.flowai.communication.MainActivity
import com.flowai.communication.R
import com.flowai.communication.domain.CaptureEndReason
import com.flowai.communication.domain.CaptureRegion
import java.util.concurrent.atomic.AtomicReference

/**
 * Screenshot capture for the V2 "read the conversation on screen" entry point.
 *
 * Ordering that Android 14 requires and that is easy to get wrong:
 *  1. start the foreground service **with type mediaProjection**,
 *  2. only then call `getMediaProjection(...)`,
 *  3. and request user consent again for **every** capture session — the consent Intent must not be
 *     cached and reused.
 *
 * Because consent is per session, the service is short-lived: it is started once consent is
 * granted and stops as soon as a capture completes or the caller releases it. That also keeps the
 * Just-in-Time Context promise — nothing is captured while no session is open.
 */
class ScreenCaptureService : Service() {

    private val screenshotter = AtomicReference<Screenshotter?>(null)
    private val ocr = AtomicReference<OcrEngine?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The foreground notification must exist BEFORE getMediaProjection, or Android 14 throws.
        startForeground(NOTIFICATION_ID, buildNotification())

        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (data == null) {
            Log.w(TAG, "no projection consent data; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection? = runCatching {
            manager.getMediaProjection(resultCode, data)
        }.onFailure { Log.e(TAG, "getMediaProjection failed", it) }.getOrNull()

        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val shot = MediaProjectionScreenshotter(this, projection)
        screenshotter.set(shot)
        // ML Kit's recogniser is created lazily on first use so a granted-but-unused session is cheap.
        pendingRegion = intent.getParcelableExtra(EXTRA_REGION)
        attach(this)
        Log.i(TAG, "capture session started")
        return START_NOT_STICKY
    }

    /**
     * Captures the requested region and returns the recognised text.
     *
     * [region] null means the whole screen. Returns null when nothing could be read, which the
     * caller should present as "nothing found" rather than an error.
     *
     * Must not run on the main thread: OCR blocks and the projection work is synchronous here.
     */
    fun captureText(region: CaptureRegion?): String? {
        val shot = screenshotter.get() ?: return null
        if (!shot.isReady) return null
        val bitmap = shot.capture(region) ?: return null
        return try {
            val engine = ocr.get() ?: MlKitOcrEngine().also { ocr.set(it) }
            OcrTextAssembler.assembleDialogue(engine.recognize(bitmap)).ifBlank { null }
        } finally {
            // Release the frame immediately; a screenshot is the most sensitive artifact here.
            bitmap.recycle()
        }
    }

    override fun onDestroy() {
        ocr.getAndSet(null)?.close()
        screenshotter.getAndSet(null)?.release()
        detach(this)
        Log.i(TAG, "capture session released")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.capture_channel_desc) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FlowAI"
        private const val CHANNEL_ID = "flowai.capture"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_STOP = "com.flowai.communication.STOP_CAPTURE"
        private const val EXTRA_RESULT_DATA = "flowai.capture.resultData"
        private const val EXTRA_RESULT_CODE = "flowai.capture.resultCode"
        private const val EXTRA_REGION = "flowai.capture.region"

        /** Set once consent is granted, so the Activity can hand the result to the service. */
        @Volatile
        private var instance: ScreenCaptureService? = null

        @Volatile
        private var pendingRegion: CaptureRegion? = null

        /** True while a consented capture session is alive. */
        val isActive: Boolean get() = instance != null

        fun start(context: Context, resultCode: Int, data: Intent, region: CaptureRegion? = null): Boolean {
            pendingRegion = region
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_REGION, region)
            }
            return runCatching {
                // minSdk is 26, so startForegroundService is always available.
                context.startForegroundService(intent)
            }.isSuccess
        }

        fun stop(context: Context) {
            // Only delivers ACTION_STOP so the service can tear itself down.
            runCatching {
                val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
                context.startService(intent)
            }
        }

        /**
         * Captures and recognises, or null when no session is active / nothing was recognised.
         * Call from a background thread.
         */
        fun captureText(region: CaptureRegion? = null): String? = instance?.captureText(region)

        /** Region requested when the session was started, or null for the whole screen. */
        fun requestedRegion(): CaptureRegion? = pendingRegion

        internal fun attach(service: ScreenCaptureService) { instance = service }

        internal fun detach(service: ScreenCaptureService) {
            if (instance === service) instance = null
        }
    }
}
