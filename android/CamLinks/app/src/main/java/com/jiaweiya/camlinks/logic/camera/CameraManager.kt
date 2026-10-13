package com.jiaweiya.camlinks.logic.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2SystemManager
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    var targetWidth: Int = 1920
    var targetHeight: Int = 1080
    var targetFps: Int = 30
    var maxBitrateKbps: Int = 4000
    private var basePhysicalFocalLength: Float = 26f
    private var currentZoomRatio: Float = 1.0f
    private var bytesSentThisSecond: Long = 0L
    private var bitrateWindowStartTime: Long = 0L
    var onFrameAvailable: ((ByteArray) -> Unit)? = null

    // 实时物理参数
    var currentAperture: String = "f/1.8"
    var currentShutter: String = "1/100s"
    var currentIso: String = "ISO 100"
    var currentFocalLength: String = "26mm"

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        onInitialized: (minZoom: Float, maxZoom: Float, minExp: Int, maxExp: Int) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // 1. 构造分辨率选择器
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(targetWidth, targetHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // 2. 匹配硬件原生支持的真实帧率 Range（例如 [30, 60]），避免非法 Range 导致硬件拒收
            val nativeRanges = getHardwareFpsRanges(lensFacing)
            val matchedRange = nativeRanges.filter { it.upper <= targetFps }
                .maxByOrNull { it.upper }
                ?: nativeRanges.maxByOrNull { it.upper }
                ?: android.util.Range(30, 30)

            // 3. 让 Preview 和 ImageAnalysis 共享完全相同的 resSelector，彻底打破 4:3 枷锁
            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resSelector)

            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                matchedRange
            )
            val preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resSelector)

            val interopExtender = Camera2Interop.Extender(analysisBuilder)
            interopExtender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                matchedRange
            )

            interopExtender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val expTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    if (expTimeNs != null && expTimeNs > 0) {
                        val sec = 1.0 / (expTimeNs / 1_000_000_000.0)
                        currentShutter = "1/${sec.toInt()}s"
                    }
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { currentIso = "ISO $it" }
                    result.get(CaptureResult.LENS_APERTURE)?.let { currentAperture = "f/$it" }
                    result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                        basePhysicalFocalLength = it
                        val effectiveFocal = (it * currentZoomRatio).toInt()
                        currentFocalLength = "${effectiveFocal}mm"
                    }
                }
            })

            imageAnalysis = analysisBuilder.build().also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - bitrateWindowStartTime >= 500L) {
                            val currentBps = (bytesSentThisSecond * 8L * 1000L) / (now - bitrateWindowStartTime).coerceAtLeast(1L)
                            val targetBps = maxBitrateKbps.toLong() * 1024L

                            // 动态平滑自适应调节 Quality，确保画面永远连续不断流
                            if (currentBps > targetBps && frameQuality > 15) {
                                frameQuality = (frameQuality - 5).coerceAtLeast(15)
                            } else if (currentBps < targetBps * 0.75 && frameQuality < 92) {
                                frameQuality = (frameQuality + 3).coerceAtMost(92)
                            }
                            bitrateWindowStartTime = now
                            bytesSentThisSecond = 0L
                        }

                        val bitmap = imageProxy.toBitmap()
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, frameQuality, outputStream)
                        val bytes = outputStream.toByteArray()

                        bytesSentThisSecond += bytes.size
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
        currentZoomRatio = ratio
        val effectiveFocal = (basePhysicalFocalLength * ratio).toInt()
        currentFocalLength = "${effectiveFocal}mm"
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

    private fun getHardwareFpsRanges(lensFacing: Int): List<android.util.Range<Int>> {
        val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2SystemManager
        val targetFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }

        val result = mutableListOf<android.util.Range<Int>>()
        try {
            for (id in camera2Manager.cameraIdList) {
                val chars = camera2Manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let {
                        result.addAll(it)
                    }
                    break
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun getCameraCapabilities(lensFacing: Int): Pair<List<String>, List<Int>> {
        val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2SystemManager
        val targetFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }

        var resStrings = mutableListOf<String>()
        val fpsSet = sortedSetOf<Int>(compareByDescending { it })

        try {
            for (id in camera2Manager.cameraIdList) {
                val chars = camera2Manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    // 核心修复：精准查询视频流/Surface 原生真实支持的尺寸，杜绝拍照尺寸引发的缩水
                    val outputSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()

                    val sortedSizes = outputSizes
                        .filter { it.width >= 640 && it.height >= 480 }
                        .distinctBy { it.width * it.height }
                        .sortedByDescending { it.width.toLong() * it.height.toLong() }

                    resStrings = sortedSizes.map { "${it.width}x${it.height}" }.toMutableList()

                    // 探测常规及高速视频在内的所有硬件物理帧率
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach { range ->
                        if (range.upper in 15..480) fpsSet.add(range.upper)
                    }

                    // 检索支持的高速模式帧率（60, 120, 240, 480 等）
                    map?.highSpeedVideoFpsRanges?.forEach { range ->
                        if (range.upper in 15..480) fpsSet.add(range.upper)
                    }
                    if (fpsSet.contains(30)) {
                        fpsSet.add(60) // 确保支持标准 60FPS
                    }
                    break
                }
            }
        } catch (_: Exception) {}

        if (resStrings.isEmpty()) {
            resStrings = mutableListOf("1920x1080", "1280x720", "640x480")
        }
        if (fpsSet.isEmpty()) {
            fpsSet.addAll(listOf(60, 30, 24))
        }

        return Pair(resStrings, fpsSet.toList())
    }

    fun release() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}