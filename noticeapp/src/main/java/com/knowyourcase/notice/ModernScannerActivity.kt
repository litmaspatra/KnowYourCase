package com.knowyourcase.notice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class ModernScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_RESULT = "scan_result"
        private const val CAMERA_PERMISSION = 41
    }

    private lateinit var previewView: PreviewView
    private lateinit var torchButton: ImageButton
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

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || finished) return@registerForActivityResult
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (!value.isNullOrBlank()) finishWithResult(value)
                        else Toast.makeText(this, "No QR code found in that image.", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Could not read that image.", Toast.LENGTH_LONG).show()
                    }
            }
            .onFailure {
                Toast.makeText(this, "Could not open that image.", Toast.LENGTH_LONG).show()
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        root.addView(ScanOverlay(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = ContextCompat.getDrawable(this@ModernScannerActivity, R.drawable.bg_scanner_action)
            setColorFilter(Color.WHITE)
            contentDescription = "Close scanner"
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(18)
            marginStart = dp(14)
        })

        torchButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_scanner_flash)
            background = ContextCompat.getDrawable(this@ModernScannerActivity, R.drawable.bg_scanner_action)
            contentDescription = "Toggle flashlight"
            setPadding(dp(15), dp(15), dp(15), dp(15))
            setOnClickListener {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                alpha = if (torchOn) 1f else .72f
            }
        }
        root.addView(torchButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(18)
            marginEnd = dp(14)
        })

        val hint = TextView(this).apply {
            text = "Align the eCourts QR code inside the frame"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(0x66000000)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(30)
            rightMargin = dp(30)
            bottomMargin = dp(118)
        })

        val photoButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_scanner_photo)
            background = ContextCompat.getDrawable(this@ModernScannerActivity, R.drawable.bg_scanner_action)
            contentDescription = "Scan QR from photo"
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { photoPicker.launch("image/*") }
        }
        root.addView(photoButton, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(42)
        })

        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
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
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
                torchButton.alpha = if (torchButton.isEnabled) .72f else .3f
            } catch (_: Exception) {
                Toast.makeText(this, "Camera could not start. Use the photo button below.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWithResult(value: String) {
        if (finished) return
        finished = true

        imageAnalysis?.clearAnalyzer()
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()

        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, value))
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(
                this,
                "Camera permission denied. Use the photo button below.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        scanner.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class ScanOverlay(context: android.content.Context) : View(context) {
        private val shade = Paint().apply { color = 0x66000000 }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5CB7FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 4f
            strokeCap = Paint.Cap.SQUARE
        }

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
            canvas.drawRoundRect(rect, 26f, 26f, frame)

            val c = size * .13f
            canvas.drawLine(rect.left, rect.top + c, rect.left, rect.top, accent)
            canvas.drawLine(rect.left, rect.top, rect.left + c, rect.top, accent)
            canvas.drawLine(rect.right - c, rect.top, rect.right, rect.top, accent)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + c, accent)
            canvas.drawLine(rect.left, rect.bottom - c, rect.left, rect.bottom, accent)
            canvas.drawLine(rect.left, rect.bottom, rect.left + c, rect.bottom, accent)
            canvas.drawLine(rect.right - c, rect.bottom, rect.right, rect.bottom, accent)
            canvas.drawLine(rect.right, rect.bottom - c, rect.right, rect.bottom, accent)
        }
    }
}
