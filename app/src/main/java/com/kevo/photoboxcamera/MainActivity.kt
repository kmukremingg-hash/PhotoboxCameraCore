package com.kevo.photoboxcamera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            preview = findViewById(R.id.preview)
            status = findViewById(R.id.status)
            manager = getSystemService(CameraManager::class.java)

            status.text = "Photobox Camera Diagnose 0.3\n\nApp-Start: OK\n\nNoch keine Kamera geöffnet.\nTippe unten auf 'Kamera testen'."
            findViewById<Button>(R.id.scan).apply {
                text = "Kamera testen"
                setOnClickListener { startCameraTest() }
            }
        } catch (t: Throwable) {
            setContentView(TextView(this).apply {
                text = "Photobox Diagnose 0.3\n\nSTARTFEHLER:\n${t.javaClass.name}\n${t.message ?: "ohne Meldung"}"
                textSize = 18f
                setPadding(32, 64, 32, 32)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (bgThread == null) {
            bgThread = HandlerThread("PhotoboxCameraThread").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
        }
    }

    override fun onPause() {
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onPause()
    }

    private fun startCameraTest() {
        try {
            status.text = "Schritt 1: Kamera-Test gestartet …"
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                status.append("\nSchritt 2: Kamera-Berechtigung wird angefragt …")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
                return
            }
            openBackCamera()
        } catch (t: Throwable) {
            showError("startCameraTest", t)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                status.text = "Kamera-Berechtigung: OK"
                openBackCamera()
            } else {
                status.text = "Kamera-Berechtigung: ABGELEHNT\nBitte unter Einstellungen > Apps > Photobox Camera Test > Berechtigungen die Kamera erlauben."
            }
        }
    }

    private fun openBackCamera() {
        try {
            status.text = "Schritt 3: Camera2 verfügbar. Suche Rückkamera …"
            val ids = manager.cameraIdList
            status.append("\nGefundene Camera IDs: ${ids.joinToString()}")
            val selected = ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (selected == null) {
                status.append("\nFEHLER: Keine Rückkamera über Camera2 gefunden.")
                return
            }

            val c = manager.getCameraCharacteristics(selected)
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val zoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            status.append("\nRückkamera: $selected")
            status.append("\nISO: ${iso ?: "nicht gemeldet"}")
            status.append("\nBelichtungszeit: ${exposure ?: "nicht gemeldet"}")
            status.append("\nMax. Zoom: ${zoom ?: "nicht gemeldet"}")
            status.append("\nSchritt 4: Öffne Kamera …")

            manager.openCamera(selected, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    runOnUiThread { status.append("\nKamera geöffnet: OK\nSchritt 5: Starte Livebild …") }
                    startPreview(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    runOnUiThread { status.append("\nKamera getrennt.") }
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    runOnUiThread { status.append("\nCAMERA2 FEHLERCODE: $error") }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            showError("openBackCamera", t)
        }
    }

    private fun startPreview(device: CameraDevice) {
        try {
            if (!preview.isAvailable) {
                preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { startPreview(device) }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                return
            }
            val texture = preview.surfaceTexture ?: error("SurfaceTexture ist null")
            val surface = Surface(texture)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(request.build(), null, bgHandler)
                        runOnUiThread { status.append("\nLivebild: OK") }
                    } catch (t: Throwable) {
                        showError("setRepeatingRequest", t)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { status.append("\nLivebild-Konfiguration: FEHLER") }
                }
            }, bgHandler)
        } catch (t: Throwable) {
            showError("startPreview", t)
        }
    }

    private fun showError(where: String, t: Throwable) {
        runOnUiThread {
            status.text = "FEHLER bei $where\n${t.javaClass.name}\n${t.message ?: "ohne Meldung"}\n\n${t.stackTrace.take(8).joinToString("\n")}"
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { camera?.close() } catch (_: Throwable) {}
        camera = null
    }
}
