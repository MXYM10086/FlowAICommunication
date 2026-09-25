package com.flowai.communication.system

import android.graphics.Bitmap
import android.util.Log
import com.flowai.communication.data.model.AnalysisResult
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.ConversationRepository
import com.flowai.communication.domain.CaptureFailure
import com.flowai.communication.domain.CaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads one captured frame the way the capture flow always should: recognise the text on the
 * device, then analyse that text.
 *
 * Sending the picture to a remote vision model looked richer but broke where it mattered — text-only
 * models refuse image input, requests die on the way, and the screenshot itself left the phone.
 * Recognising locally keeps the frame on the device and hands every engine, remote text model or
 * built-in one alike, something it can actually analyse, so a capture never dead-ends in an error
 * box just because the configured model cannot look at pictures.
 *
 * The bitmap is the caller's: nothing here recycles it.
 */
object FrameAnalysis {
    private const val TAG = "FlowAI"

    // Mirror the `R.string.capture_failure_*` texts; thrown messages reach notices verbatim, and
    // this object has no Context to resolve resources with.
    private const val BLACK_FRAME_GUIDANCE =
        "截屏全黑：对方应用开启了截屏保护，请切换到能正常显示聊天的界面后重试"
    private const val NO_TEXT_GUIDANCE = "没有识别到文字，请确认聊天内容完整显示后重试"
    private const val OCR_TIMEOUT_GUIDANCE = "识别超时，请重试"
    private const val UNKNOWN_GUIDANCE = "识别失败，请重试"

    /**
     * Recognises the frame on device and analyses the recognised text as a screenshot-sourced chat.
     *
     * Read failures are thrown as guidance rather than returned: the callers already show thrown
     * messages as their notice, and a frame that yielded nothing is not an analysis.
     */
    suspend fun analyze(repository: ConversationRepository, bitmap: Bitmap): AnalysisResult {
        val read = withContext(Dispatchers.IO) { OcrPipeline.recognize(bitmap) }
        return when (read) {
            is CaptureResult.Success -> {
                Log.i(TAG, "recognised chars=${read.text.length}; analysing as text")
                repository.analyze(read.text, SourceType.SCREENSHOT)
            }
            is CaptureResult.Failure -> {
                Log.w(TAG, "frame read failed: ${read.reason}")
                throw IllegalStateException(guidanceFor(read.reason))
            }
        }
    }

    /**
     * 识别失败原因 → 给用户看的中文引导语。
     *
     * internal：回复卡片（[ReplyCardWindow]）复用同一套文案，避免两处各写一份漂移。
     */
    internal fun guidanceFor(reason: CaptureFailure): String = when (reason) {
        CaptureFailure.BLACK_FRAME -> BLACK_FRAME_GUIDANCE
        CaptureFailure.NO_TEXT -> NO_TEXT_GUIDANCE
        CaptureFailure.OCR_TIMEOUT -> OCR_TIMEOUT_GUIDANCE
        else -> UNKNOWN_GUIDANCE
    }
}
