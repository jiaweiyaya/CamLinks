package com.jiaweiya.camlinks.logic.model

import androidx.camera.core.CameraSelector

data class CameraUiState(
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val isTorchOn: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 5.0f,
    val exposureCompensation: Int = 0,
    val minExposureCompensation: Int = -4,
    val maxExposureCompensation: Int = 4,
    val isStreaming: Boolean = false
)