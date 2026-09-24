package com.knowyourcase.notice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.knowyourcase.notice.data.api.CaptchaRequest
import com.knowyourcase.notice.data.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Phase 1 CAPTCHA Solver — ML Kit OCR with preprocessing.
 *
 * Pipeline:
 *   Raw CAPTCHA bitmap
 *       → grayscale + contrast boost + binarize
 *       → ML Kit OCR
 *       → clean & fix common misreads
 *       → return solved text
 *
 */
class CaptchaSolver(private val context: android.content.Context) {

    companion object {
        private const val TAG = "CaptchaSolver"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    /** Returns the solved CAPTCHA string, or null if OCR failed. */
    suspend fun solve(rawBitmap: Bitmap): String? {
        // Use the backend's CAPTCHA-specific OpenCV/Tesseract pipeline first.
        // ML Kit remains an offline fallback when Render is sleeping/unavailable.
        val backendSolved = solveWithBackend(rawBitmap)
            ?.let(::cleanOcrResult)
            ?.takeIf { it.length == 6 }
        val solved = backendSolved ?: runOcr(preprocess(rawBitmap))
            ?.let(::cleanOcrResult)
            ?.takeIf { it.length == 6 }
            ?: return null

        Log.d(TAG, "CAPTCHA solved using ${if (backendSolved != null) "backend" else "on-device OCR"}")

        // eCourts uses exactly six lowercase letters/digits. Avoid spending a
        // server attempt on a partial OCR result.
        return solved
    }

    private suspend fun solveWithBackend(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            val response = RetrofitClient.service(context).solveCaptcha(
                CaptchaRequest(Base64.encodeToString(bytes, Base64.NO_WRAP))
            )
            response.body()?.solved.takeIf { response.isSuccessful }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Call this from ECourtWebViewActivity after we know if the CAPTCHA was correct.
     * Updates the saved sample with the ground truth label.
     */
    // ── Image preprocessing ────────────────────────────────────────────────────

    private fun preprocess(src: Bitmap): Bitmap {
        // 1. Scale up — OCR works better on larger images
        val scaled = Bitmap.createScaledBitmap(src, src.width * 3, src.height * 3, true)

        // 2. Grayscale
        val gray = toGrayscale(scaled)

        // 3. High contrast + binarize (makes text pop against noisy background)
        return binarize(gray, threshold = 128)
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }

        // Boost contrast before grayscale
        val contrast = 1.8f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrastMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun binarize(src: Bitmap, threshold: Int): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            pixels[i] = if (gray < threshold) {
                0xFF000000.toInt()  // black (text)
            } else {
                0xFFFFFFFF.toInt()  // white (background)
            }
        }

        result.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return result
    }

    // ── ML Kit OCR ─────────────────────────────────────────────────────────────

    private suspend fun runOcr(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text)
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }

    // ── Text cleaning ──────────────────────────────────────────────────────────

    /**
     * eCourts CAPTCHA is alphanumeric, typically 6 chars.
     * Cleans whitespace and fixes the most common OCR misreads.
     */
    private fun cleanOcrResult(raw: String): String {
        var text = raw
            .replace("\\s".toRegex(), "")   // strip all whitespace
            .lowercase()
            .trim()

        // Keep only alphanumeric
        text = text.replace("[^a-z0-9]".toRegex(), "")

        // The current field has maxlength=6; truncate OCR punctuation/noise tails.
        if (text.length > 6) text = text.substring(0, 6)

        return text
    }

    fun release() {
        recognizer.close()
    }
}
