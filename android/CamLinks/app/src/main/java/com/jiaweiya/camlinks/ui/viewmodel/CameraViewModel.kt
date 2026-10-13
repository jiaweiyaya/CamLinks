package com.jiaweiya.camlinks.ui.viewmodel

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import com.jiaweiya.camlinks.logic.camera.CameraManager
import com.jiaweiya.camlinks.logic.model.CameraUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraManager = CameraManager(application)
    private val discoveryServer = com.jiaweiya.camlinks.logic.network.DeviceDiscoveryServer(application)
    private var tlsServer: com.jiaweiya.camlinks.logic.network.TlsCamServer? = null

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    init {
        cameraManager.onFrameAvailable = { frameBytes ->
            tlsServer?.broadcastBinary(frameBytes)
        }
        discoveryServer.start()
        startTlsServer()

        // 启动 1s 周期心跳任务，每秒自动将手机物理光圈、快门、ISO 等参数推给 PC
        heartbeatJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                syncDeviceState()
            }
        }
    }

    private var currentSurfaceTexture: android.graphics.SurfaceTexture? = null

    private fun startTlsServer() {
        try {
            tlsServer = com.jiaweiya.camlinks.logic.network.TlsCamServer(
                onClientConnected = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        cameraManager.requestKeyFrame()
                        syncDeviceState()
                    }
                }
            ) { action, json ->
                when (action) {
                    "set_torch" -> {
                        val enabled = json.optBoolean("bool_value", false)
                        cameraManager.setTorch(enabled)
                        _uiState.update { it.copy(isTorchOn = enabled) }
                        syncDeviceState()
                    }
                    "set_exposure" -> {
                        val ev = json.optInt("int_value", 0)
                        cameraManager.setExposure(ev)
                        _uiState.update { it.copy(exposureCompensation = ev) }
                        syncDeviceState()
                    }
                    "switch_camera" -> {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            toggleCameraFacing()
                            syncDeviceState()
                        }
                    }
                    "set_resolution" -> {
                        val w = json.optInt("width", 1280)
                        val h = json.optInt("height", 720)
                        cameraManager.targetWidth = w
                        cameraManager.targetHeight = h
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            restartCamera()
                            syncDeviceState()
                        }
                    }
                    "set_fps" -> {
                        val fps = json.optInt("fps", 60)
                        cameraManager.targetFps = fps
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            restartCamera()
                            syncDeviceState()
                        }
                    }
                    "request_key_frame" -> {
                        cameraManager.requestKeyFrame()
                    }
                    "set_bitrate" -> {
                        val bitrate = json.optInt("int_value", 4000)
                        cameraManager.setBitrate(bitrate)
                    }
                }
            }
            tlsServer?.start()
            android.util.Log.i("CamLinks", "TlsCamServer successfully started on port 8443")
        } catch (e: Throwable) {
            android.util.Log.e("CamLinks", "Failed to start TlsCamServer: " + e.message, e)
        }
    }

    private fun syncDeviceState() {
        val state = _uiState.value
        val (supportedRes, supportedFps) = cameraManager.getCameraCapabilities(state.lensFacing)
        val json = org.json.JSONObject().apply {
            put("is_torch_on", state.isTorchOn)
            put("lens_facing", state.lensFacing)
            put("exposure_compensation", state.exposureCompensation)
            put("min_exposure", state.minExposureCompensation)
            put("max_exposure", state.maxExposureCompensation)
            put("zoom_ratio", state.zoomRatio.toDouble())
            put("bitrate", cameraManager.maxBitrateKbps)
            put("current_resolution", "${cameraManager.targetWidth}x${cameraManager.targetHeight}")
            put("current_fps", cameraManager.targetFps)
            put("aperture", cameraManager.currentAperture)
            put("shutter", cameraManager.currentShutter)
            put("iso", cameraManager.currentIso)
            put("focal_length", cameraManager.currentFocalLength)

            val resArray = org.json.JSONArray()
            supportedRes.forEach { resArray.put(it) }
            put("supported_resolutions", resArray)

            val fpsArray = org.json.JSONArray()
            supportedFps.forEach { fpsArray.put(it) }
            put("supported_fps", fpsArray)
        }
        tlsServer?.broadcastState(json.toString())
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun onPreviewSurfaceTextureReady(surfaceTexture: android.graphics.SurfaceTexture) {
        currentSurfaceTexture = surfaceTexture
        restartCamera()
    }

    private fun restartCamera() {
        val surfaceTexture = currentSurfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(cameraManager.targetWidth, cameraManager.targetHeight)
        val surface = android.view.Surface(surfaceTexture)
        cameraManager.startCamera(surface, _uiState.value.lensFacing) { minZoom, maxZoom, minExp, maxExp ->
            _uiState.update {
                it.copy(
                    minZoomRatio = minZoom,
                    maxZoomRatio = maxZoom,
                    minExposureCompensation = minExp,
                    maxExposureCompensation = maxExp
                )
            }
            syncDeviceState()
        }
    }

    fun toggleCameraFacing() {
        val nextFacing = if (_uiState.value.lensFacing == 1) 0 else 1
        _uiState.update { it.copy(lensFacing = nextFacing, isTorchOn = false) }
        restartCamera()
    }

    fun toggleTorch() {
        val nextState = !_uiState.value.isTorchOn
        cameraManager.setTorch(nextState)
        _uiState.update { it.copy(isTorchOn = nextState) }
    }

    fun updateExposure(value: Int) {
        cameraManager.setExposure(value)
        _uiState.update { it.copy(exposureCompensation = value) }
    }

    fun updateZoom(ratio: Float) {
        cameraManager.setZoom(ratio)
        _uiState.update { it.copy(zoomRatio = ratio) }
    }

    fun triggerFocus(x: Float, y: Float, width: Float, height: Float) {
        cameraManager.focusAt(x, y, width, height)
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        discoveryServer.stop()
        try { tlsServer?.stop() } catch (_: Exception) {}
        cameraManager.release()
    }
}