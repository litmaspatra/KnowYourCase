package com.knowyourcase.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.knowyourcase.app.R
import com.knowyourcase.app.databinding.FragmentScannerBinding
import com.knowyourcase.app.utils.CnrUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false
    private var hasNavigated = false
    private var lastDetectedCnr: String? = null

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else if (_binding != null) {
            binding.tvHint.text = getString(R.string.choose_photo)
        }
    }

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) processGalleryImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        }

        binding.tvManualEntry.setOnClickListener {
            findNavController().navigate(R.id.manualEntryFragment)
        }

        binding.btnGallery.setOnClickListener {
            galleryPicker.launch("image/*")
        }

        binding.btnSearch.setOnClickListener {
            val cnr = CnrUtils.normalise(binding.etCnr.text.toString())
            if (CnrUtils.isValid(cnr)) {
                navigateToResult(cnr)
            } else {
                binding.tilCnr.error = "Please correct the CNR number"
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        hasNavigated = false
        lastDetectedCnr = null
        if (_binding != null) {
            binding.panelConfirm.visibility = View.GONE
            binding.tvHint.visibility = View.VISIBLE
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) } }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processFrame(proxy: ImageProxy) {
        if (hasNavigated) { proxy.close(); return }

        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    val cnr = CnrUtils.extractFromText(raw) ?: continue
                    if (CnrUtils.isValid(cnr)) {
                        requireActivity().runOnUiThread { navigateToResult(cnr) }
                        return@addOnSuccessListener
                    }
                }
                textRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        val cnr = CnrUtils.extractFromText(result.text)
                        if (cnr != null && cnr != lastDetectedCnr && CnrUtils.isValid(cnr)) {
                            lastDetectedCnr = cnr
                            requireActivity().runOnUiThread { showConfirmPanel(cnr) }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            .addOnFailureListener { proxy.close() }
    }

    private fun showConfirmPanel(cnr: String) {
        if (_binding == null) return
        binding.panelConfirm.visibility = View.VISIBLE
        binding.etCnr.setText(cnr)
        binding.tilCnr.error = null
        binding.tvHint.visibility = View.GONE
    }

    private fun processGalleryImage(uri: Uri) {
        val image = try {
            InputImage.fromFilePath(requireContext(), uri)
        } catch (_: Exception) {
            showGalleryError()
            return
        }

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qrCnr = barcodes.asSequence()
                    .mapNotNull { it.rawValue }
                    .mapNotNull { CnrUtils.extractFromText(it) }
                    .firstOrNull { CnrUtils.isValid(it) }

                if (qrCnr != null) {
                    navigateToResult(qrCnr)
                    return@addOnSuccessListener
                }

                textRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        val cnr = CnrUtils.extractFromText(result.text)
                        if (cnr != null && CnrUtils.isValid(cnr)) {
                            showConfirmPanel(cnr)
                        } else {
                            showGalleryError()
                        }
                    }
                    .addOnFailureListener { showGalleryError() }
            }
            .addOnFailureListener { showGalleryError() }
    }

    private fun showGalleryError() {
        if (!isAdded) return
        android.widget.Toast.makeText(
            requireContext(),
            R.string.cnr_not_found_in_photo,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun navigateToResult(cnr: String) {
        if (hasNavigated) return
        hasNavigated = true
        findNavController().navigate(
            ScannerFragmentDirections.actionScannerToResult(cnr)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }
}
