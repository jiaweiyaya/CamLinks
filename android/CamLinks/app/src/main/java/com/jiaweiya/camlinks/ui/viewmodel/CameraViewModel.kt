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
        cameraManager.release()
    }
}