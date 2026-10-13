package com.jiaweiya.camlinks.logic.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(private val context: Context) {

    private val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encodingThread: Thread? = null
    private val isEncoding = AtomicBoolean(false)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var targetWidth: Int = 1280
    var targetHeight: Int = 720
    var targetFps: Int = 60
    var maxBitrateKbps: Int = 4000
    private var basePhysicalFocalLength: Float = 26f
    private var currentZoomRatio: Float = 1.0f
    private var currentLensFacing: Int = 1 // 1: 后置, 0: 前置

    var onFrameAvailable: ((ByteArray) -> Unit)? = null

    // 实时物理参数看板
    var currentAperture: String = "f/1.8"
    var currentShutter: String = "1/100s"
    var currentIso: String = "ISO 100"
    var currentFocalLength: String = "26mm"

    init {
        val thread = HandlerThread("CameraBackground").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    @SuppressLint("MissingPermission")
    fun startCamera(
        previewSurface: Surface,
        lensFacing: Int,
        onInitialized: (minZoom: Float, maxZoom: Float, minExp: Int, maxExp: Int) -> Unit
    ) {
        stopCamera()
        currentLensFacing = lensFacing
        val cameraId = getCameraId(lensFacing) ?: return
        val characteristics = camera2Manager.getCameraCharacteristics(cameraId)

        val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val expRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        onInitialized(
            zoomRange?.lower ?: 1.0f,
            zoomRange?.upper ?: 5.0f,
            expRange?.lower ?: -4,
            expRange?.upper ?: 4
        )

        initMediaCodec()

        camera2Manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val surfaces = listOfNotNull(previewSurface, encoderSurface)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val isHighSpeed = targetFps > 30 && map?.getHighSpeedVideoSizes()?.any { it.width == targetWidth && it.height == targetHeight } == true

                if (isHighSpeed) {
                    val highSpeedRanges = map?.getHighSpeedVideoFpsRangesFor(android.util.Size(targetWidth, targetHeight)) ?: emptyArray()
                    val matchedRange = highSpeedRanges.filter { it.upper == targetFps }.maxByOrNull { it.lower }
                        ?: highSpeedRanges.filter { it.upper >= targetFps }.minByOrNull { it.upper }
                        ?: highSpeedRanges.firstOrNull()
                        ?: android.util.Range(targetFps, targetFps)

                    camera.createConstrainedHighSpeedCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
                            try {
                                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                builder.addTarget(previewSurface)
                                encoderSurface?.let { builder.addTarget(it) }

                                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedRange)

                                previewRequestBuilder = builder
                                val requestList = highSpeedSession.createHighSpeedRequestList(builder.build())
                                session.setRepeatingBurst(requestList, captureCallback, backgroundHandler)
                                startEncodingLoop()
                                requestKeyFrame()
                            } catch (_: Exception) {}
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, backgroundHandler)
                } else {
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                builder.addTarget(previewSurface)
                                encoderSurface?.let { builder.addTarget(it) }

                                val nativeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                                val matchedRange = nativeRanges.filter { it.upper == targetFps }.maxByOrNull { it.lower }
                                    ?: nativeRanges.filter { it.upper <= targetFps }.maxByOrNull { it.upper }
                                    ?: android.util.Range(targetFps, targetFps)

                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, matchedRange)

                                previewRequestBuilder = builder
                                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                                startEncodingLoop()
                                requestKeyFrame()
                            } catch (_: Exception) {}
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, backgroundHandler)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
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
    }

    private fun initMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, maxBitrateKbps * 1024)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                try {
                    setInteger("max-bitrate", maxBitrateKbps * 1024)
                    // 当设置超低码率时，动态拉高最小 QP 阈值，强制硬件编码器更深层压缩，从源头上把码率压死
                    val minQp = when {
                        maxBitrateKbps <= 1000 -> 32
                        maxBitrateKbps <= 2500 -> 26
                        else -> 20
                    }
                    setInteger(MediaFormat.KEY_VIDEO_QP_MIN, minQp)
                    setInteger(MediaFormat.KEY_VIDEO_QP_MAX, 51)
                    setInteger("video-qp-min", minQp)
                    setInteger("video-qp-max", 51)
                } catch (_: Exception) {}
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
        } catch (_: Exception) {}
    }

    private var cachedConfig: ByteArray? = null
    private var isAwaitingFirstKeyFrame = true

    private fun startEncodingLoop() {
        isEncoding.set(true)
        isAwaitingFirstKeyFrame = true

        encodingThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val codec = mediaCodec ?: return@Thread
            while (isEncoding.get()) {
                try {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val outData = ByteArray(bufferInfo.size)
                            outputBuffer.get(outData)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isConfig) {
                                cachedConfig = outData.copyOf()
                                // 刚拿到配置头，立即催促硬件产出第一帧 IDR 图像帧
                                requestKeyFrame()
                            } else if (isKeyFrame) {
                                isAwaitingFirstKeyFrame = false
                                val config = cachedConfig
                                val payload = if (config != null && !hasSpsHeader(outData)) {
                                    config + outData
                                } else {
                                    outData
                                }
                                onFrameAvailable?.invoke(payload)
                            } else {
                                // 如果切换后还在等第一个完整关键帧，持续催促硬件编码器立即输出 IDR，不让客户端等待
                                if (isAwaitingFirstKeyFrame) {
                                    requestKeyFrame()
                                }
                                // 正常下发所有 P 帧，完整保留 H.264 预测参考链，画面绝对不花
                                onFrameAvailable?.invoke(outData)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    private fun hasSpsHeader(data: ByteArray): Boolean {
        if (data.size < 5) return false
        return (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() && (data[4].toInt() and 0x1F) == 7)
    }

    fun requestKeyFrame() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (_: Exception) {}
    }

    private fun applyRepeatingRequest() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            if (session is CameraConstrainedHighSpeedCaptureSession) {
                val list = session.createHighSpeedRequestList(builder.build())
                session.setRepeatingBurst(list, captureCallback, backgroundHandler)
            } else {
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            }
        } catch (_: Exception) {}
    }

    fun setTorch(enable: Boolean) {
        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.FLASH_MODE, if (enable) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            applyRepeatingRequest()
        }
    }

    fun setZoom(ratio: Float) {
        currentZoomRatio = ratio
        val effectiveFocal = (basePhysicalFocalLength * ratio).toInt()
        currentFocalLength = "${effectiveFocal}mm"
        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
            applyRepeatingRequest()
        }
    }

    fun setExposure(index: Int) {
        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            applyRepeatingRequest()
        }
    }

    fun setBitrate(bitrateKbps: Int) {
        maxBitrateKbps = bitrateKbps
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateKbps * 1024)
                val minQp = when {
                    bitrateKbps <= 1000 -> 32
                    bitrateKbps <= 2500 -> 26
                    else -> 20
                }
                putInt(MediaFormat.KEY_VIDEO_QP_MIN, minQp)
                putInt(MediaFormat.KEY_VIDEO_QP_MAX, 51)
                putInt("video-qp-min", minQp)
                putInt("video-qp-max", 51)
            }
            mediaCodec?.setParameters(params)
        } catch (_: Exception) {}
    }

    fun focusAt(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        val cameraId = getCameraId(currentLensFacing) ?: return
        val characteristics = camera2Manager.getCameraCharacteristics(cameraId)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val halfSize = 150
        val centerX = ((x / viewWidth) * sensorRect.width()).toInt().coerceIn(0, sensorRect.width())
        val centerY = ((y / viewHeight) * sensorRect.height()).toInt().coerceIn(0, sensorRect.height())

        val focusRect = Rect(
            (centerX - halfSize).coerceAtLeast(0),
            (centerY - halfSize).coerceAtLeast(0),
            (centerX + halfSize).coerceAtMost(sensorRect.width()),
            (centerY + halfSize).coerceAtMost(sensorRect.height())
        )

        val meteringRectangle = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))
            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (_: Exception) {}
    }

    private fun getCameraId(lensFacing: Int): String? {
        val targetFacing = if (lensFacing == 0) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in camera2Manager.cameraIdList) {
            val chars = camera2Manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                return id
            }
        }
        return camera2Manager.cameraIdList.firstOrNull()
    }

    fun getCameraCapabilities(lensFacing: Int): Pair<List<String>, List<Int>> {
        val targetFacing = if (lensFacing == 0) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        var resStrings = mutableListOf<String>()
        val fpsSet = sortedSetOf<Int>(compareByDescending { it })

        try {
            for (id in camera2Manager.cameraIdList) {
                val chars = camera2Manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val outputSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()

                    val sortedSizes = outputSizes
                        .filter { size ->
                            if (size.width < 640 || size.height < 480) return@filter false
                            val ratio = size.width.toFloat() / size.height.toFloat()
                            val is16_9 = kotlin.math.abs(ratio - 16f / 9f) < 0.05f
                            val is4_3 = kotlin.math.abs(ratio - 4f / 3f) < 0.05f
                            is16_9 || is4_3
                        }
                        .distinctBy { "${it.width}x${it.height}" }
                        .sortedByDescending { it.width.toLong() * it.height.toLong() }

                    resStrings = sortedSizes.map { "${it.width}x${it.height}" }.toMutableList()

                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach { range ->
                        if (range.upper in 15..30) fpsSet.add(range.upper)
                    }

                    map?.highSpeedVideoFpsRanges?.forEach { range ->
                        if (range.upper in 15..480) fpsSet.add(range.upper)
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

    fun stopCamera() {
        isEncoding.set(false)
        try { encodingThread?.join(500) } catch (_: Exception) {}
        encodingThread = null
        cachedConfig = null

        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null

        encoderSurface?.release()
        encoderSurface = null
    }

    fun release() {
        stopCamera()
        backgroundThread?.quitSafely()
    }
}