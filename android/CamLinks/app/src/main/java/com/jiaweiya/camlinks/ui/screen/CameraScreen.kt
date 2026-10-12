package com.jiaweiya.camlinks.ui.screen

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiaweiya.camlinks.ui.viewmodel.CameraViewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        viewModel.triggerFocus(
                            offset.x,
                            offset.y,
                            size.width.toFloat(),
                            size.height.toFloat()
                        )
                    }
                }
        )

        androidx.compose.runtime.LaunchedEffect(uiState.lensFacing) {
            viewModel.bindCamera(lifecycleOwner, previewView)
        }

        // 底部控制面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 曝光补偿调节滑块
            if (uiState.minExposureCompensation < uiState.maxExposureCompensation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "EV: ${uiState.exposureCompensation}", color = Color.White)
                    Slider(
                        value = uiState.exposureCompensation.toFloat(),
                        onValueChange = { viewModel.updateExposure(it.toInt()) },
                        valueRange = uiState.minExposureCompensation.toFloat()..uiState.maxExposureCompensation.toFloat(),
                        steps = uiState.maxExposureCompensation - uiState.minExposureCompensation - 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
            }

            // 功能操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 闪光灯切换
                IconButton(onClick = { viewModel.toggleTorch() }) {
                    Icon(
                        imageVector = if (uiState.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = Color.White
                    )
                }

                // 翻转前后摄像头
                IconButton(onClick = { viewModel.toggleCameraFacing(lifecycleOwner, previewView) }) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }
        }
    }
}