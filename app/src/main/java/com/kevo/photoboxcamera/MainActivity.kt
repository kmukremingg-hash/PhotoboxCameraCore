package com.kevo.photoboxcamera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var manager: CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        manager = getSystemService(CameraManager::class.java)

        findViewById<Button>(R.id.scan).setOnClickListener { inspectAndOpen() }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = inspectAndOpen()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("CameraThread").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        if (preview.isAvailable) inspectAndOpen()
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) inspectAndOpen()
    }

    private fun inspectAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || !preview.isAvailable) return

        closeCamera()
        val selected = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        cameraId = selected
        val c = manager.getCameraCharacteristics(selected)
        status.text = capabilityReport(selected, c)

        manager.openCamera(selected, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createPreview(device)
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) {
                status.append("\nKamerafehler: $error")
                device.close()
            }
        }, bgHandler)
    }

    private fun createPreview(device: CameraDevice) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(1920, 1080)
        val surface = Surface(texture)
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                s.setRepeatingRequest(request.build(), null, bgHandler)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                status.append("\nLivebild konnte nicht gestartet werden.")
            }
        }, bgHandler)
    }

    private fun capabilityReport(id: String, c: CameraCharacteristics): String {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
        val logicalMulti = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps

        val iso: Range<Int>? = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure: Range<Long>? = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString() ?: "?"
        val awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.joinToString() ?: "?"
        val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

        return buildString {
            appendLine("Photobox Camera Test 0.1")
            appendLine("Camera ID: $id | Hardware-Level: $level")
            appendLine("MANUAL_SENSOR: ${yesNo(manualSensor)}")
            appendLine("MANUAL_POST_PROCESSING: ${yesNo(manualPost)}")
            appendLine("RAW: ${yesNo(raw)} | Logical Multi-Camera: ${yesNo(logicalMulti)}")
            appendLine("ISO-Bereich: ${iso ?: "nicht gemeldet"}")
            appendLine("Belichtungszeit ns: ${exposure ?: "nicht gemeldet"}")
            appendLine("Min. Fokusdistanz (Dioptrien): ${minFocus ?: "nicht gemeldet"}")
            appendLine("Max. Digitalzoom: ${maxZoom ?: "nicht gemeldet"}x")
            appendLine("AF-Modi: $afModes")
            appendLine("AWB-Modi: $awbModes")
            append("\nWenn MANUAL_SENSOR = JA, können ISO und Belichtungszeit später direkt geregelt werden.")
        }
    }

    private fun yesNo(value: Boolean) = if (value) "JA" else "NEIN"

    private fun closeCamera() {
        session?.close(); session = null
        camera?.close(); camera = null
    }
}
