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

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraManager = CameraManager(application)
    private val discoveryServer = com.jiaweiya.camlinks.logic.network.DeviceDiscoveryServer(application)
    private var tlsServer: com.jiaweiya.camlinks.logic.network.TlsCamServer? = null

    init {
        discoveryServer.start()
        startTlsServer()
    }

    private fun startTlsServer() {
        try {
            tlsServer = com.jiaweiya.camlinks.logic.network.TlsCamServer { action, json ->
                when (action) {
                    "set_torch" -> {
                        val enabled = json.optBoolean("bool_value", false)
                        cameraManager.setTorch(enabled)
                        _uiState.update { it.copy(isTorchOn = enabled) }
                    }
                    "set_exposure" -> {
                        val ev = json.optInt("int_value", 0)
                        cameraManager.setExposure(ev)
                        _uiState.update { it.copy(exposureCompensation = ev) }
                    }
                }
            }
            tlsServer?.start()
            android.util.Log.i("CamLinks", "TlsCamServer successfully started on port 8443")
        } catch (e: Throwable) {
            android.util.Log.e("CamLinks", "Failed to start TlsCamServer: " + e.message, e)
        }
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.startCamera(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            lensFacing = _uiState.value.lensFacing
        ) { minZoom, maxZoom, minExp, maxExp ->
            _uiState.update {
                it.copy(
                    minZoomRatio = minZoom,
                    maxZoomRatio = maxZoom,
                    minExposureCompensation = minExp,
                    maxExposureCompensation = maxExp
                )
            }
        }
    }

    fun toggleCameraFacing(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val nextFacing = if (_uiState.value.lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _uiState.update { it.copy(lensFacing = nextFacing, isTorchOn = false) }
        bindCamera(lifecycleOwner, previewView)
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
        discoveryServer.stop()
        try { tlsServer?.stop() } catch (_: Exception) {}
        cameraManager.release()
    }
}