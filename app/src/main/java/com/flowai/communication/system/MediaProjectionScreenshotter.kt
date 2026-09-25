package com.flowai.communication.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.flowai.communication.domain.CaptureRegion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screenshot backend built on MediaProjection + VirtualDisplay + ImageReader.
 *
 * Points that are easy to get wrong, and why they are done this way:
 *
 *  - **Register the callback before creating the virtual display.** Without a callback
 *    `createVirtualDisplay` throws, and the callback is also how a user-initiated stop is noticed.
 *  - **Virtual display size is the PHYSICAL display size**, not the app window size. Overlay
 *    coordinates and capture coordinates then share one space, so a region picked on screen maps
 *    directly onto the captured bitmap.
 *  - **All capture work runs on a dedicated HandlerThread.** Bitmap creation and ImageReader
 *    callbacks must not block the main thread.
 *  - **`onCapturedContentResize` (Android 14+) is honoured** so window-level sharing or rotation
 *    does not silently return wrongly-sized frames.
 *  - **Frames are cropped in the caller's region**, so only the conversation area is ever handed
 *    to OCR.
 *  - **Several frames are sampled and the best one wins.** The first frame after the virtual
 *    display attaches is routinely black or half-rendered, so [capture] takes up to [MAX_FRAMES]
 *    shots [FRAME_INTERVAL_MS] apart and returns whichever shows the most non-black content.
 */
