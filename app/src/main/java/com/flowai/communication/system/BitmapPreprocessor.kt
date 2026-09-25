package com.flowai.communication.system

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Cleans a captured frame before OCR: greyscale, contrast stretch, polarity normalisation, and —
 * for the fallback pass only — Otsu binarisation with a median denoise.
 *
 * Screenshots of chat UIs arrive with tinted bubbles, gradients and anti-aliased edges; the
 * recogniser reads crisper when the page is plain black-on-white. Every stage is a single linear
 * pass over one [IntArray] read with `getPixels`, so a 1080p frame costs a few tens of
 * milliseconds rather than the seconds a per-pixel `getPixel` walk would.
 *
 * [normalize] is the primary preparation and deliberately stops short of binarising: a global
 * ink/paper split cannot exist on a chat screen where one party's bubbles carry white text and
 * the other's carry black — hard-thresholding such a frame erases whichever text falls on the
 * wrong side of the threshold, which is how a whole conversation shrank to one line. Greyscale
 * plus a contrast stretch keeps both polarities legible; the binarised [preprocess] remains as a
 * second attempt for frames the recogniser still reads poorly.
 *
 * Pure framework Kotlin — no third-party image library.
 */
object BitmapPreprocessor {

    /** Rec. 601 luma weights, applied to all three channels so R=G=B=luma afterwards. */
    private val GRAY_MATRIX = floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    private const val WHITE = 255
    private const val BLACK = 0

    /**
     * Returns a binarised ARGB_8888 copy of [source], or [source] itself when there is nothing to
     * enhance (empty dimensions, an already-recycled frame, or a flat image whose pixels are all
     * identical — binarising those would only manufacture edges).
     *
     * The caller owns both bitmaps: the returned one is a fresh allocation unless it is [source].
     */
    fun preprocess(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0 || source.isRecycled) return source

        val gray = toGrayscale(source, width, height)
        val luma = IntArray(width * height)
        gray.getPixels(luma, 0, width, 0, 0, width, height)
        gray.recycle()

        var min = 255
        var max = 0
        for (i in luma.indices) {
            val v = luma[i] and 0xFF
            luma[i] = v
            if (v < min) min = v
            if (v > max) max = v
        }
        // A flat image carries no text: stretching would divide by zero, binarising would noise.
        if (max == min) return source

        // Linear contrast stretch, histogrammed in the same pass for Otsu below.
        val histogram = IntArray(256)
        val span = max - min
        for (i in luma.indices) {
            val stretched = (luma[i] - min) * 255 / span
            luma[i] = stretched
            histogram[stretched]++
        }

        val threshold = otsuThreshold(histogram, luma.size)
        var whites = 0
        for (i in luma.indices) {
            val v = if (luma[i] > threshold) WHITE else BLACK
            luma[i] = v
            if (v == WHITE) whites++
        }
        // A mostly-black page means Otsu kept the *text* white — the dark-mode signature. Flip so
        // ink is dark on light paper, which is what the recogniser expects.
        if (whites * 2 < luma.size) {
            for (i in luma.indices) luma[i] = if (luma[i] == WHITE) BLACK else WHITE
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(median3x3(luma, width, height), 0, width, 0, 0, width, height)
        return result
    }

    /**
     * The primary preparation: greyscale, full-range contrast stretch, polarity normalisation —
     * and no thresholding at all.
     *
     * A chat frame has no single ink/paper split: one party's bubbles carry white text, the
     * other's black, so any global binarisation erases whichever side falls wrong. Keeping the
     * stretched greyscale leaves both legible. Dark-mode frames (mean luma on the dark side after
     * the stretch) are inverted whole, so the page reads as ink on paper either way.
     */
    fun normalize(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0 || source.isRecycled) return source

        val gray = toGrayscale(source, width, height)
        val luma = IntArray(width * height)
        gray.getPixels(luma, 0, width, 0, 0, width, height)
        gray.recycle()

        var min = 255
        var max = 0
        for (i in luma.indices) {
            val v = luma[i] and 0xFF
            luma[i] = v
            if (v < min) min = v
            if (v > max) max = v
        }
        if (max == min) return source

        val span = max - min
        var sum = 0L
        for (i in luma.indices) {
            val stretched = (luma[i] - min) * 255 / span
            luma[i] = stretched
            sum += stretched
        }
        if (sum / luma.size < 128) {
            for (i in luma.indices) luma[i] = 255 - luma[i]
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(packGray(luma), 0, width, 0, 0, width, height)
        return result
    }

    /** Packs luma values into opaque ARGB ints for a single [Bitmap.setPixels] call. */
    private fun packGray(luma: IntArray): IntArray =
        IntArray(luma.size) { i ->
            val v = luma[i]
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

    /** Greyscale copy drawn through a [ColorMatrixColorFilter]; the caller must recycle it. */
    private fun toGrayscale(source: Bitmap, width: Int, height: Int): Bitmap {
        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(GRAY_MATRIX))
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return gray
    }

    /**
     * Otsu's method: the threshold maximising between-class variance of the histogram, i.e. the
     * split that best separates ink from paper without any hand-tuned constant.
     */
    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sumAll = 0L
        for (value in 0..255) sumAll += value.toLong() * histogram[value]

        var backgroundWeight = 0L
        var backgroundSum = 0L
        var best = 0
        var bestVariance = -1.0
        for (t in 0..255) {
            backgroundWeight += histogram[t]
            backgroundSum += t.toLong() * histogram[t]
            val foregroundWeight = total.toLong() - backgroundWeight
            if (backgroundWeight == 0L || foregroundWeight == 0L) continue
            val meanDelta = backgroundSum.toDouble() / backgroundWeight -
                (sumAll - backgroundSum).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight * meanDelta * meanDelta
            if (variance > bestVariance) {
                bestVariance = variance
                best = t
            }
        }
        return best
    }

    /**
     * 3x3 median filter, which on a binary image is exactly a 9-neighbour majority vote: isolated
     * speckles flip to their surroundings while real strokes (runs wider than one pixel) survive.
     * Border pixels clamp to the edge instead of reading outside the frame.
     *
     * Returns packed ARGB ints so the result feeds `setPixels` directly.
     */
    private fun median3x3(binary: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(binary.size)
        for (y in 0 until height) {
            val topRow = (if (y > 0) y - 1 else 0) * width
            val midRow = y * width
            val bottomRow = (if (y < height - 1) y + 1 else height - 1) * width
            for (x in 0 until width) {
                val left = if (x > 0) x - 1 else 0
                val right = if (x < width - 1) x + 1 else width - 1
                val whites = rowWhites(binary, topRow, left, x, right) +
                    rowWhites(binary, midRow, left, x, right) +
                    rowWhites(binary, bottomRow, left, x, right)
                val v = if (whites >= 5) WHITE else BLACK
                out[midRow + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return out
    }

    /** Count of white pixels among the three neighbours of one row. */
    private fun rowWhites(binary: IntArray, row: Int, left: Int, centre: Int, right: Int): Int {
        var whites = 0
        if (binary[row + left] == WHITE) whites++
        if (binary[row + centre] == WHITE) whites++
        if (binary[row + right] == WHITE) whites++
        return whites
    }
}
