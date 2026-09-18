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
        val reader = imageReader ?: return null
        return runCatching {
            // acquireLatestImage drops stale frames, which is what we want for a one-shot read.
            val image = reader.acquireLatestImage() ?: return null
            image.use { crop(it, region) }
        }.onFailure { Log.w(TAG, "capture failed", it) }.getOrNull()
    }

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
        val step = 32
        var samples = 0
        var nonBlack = 0
        var sum = 0L
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                sum += lum
                if (lum > BLACK_THRESHOLD) nonBlack++
                samples++
                x += step
            }
            y += step
        }
        val avg = if (samples == 0) 0 else (sum / samples)
        Log.i(
            TAG,
            "frame ${bitmap.width}x${bitmap.height} avgLuma=$avg " +
                "nonBlackSamples=$nonBlack/$samples " +
                (if (nonBlack == 0) "=> ALL BLACK (FLAG_SECURE or empty frame)" else "")
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
    }
}