class MediaProjectionScreenshotter(
    context: Context,
    private val projection: MediaProjection
) : Screenshotter {

    private val handlerThread = HandlerThread("FlowAI-Capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val density: Int = context.resources.configuration.densityDpi

    @Volatile private var width: Int
    @Volatile private var height: Int

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val released = AtomicBoolean(false)

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system or user")
            release()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // Android 14+: content size can change (window sharing, rotation). Rebuild the reader.
            Log.i(TAG, "captured content resized to ${width}x$height")
            resize(width, height)
        }
    }

    init {
        val metrics = context.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels

        projection.registerCallback(callback, handler)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "FlowAI-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )
        Log.i(TAG, "capture ready: ${width}x$height @ $density dpi")
    }

    override val isReady: Boolean get() = !released.get() && virtualDisplay != null

    override fun capture(region: CaptureRegion?): Bitmap? {
        if (!isReady) return null
        // The frame loop is paced with handler.postDelayed on the capture thread, so this caller
        // (already a background thread) only blocks on a latch until the loop settles.
        val latch = CountDownLatch(1)
        val lock = Any()
        var outcome: Bitmap? = null
        var settled = false
        val deliver: (Bitmap?, FrameQuality?) -> Unit = { bitmap, quality ->
            // An all-black frame is FLAG_SECURE or a blank first frame, never a conversation.
            val framed = when {
                bitmap == null -> null
                quality == null || quality.nonBlackRatio <= 0f -> {
                    bitmap.recycle()
                    null
                }
                else -> bitmap
            }
            synchronized(lock) {
                if (settled) framed?.recycle() else {
                    outcome = framed
                    settled = true
                }
            }
            latch.countDown()
        }

        // Always paced on the capture handler: callers arrive from their own background thread
        // (the service forbids the main thread), never from this looper.
        handler.post { collectBestFrame(region, 0, null, null, deliver) }

        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            // The loop outlived us: mark settled so whatever it finishes with recycles itself.
            synchronized(lock) { settled = true }
            return null
        }
        return synchronized(lock) { outcome }
    }

    /**
     * Samples up to [MAX_FRAMES] frames [FRAME_INTERVAL_MS] apart and keeps the best one.
     *
     * The first frame after a VirtualDisplay attaches is routinely black or half-rendered, so a
     * single shot gambles on it; three shots 100ms apart almost always include a settled frame.
     * Every frame that loses the comparison is recycled at once — only the current best survives,
     * and the caller owns exactly one bitmap when the loop settles.
     */
    private fun collectBestFrame(
        region: CaptureRegion?,
        attempt: Int,
        best: Bitmap?,
        bestQuality: FrameQuality?,
        deliver: (Bitmap?, FrameQuality?) -> Unit
    ) {
        if (released.get()) {
            deliver(best, bestQuality)
            return
        }
        val frame = grabFrame(region)
        var keep = best
        var keepQuality = bestQuality
        if (frame != null) {
            val quality = measureFrameQuality(frame)
            if (isBetter(quality, keepQuality)) {
                keep?.recycle()
                keep = frame
                keepQuality = quality
            } else {
                frame.recycle()
            }
        }
        // A fully non-black frame cannot be beaten, so stop early instead of waiting out the loop.
        val done = attempt + 1 >= MAX_FRAMES || keepQuality?.nonBlackRatio == 1f
        if (done) {
            deliver(keep, keepQuality)
        } else {
            handler.postDelayed(
                { collectBestFrame(region, attempt + 1, keep, keepQuality, deliver) },
                FRAME_INTERVAL_MS
            )
        }
    }

    /** One acquire-and-crop attempt; null when no frame was available or cropping failed. */
    private fun grabFrame(region: CaptureRegion?): Bitmap? =
        runCatching {
            // acquireLatestImage drops stale frames, which is what we want for a one-shot read.
            imageReader?.acquireLatestImage()?.use { crop(it, region) }
        }.onFailure { Log.w(TAG, "capture failed", it) }.getOrNull()

    private fun isBetter(candidate: FrameQuality, current: FrameQuality?): Boolean =
        current == null ||
            candidate.nonBlackRatio > current.nonBlackRatio ||
            (candidate.nonBlackRatio == current.nonBlackRatio && candidate.avgLuma > current.avgLuma)

    /**
     * How much of a frame is actually rendered: mean luminance plus the share of pixels above
     * [BLACK_THRESHOLD]. Read in one bulk [Bitmap.getPixels]; a per-pixel getPixel walk over a
     * 1080p frame is two orders of magnitude slower.
     */
    private fun measureFrameQuality(bitmap: Bitmap): FrameQuality {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return FrameQuality(0f, 0f)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var sum = 0L
        var nonBlack = 0
        for (p in pixels) {
            val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            sum += lum
            if (lum > BLACK_THRESHOLD) nonBlack++
        }
        val count = pixels.size
        return FrameQuality(sum.toFloat() / count, nonBlack.toFloat() / count)
    }

    data class FrameQuality(val avgLuma: Float, val nonBlackRatio: Float)

    private fun crop(image: Image, region: CaptureRegion?): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // The buffer is row-padded, so build the full bitmap first and crop afterwards.
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)

        val full = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (full != padded) padded.recycle()

        val clamped = region?.takeIf { it.isValid() }?.clampTo(full.width, full.height)
        if (clamped == null) return full

        val cropped = Bitmap.createBitmap(full, clamped.left, clamped.top, clamped.width, clamped.height)
        if (cropped != full) full.recycle()
        logFrameStats(cropped)
        return cropped
    }

    /**
     * Records basic statistics of the captured frame.
     *
     * A frame that came back black is the signature of a `FLAG_SECURE` window, and it is otherwise
     * indistinguishable from "OCR found nothing" — which would send debugging in the wrong
     * direction entirely.
     */
    private fun logFrameStats(bitmap: Bitmap) {
        val quality = measureFrameQuality(bitmap)
        Log.i(
            TAG,
            "frame ${bitmap.width}x${bitmap.height} avgLuma=${quality.avgLuma} " +
                "nonBlackRatio=${quality.nonBlackRatio} " +
                (if (quality.nonBlackRatio <= 0f) "=> ALL BLACK (FLAG_SECURE or empty frame)" else "")
        )
    }

    private fun resize(targetWidth: Int, targetHeight: Int) {
        if (released.get() || targetWidth <= 0 || targetHeight <= 0) return
        if (targetWidth == width && targetHeight == height) return
        handler.post {
            val display = virtualDisplay ?: return@post
            val oldReader = imageReader
            val newReader = runCatching {
                ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
            }.getOrNull() ?: return@post

            val ok = runCatching {
                display.setSurface(null)
                display.resize(targetWidth, targetHeight, density)
                display.setSurface(newReader.surface)
            }.isSuccess

            if (!ok) {
                newReader.close()
                runCatching { display.setSurface(oldReader?.surface) }
                return@post
            }
            width = targetWidth
            height = targetHeight
            imageReader = newReader
            oldReader?.close()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        handlerThread.quitSafely()
        Log.i(TAG, "capture released")
    }

    private companion object {
        const val TAG = "FlowAI"

        /** Below this average luminance a pixel is treated as black. */
        const val BLACK_THRESHOLD = 8

        /** How many frames to sample before picking the best one. */
        const val MAX_FRAMES = 3

        /** Spacing between frame attempts; paced on the capture handler, never Thread.sleep. */
        const val FRAME_INTERVAL_MS = 100L

        /** Worst case for the whole loop: the intervals plus slack for acquire and measure. */
        const val CAPTURE_TIMEOUT_MS = MAX_FRAMES * FRAME_INTERVAL_MS + 700L
    }
}
