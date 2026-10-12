package com.jiaweiya.camlinks.logic.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import java.io.ByteArrayOutputStream
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    var frameQuality: Int = 75
    var onFrameAvailable: ((ByteArray) -> Unit)? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        onInitialized: (minZoom: Float, maxZoom: Float, minExp: Int, maxExp: Int) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(1920, 1080))
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val bitmap = imageProxy.toBitmap()
                            val outputStream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, frameQuality, outputStream)
                            val bytes = outputStream.toByteArray()
                            onFrameAvailable?.invoke(bytes)
                        } catch (_: Exception) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo

                cameraInfo?.let { info ->
                    val zoomState = info.zoomState.value
                    val expRange = info.exposureState.exposureCompensationRange

                    onInitialized(
                        zoomState?.minZoomRatio ?: 1f,
                        zoomState?.maxZoomRatio ?: 5f,
                        expRange.lower,
                        expRange.upper
                    )
                }
            } catch (e: Exception) {
                // 相机初始化异常处理
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setTorch(enable: Boolean) {
        cameraControl?.enableTorch(enable)
    }

    fun setZoom(ratio: Float) {
        cameraControl?.setZoomRatio(ratio)
    }

    fun setExposure(index: Int) {
        cameraControl?.setExposureCompensationIndex(index)
    }

    fun focusAt(x: Float, y: Float, width: Float, height: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(width, height)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        cameraControl?.startFocusAndMetering(action)
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "CamLinks_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    android.util.Log.i("CamLinks", "Photo saved to gallery: " + outputFileResults.savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e("CamLinks", "Photo capture failed", exception)
                }
            }
        )
    }

    fun release() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}