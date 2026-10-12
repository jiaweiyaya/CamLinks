package com.jiaweiya.camlinks.logic.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
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

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
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

    fun release() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}