package com.knowyourcase.notice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.knowyourcase.notice.ui.StateKind
import com.knowyourcase.notice.ui.TextRole
import com.knowyourcase.notice.ui.UiTokens
import com.knowyourcase.notice.ui.applyType
import com.knowyourcase.notice.ui.dp
import com.knowyourcase.notice.ui.roundedColor
import com.knowyourcase.notice.ui.statePanel
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
class ModernScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_RESULT = "scan_result"
    }

    private lateinit var root: FrameLayout
    private lateinit var controls: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var torchButton: ImageButton
    private lateinit var photoButton: ImageButton
    private lateinit var cameraProgress: CircularProgressIndicator
    private lateinit var photoProgress: CircularProgressIndicator

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var finished = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else showCameraError(
            "Camera permission is off",
            "Allow camera access to scan a notice, or choose an existing QR image."
        )
    }

    private val photoPicker: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || finished) return@registerForActivityResult
        setPhotoLoading(true)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (!value.isNullOrBlank()) {
                            finishWithResult(value)
                        } else {
                            setPhotoLoading(false)
                            showMessage("No QR code was found in that image.", "Choose another") {
                                launchPhotoPicker()
                            }
                        }
                    }
                    .addOnFailureListener {
                        setPhotoLoading(false)
                        showMessage("That image could not be read.", "Try again") {
                            launchPhotoPicker()
                        }
                    }
            }
            .onFailure {
                setPhotoLoading(false)
                showMessage("That image could not be opened.", "Choose another") {
                    launchPhotoPicker()
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchPhotoPicker() {
        if (!finished) photoPicker.launch("image/*")
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.nt_scanner_scrim))
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        root.addView(
            ScanOverlay(this),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        controls = FrameLayout(this)
        root.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val close = scannerActionButton(R.drawable.ic_nt_close, "Close scanner") { finish() }
        controls.addView(
            close,
            FrameLayout.LayoutParams(dp(UiTokens.Size.SCANNER_ACTION), dp(UiTokens.Size.SCANNER_ACTION), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(UiTokens.Space.MD)
                marginStart = dp(UiTokens.Space.MD)
            }
        )

        torchButton = scannerActionButton(R.drawable.ic_nt_flash, "Toggle flashlight") {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchButton.alpha = if (torchOn) 1f else .78f
        }.apply {
            isEnabled = false
            alpha = .45f
        }
        controls.addView(
            torchButton,
            FrameLayout.LayoutParams(dp(UiTokens.Size.SCANNER_ACTION), dp(UiTokens.Size.SCANNER_ACTION), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(UiTokens.Space.MD)
                marginEnd = dp(UiTokens.Space.MD)
            }
        )

        val hint = TextView(this).apply {
            text = "Align the eCourts QR code inside the frame"
            applyType(TextRole.BODY, true)
            setTextColor(getColor(R.color.nt_scanner_text))
            gravity = Gravity.CENTER
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.XS),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.XS)
            )
            background = roundedColor(R.color.nt_scanner_control, UiTokens.Radius.MEDIUM)
        }
        controls.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(UiTokens.Space.LG)
                rightMargin = dp(UiTokens.Space.LG)
                bottomMargin = dp(UiTokens.Space.XL + UiTokens.Size.SCANNER_PHOTO + UiTokens.Space.MD)
            }
        )

        photoButton = scannerActionButton(R.drawable.ic_nt_photo, "Scan QR from photo") {
            launchPhotoPicker()
        }
        controls.addView(
            photoButton,
            FrameLayout.LayoutParams(dp(UiTokens.Size.SCANNER_PHOTO), dp(UiTokens.Size.SCANNER_PHOTO), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(UiTokens.Space.LG)
            }
        )

        photoProgress = CircularProgressIndicator(this).apply {
            visibility = View.GONE
            isIndeterminate = true
            indicatorSize = dp(UiTokens.Icon.SUPPORT)
            trackThickness = dp(UiTokens.Space.XXS)
            setIndicatorColor(getColor(R.color.nt_scanner_text))
        }
        controls.addView(
            photoProgress,
            FrameLayout.LayoutParams(dp(UiTokens.Icon.SUPPORT), dp(UiTokens.Icon.SUPPORT), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(UiTokens.Space.LG + (UiTokens.Size.SCANNER_PHOTO - UiTokens.Icon.SUPPORT) / 2)
            }
        )

        cameraProgress = CircularProgressIndicator(this).apply {
            isIndeterminate = true
            indicatorSize = dp(UiTokens.Size.PROGRESS)
            trackThickness = dp(UiTokens.Space.XXS)
            setIndicatorColor(getColor(R.color.nt_scanner_accent))
        }
        controls.addView(
            cameraProgress,
            FrameLayout.LayoutParams(dp(UiTokens.Size.PROGRESS), dp(UiTokens.Size.PROGRESS), Gravity.CENTER)
        )

        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setContentView(root)
    }

    private fun scannerActionButton(iconRes: Int, description: String, action: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(getColor(R.color.nt_scanner_text))
            background = roundedColor(R.color.nt_scanner_control, UiTokens.Radius.PILL)
            contentDescription = description
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD)
            )
            setOnClickListener { action() }
        }

    private fun startCamera() {
        cameraProgress.visibility = View.VISIBLE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrElse {
                cameraProgress.visibility = View.GONE
                showCameraError("Camera unavailable", "The camera could not be opened. You can still scan a QR code from a photo.")
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.display.rotation)
                .build()
            imageAnalysis = analysis

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (finished) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val media = imageProxy.image
                if (media == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (!value.isNullOrBlank()) finishWithResult(value)
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
                torchButton.alpha = if (torchButton.isEnabled) .78f else .45f
                cameraProgress.visibility = View.GONE
            } catch (_: Exception) {
                cameraProgress.visibility = View.GONE
                showCameraError("Camera unavailable", "The camera could not start. You can still scan a QR code from a photo.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showCameraError(title: String, message: String) {
        cameraProgress.visibility = View.GONE
        controls.addView(
            statePanel(
                StateKind.ERROR,
                title,
                message,
                R.drawable.ic_nt_error,
                "Choose photo"
            ) { launchPhotoPicker() },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = dp(UiTokens.Space.LG)
                marginEnd = dp(UiTokens.Space.LG)
            }
        )
    }

    private fun setPhotoLoading(loading: Boolean) {
        photoButton.isEnabled = !loading
        photoButton.alpha = if (loading) .45f else 1f
        photoProgress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showMessage(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val bar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        if (!actionLabel.isNullOrBlank() && action != null) bar.setAction(actionLabel) { action() }
        bar.show()
    }

    private fun finishWithResult(value: String) {
        if (finished) return
        finished = true
        imageAnalysis?.clearAnalyzer()
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, value))
        finish()
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        scanner.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private class ScanOverlay(context: android.content.Context) : View(context) {
        private val shade = Paint().apply { color = context.getColor(R.color.nt_scanner_scrim) }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.nt_scanner_text)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.nt_scanner_accent)
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 4f
            strokeCap = Paint.Cap.SQUARE
        }
        private val radius = context.dp(UiTokens.Radius.LARGE).toFloat()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = width * 0.74f
            val left = (width - size) / 2f
            val top = height * 0.25f
            val rect = RectF(left, top, left + size, top + size)

            canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shade)
            canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shade)
            canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shade)
            canvas.drawRoundRect(rect, radius, radius, frame)

            val corner = size * .13f
            canvas.drawLine(rect.left, rect.top + corner, rect.left, rect.top, accent)
            canvas.drawLine(rect.left, rect.top, rect.left + corner, rect.top, accent)
            canvas.drawLine(rect.right - corner, rect.top, rect.right, rect.top, accent)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + corner, accent)
            canvas.drawLine(rect.left, rect.bottom - corner, rect.left, rect.bottom, accent)
            canvas.drawLine(rect.left, rect.bottom, rect.left + corner, rect.bottom, accent)
            canvas.drawLine(rect.right - corner, rect.bottom, rect.right, rect.bottom, accent)
            canvas.drawLine(rect.right, rect.bottom - corner, rect.right, rect.bottom, accent)
        }
    }
}
