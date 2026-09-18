package com.flowai.communication.system

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ML Kit text recognition using the BUNDLED Chinese model.
 *
 * The bundled model ships inside the APK, runs fully offline and does not depend on Google Play
 * services. The unbundled variant is smaller but downloads its model through GMS and returns empty
 * results until that completes, which would look like "capture found nothing" on devices without
 * GMS — the wrong failure mode for a capture feature.
 *
 * ML Kit's task API is asynchronous; this wraps it in a blocking call because capture happens on a
 * background thread inside a short-lived session.
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val closed = AtomicBoolean(false)

    override fun recognize(bitmap: Bitmap): List<OcrLine> {
        if (closed.get()) return emptyList()
        return runCatching {
            val result = com.google.android.gms.tasks.Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
            )
            result.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = line.boundingBox
                    OcrLine(
                        text = line.text,
                        left = box?.left ?: 0,
                        top = box?.top ?: 0,
                        right = box?.right ?: 0,
                        bottom = box?.bottom ?: 0
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "OCR failed", it)
        }.getOrDefault(emptyList())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TAG = "FlowAI"
    }
}
