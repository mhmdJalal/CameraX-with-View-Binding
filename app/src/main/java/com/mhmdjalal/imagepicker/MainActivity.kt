package com.mhmdjalal.imagepicker

import android.Manifest
import android.animation.AnimatorInflater
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.mhmdjalal.imagepicker.databinding.ActivityMainBinding
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var imageUri: Uri? = null
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // Request camera permissions
        if (allPermissionsGranted()) {
            setUpCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listener for take photo button
        binding.cameraCaptureButton.setOnClickListener { takePhoto() }
        binding.imageCancel.setOnClickListener {
            binding.groupCameraPicker.visibility = View.VISIBLE
            binding.groupPreview.visibility = View.GONE
            imageUri = null

            setUpCamera()
        }
        binding.imageSave.setOnClickListener {
            if (imageUri != null) {
                openFile(imageUri)
                Toast.makeText(this, "Saved to ${imageUri?.path}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.imageFlash.setOnClickListener {
            when(flashMode) {
                ImageCapture.FLASH_MODE_OFF -> {
                    binding.imageFlash.setImageResource(R.drawable.ic_flash_on)
                    flashMode = ImageCapture.FLASH_MODE_ON
                }
                ImageCapture.FLASH_MODE_ON -> {
                    binding.imageFlash.setImageResource(R.drawable.ic_flash_auto)
                    flashMode = ImageCapture.FLASH_MODE_AUTO
                }
                ImageCapture.FLASH_MODE_AUTO -> {
                    binding.imageFlash.setImageResource(R.drawable.ic_flash_off)
                    flashMode = ImageCapture.FLASH_MODE_OFF
                }
            }
            setUpCamera()
        }
        binding.imageRotate.setOnClickListener {
            val anim = AnimatorInflater.loadAnimator(this, R.animator.flip)
            anim.setTarget(binding.imageRotate)
            anim.start()
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                setUpCamera(CameraSelector.LENS_FACING_FRONT)
            } else {
                setUpCamera(CameraSelector.LENS_FACING_BACK)
            }
        }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = createFile(outputDirectory, FILENAME_FORMAT, PHOTO_EXTENSION)

        // Setup image capture metadata
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        applicationContext.packageName.toString() + ".provider",
                        photoFile
                    )
                    imageUri = savedUri
                    previewImage()
                }
            })
    }

    private fun previewImage() {
        binding.groupCameraPicker.visibility = View.GONE
        binding.groupPreview.visibility = View.VISIBLE

        Glide.with(this)
            .load(imageUri)
            .into(binding.imagePreview)
    }

    private fun setUpCamera(cameraType: Int? = null) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            if (cameraType != null) {
                lensFacing = cameraType
            }

            // Enable or disable switching between cameras
            updateCameraSwitchButton()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
//        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
//        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val screenAspectRatio = AspectRatio.RATIO_4_3

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    // Values returned from our analyzer are passed to the attached listener
                    // We log image analysis results here - you should do something useful
                    // instead!
                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        val switchCamerasButton = binding.imageRotate
        try {
            switchCamerasButton.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            switchCamerasButton.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun openFile(uri: Uri?) {
        try {
            val path = uri.toString().toLowerCase()
            val extension = path.substringAfterLast(".")
            val mimetype = if (extension.contains("doc")) {
                "application/msword"
            } else if (extension.contains("docx")) {
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            } else if (extension.contains("pptx")) {
                "application/vnd.ms-powerpoint"
            } else if (extension.contains("pptx")) {
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            } else if (extension.contains("xls")) {
                "application/vnd.ms-excel"
            } else if (extension.contains("xlsx")) {
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            } else if (extension.contains("zip") || extension.contains("rar")) {
                "application/zip"
            } else if (extension.contains("rtf")) {
                "application/rtf"
            } else if (extension.contains("wav") || extension.contains("mp3")) {
                "audio/x-wav"
            } else if (extension.contains("gif")) {
                "image/gif"
            } else if (extension.contains("jpg") || extension.contains("jpeg") || extension.contains(
                    "png"
                )) {
                "image/jpeg"
            } else if (extension.contains("txt")) {
                "text/plain"
            } else if (extension.contains("pdf")) {
                "application/pdf"
            } else if (extension.contains("3gp") || extension.contains("mpg") || extension.contains(
                    "mpeg"
                ) || extension.contains("mpe") || extension.contains("mp4") || extension.contains("avi")) {
                "video/*"
            } else {
                "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimetype)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setUpCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10

        private const val PHOTO_EXTENSION = ".jpg"

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(
                baseFolder, SimpleDateFormat(format, Locale.US)
                    .format(System.currentTimeMillis()) + extension
            )
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }
}