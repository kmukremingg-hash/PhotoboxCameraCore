package com.kevo.photoboxcamera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
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
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    @Volatile private var openingCamera = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        manager = getSystemService(CameraManager::class.java)

        findViewById<Button>(R.id.scan).setOnClickListener { safeInspectAndOpen() }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                safeInspectAndOpen()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        status.text = "Photobox Camera Test 0.2\nKamera wird vorbereitet …"
        ensureCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        if (bgThread == null) {
            bgThread = HandlerThread("CameraThread").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
        }
        if (preview.isAvailable) safeInspectAndOpen()
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onPause()
    }

    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (preview.isAvailable) safeInspectAndOpen()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                status.text = "Kameraberechtigung erteilt. Kamera wird geöffnet …"
                safeInspectAndOpen()
            } else {
                status.text = "Kameraberechtigung wurde nicht erteilt. Bitte in Android unter App-Info > Berechtigungen > Kamera erlauben."
            }
        }
    }

    private fun safeInspectAndOpen() {
        if (!::preview.isInitialized || !::manager.isInitialized) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!preview.isAvailable || openingCamera || camera != null) return

        openingCamera = true
        try {
            val selected = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            if (selected == null) {
                openingCamera = false
                status.text = "Keine rückseitige Kamera gefunden."
                return
            }

            val characteristics = manager.getCameraCharacteristics(selected)
            status.text = capabilityReport(selected, characteristics)

            manager.openCamera(selected, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    openingCamera = false
                    camera = device
                    createPreview(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    openingCamera = false
                    device.close()
                    if (camera === device) camera = null
                    runOnUiThread { status.append("\nKamera wurde getrennt.") }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    openingCamera = false
                    device.close()
                    if (camera === device) camera = null
                    runOnUiThread { status.append("\nKamerafehler: $error") }
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            openingCamera = false
            status.text = "Kamera konnte wegen fehlender Berechtigung nicht geöffnet werden."
        } catch (e: CameraAccessException) {
            openingCamera = false
            status.text = "Camera2-Fehler: ${e.reason} – ${e.message ?: "unbekannt"}"
        } catch (e: Exception) {
            openingCamera = false
            status.text = "Startfehler: ${e.javaClass.simpleName}: ${e.message ?: "unbekannt"}"
        }
    }

    private fun createPreview(device: CameraDevice) {
        try {
            val texture = preview.surfaceTexture ?: return
            val characteristics = manager.getCameraCharacteristics(device.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val chosen = sizes?.filter { it.width <= 1920 && it.height <= 1080 }
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: sizes?.firstOrNull()

            if (chosen != null) texture.setDefaultBufferSize(chosen.width, chosen.height)

            val surface = Surface(texture)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        s.setRepeatingRequest(request.build(), null, bgHandler)
                    } catch (e: Exception) {
                        runOnUiThread { status.append("\nLivebild-Fehler: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { status.append("\nLivebild konnte nicht gestartet werden.") }
                }
            }, bgHandler)
        } catch (e: Exception) {
            runOnUiThread { status.append("\nVorschaufehler: ${e.javaClass.simpleName}: ${e.message ?: "unbekannt"}") }
        }
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
            appendLine("Photobox Camera Test 0.2")
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
        openingCamera = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
    }
}
