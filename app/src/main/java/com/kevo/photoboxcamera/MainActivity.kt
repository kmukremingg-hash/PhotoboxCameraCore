package com.kevo.photoboxcamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var remotePreview: ImageView
    private lateinit var remotePanel: View
    private lateinit var hostAddress: EditText
    private lateinit var status: TextView
    private lateinit var manager: CameraManager
    private lateinit var isoBar: SeekBar
    private lateinit var exposureBar: SeekBar
    private lateinit var focusBar: SeekBar
    private lateinit var zoomBar: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var focusLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var modeButton: Button
    private lateinit var wbButton: Button
    private lateinit var shutterButton: Button
    private lateinit var scanButton: Button
    private lateinit var autofocusButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button

    private val prefs by lazy { getSharedPreferences("photobox_settings_v08", MODE_PRIVATE) }

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var characteristics: CameraCharacteristics? = null
    private var sensorRect: Rect? = null
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var minFocusDistance = 0f
    private var maxZoom = 1f
    private var manualExposure = false
    private var manualFocus = false
    private var currentIso = 100
    private var currentExposureNs = 10_000_000L
    private var currentFocus = 0f
    private var currentZoom = 1f
    private var wbIndex = 0
    private var hostMode = false
    private var remoteMode = false
    @Volatile private var remoteRunning = false
    private var remoteBase = ""
    private var server: RemoteServer? = null
    private var suppressHistory = false

    private val captureLock = Any()
    @Volatile private var transferCaptureLatch: CountDownLatch? = null
    @Volatile private var transferCaptureBytes: ByteArray? = null

    private data class SettingsState(
        val manualExposure: Boolean,
        val isoProgress: Int,
        val exposureProgress: Int,
        val focusProgress: Int,
        val zoomProgress: Int,
        val wbIndex: Int
    )

    private val undoStack = mutableListOf<SettingsState>()
    private val redoStack = mutableListOf<SettingsState>()

    private val wbModes = intArrayOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO,
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
    )
    private val wbNames = arrayOf("AUTO", "TAGESLICHT", "BEWÖLKT", "LEUCHTSTOFF", "GLÜHLAMPE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            preview = findViewById(R.id.preview)
            remotePreview = findViewById(R.id.remotePreview)
            remotePanel = findViewById(R.id.remotePanel)
            hostAddress = findViewById(R.id.hostAddress)
            status = findViewById(R.id.status)
            manager = getSystemService(CameraManager::class.java)
            isoBar = findViewById(R.id.iso)
            exposureBar = findViewById(R.id.exposure)
            focusBar = findViewById(R.id.focus)
            zoomBar = findViewById(R.id.zoom)
            isoLabel = findViewById(R.id.isoLabel)
            exposureLabel = findViewById(R.id.exposureLabel)
            focusLabel = findViewById(R.id.focusLabel)
            zoomLabel = findViewById(R.id.zoomLabel)
            modeButton = findViewById(R.id.mode)
            wbButton = findViewById(R.id.wb)
            shutterButton = findViewById(R.id.shutter)
            scanButton = findViewById(R.id.scan)
            autofocusButton = findViewById(R.id.autofocus)
            undoButton = findViewById(R.id.undoSettings)
            redoButton = findViewById(R.id.redoSettings)

            setupSeekBars()
            loadPersistentSettings()
            initHistory()
            setControlsEnabled(false)
            preview.visibility = View.GONE

            findViewById<Button>(R.id.hostMode).setOnClickListener { selectHostMode() }
            findViewById<Button>(R.id.remoteMode).setOnClickListener { selectRemoteMode() }
            findViewById<Button>(R.id.connectRemote).setOnClickListener { connectRemote() }
            scanButton.setOnClickListener { startCamera() }

            modeButton.setOnClickListener {
                manualExposure = !manualExposure
                updateModeButton()
                persistSettings()
                commitHistory()
                if (remoteMode) sendRemote("/control?manual=${if (manualExposure) 1 else 0}") else applyPreviewSettings()
            }
            wbButton.setOnClickListener {
                wbIndex = (wbIndex + 1) % wbModes.size
                updateWbButton()
                persistSettings()
                commitHistory()
                if (remoteMode) sendRemote("/control?wb=$wbIndex") else applyPreviewSettings()
            }
            autofocusButton.setOnClickListener { requestAutofocusCenter() }
            undoButton.setOnClickListener { undoSettings() }
            redoButton.setOnClickListener { redoSettings() }
            shutterButton.setOnClickListener {
                if (remoteMode) captureRemoteToTablet()
                else status.text = "Foto wird ausschließlich auf dem Tablet gespeichert. Bitte dort auslösen."
            }

            preview.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP && hostMode) {
                    focusAtNormalized(event.x / v.width.coerceAtLeast(1), event.y / v.height.coerceAtLeast(1))
                    true
                } else false
            }
            remotePreview.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP && remoteMode && remoteBase.isNotBlank()) {
                    setAutoFocusState(recordHistory = true)
                    val nx = ((event.x / v.width.coerceAtLeast(1)) * 1000f).toInt().coerceIn(0, 1000)
                    val ny = ((event.y / v.height.coerceAtLeast(1)) * 1000f).toInt().coerceIn(0, 1000)
                    sendRemote("/focus?x=$nx&y=$ny")
                    status.text = "Fokuspunkt an S25 gesendet."
                    true
                } else false
            }
        } catch (t: Throwable) {
            setContentView(TextView(this).apply {
                text = "Photobox 0.8 STARTFEHLER\n${t.javaClass.name}\n${t.message ?: "ohne Meldung"}"
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

    override fun onDestroy() {
        remoteRunning = false
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun selectHostMode() {
        hostMode = true
        remoteMode = false
        remoteRunning = false
        prefs.edit().putString("lastMode", "host").apply()
        remotePanel.visibility = View.GONE
        remotePreview.visibility = View.GONE
        preview.visibility = View.VISIBLE
        scanButton.visibility = View.VISIBLE
        setControlsEnabled(true)
        val ip = localIpv4() ?: "IP nicht gefunden"
        server?.stop()
        server = RemoteServer(8765).also { it.start() }
        status.text = "S25 HOST-MODUS\nWLAN-Adresse: $ip:8765\nFotos werden NICHT auf dem S25 gespeichert."
        refreshLabels()
    }

    private fun selectRemoteMode() {
        hostMode = false
        remoteMode = true
        prefs.edit().putString("lastMode", "remote").apply()
        closeCamera()
        server?.stop(); server = null
        preview.visibility = View.GONE
        scanButton.visibility = View.GONE
        remotePanel.visibility = View.VISIBLE
        remotePreview.visibility = View.VISIBLE
        setControlsEnabled(true)
        status.text = "TABLET REMOTE-MODUS\nIP-Adresse vom S25 eingeben und verbinden.\nFotos werden nur auf diesem Tablet gespeichert."
        refreshLabels()
    }

    private fun connectRemote() {
        val ip = hostAddress.text.toString().trim().removePrefix("http://").removeSuffix("/")
        if (ip.isBlank()) {
            status.text = "Bitte die auf dem S25 angezeigte IP-Adresse eingeben."
            return
        }
        remoteBase = "http://$ip${if (ip.contains(':')) "" else ":8765"}"
        remoteRunning = true
        status.text = "Verbinde mit $remoteBase …"
        startRemotePreviewLoop()
        sendRemote("/ping") { ok ->
            runOnUiThread {
                if (ok) {
                    status.text = "Verbunden mit S25: OK\nSpeicherziel: Tablet / Pictures/Collectooow Photobox/Rohbilder"
                    sendAllRemoteSettings()
                } else status.text = "Verbindung fehlgeschlagen: $remoteBase"
            }
        }
    }

    private fun startRemotePreviewLoop() {
        thread(name = "PhotoboxRemotePreview", isDaemon = true) {
            while (remoteRunning && remoteMode) {
                try {
                    val conn = URL("$remoteBase/preview.jpg?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 1200
                    conn.readTimeout = 1800
                    conn.useCaches = false
                    if (conn.responseCode == 200) {
                        val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        if (bmp != null) runOnUiThread { remotePreview.setImageBitmap(bmp) }
                    }
                    conn.disconnect()
                } catch (_: Throwable) {}
                try { Thread.sleep(450) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun sendRemote(path: String, callback: ((Boolean) -> Unit)? = null) {
        if (!remoteMode || remoteBase.isBlank()) return
        thread(isDaemon = true) {
            var ok = false
            try {
                val conn = URL(remoteBase + path).openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 2500
                ok = conn.responseCode in 200..299
                try { conn.inputStream?.close() } catch (_: Throwable) {}
                conn.disconnect()
            } catch (_: Throwable) {}
            callback?.invoke(ok)
        }
    }

    private fun sendAllRemoteSettings() {
        if (!remoteMode || remoteBase.isBlank()) return
        sendRemote("/control?manual=${if (manualExposure) 1 else 0}&iso=${isoBar.progress}&exposure=${exposureBar.progress}&focus=${focusBar.progress}&zoom=${zoomBar.progress}&wb=$wbIndex")
    }

    private fun setupSeekBars() {
        isoBar.setOnSeekBarChangeListener(simpleSeek({ onIsoProgress(it, true) }, { commitHistory() }))
        exposureBar.setOnSeekBarChangeListener(simpleSeek({ onExposureProgress(it, true) }, { commitHistory() }))
        focusBar.setOnSeekBarChangeListener(simpleSeek({ onFocusProgress(it, true) }, { commitHistory() }))
        zoomBar.setOnSeekBarChangeListener(simpleSeek({ onZoomProgress(it, true) }, { commitHistory() }))
    }

    private fun simpleSeek(onChange: (Int) -> Unit, onStop: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) { onStop() }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf<View>(isoBar, exposureBar, focusBar, zoomBar, modeButton, wbButton, shutterButton, autofocusButton).forEach { it.isEnabled = enabled }
        updateUndoRedoButtons()
    }

    private fun onIsoProgress(p: Int, send: Boolean) {
        if (remoteMode) {
            isoLabel.text = "ISO-Regler: $p"
            if (send) sendRemote("/control?iso=$p")
        } else {
            isoRange?.let { r ->
                currentIso = r.lower + ((r.upper - r.lower) * p.coerceIn(0, 1000) / 1000f).toInt()
                isoLabel.text = "ISO: $currentIso"
                if (manualExposure) applyPreviewSettings()
            } ?: run { isoLabel.text = "ISO-Regler: $p" }
        }
        persistSettings()
    }

    private fun onExposureProgress(p: Int, send: Boolean) {
        if (remoteMode) {
            exposureLabel.text = "Belichtungs-Regler: $p"
            if (send) sendRemote("/control?exposure=$p")
        } else {
            exposureRange?.let { r ->
                currentExposureNs = logMap(p.coerceIn(0, 1000), r.lower, r.upper)
                exposureLabel.text = "Belichtungszeit: ${formatExposure(currentExposureNs)}"
                if (manualExposure) applyPreviewSettings()
            } ?: run { exposureLabel.text = "Belichtungs-Regler: $p" }
        }
        persistSettings()
    }

    private fun onFocusProgress(p: Int, send: Boolean) {
        manualFocus = p > 0
        if (remoteMode) {
            focusLabel.text = if (p == 0) "Fokus: AUTO" else "Fokus-Regler: $p"
            if (send) sendRemote("/control?focus=$p")
        } else {
            if (minFocusDistance > 0f) {
                currentFocus = minFocusDistance * p.coerceIn(0, 1000) / 1000f
                focusLabel.text = if (manualFocus) "Fokus manuell: ${"%.2f".format(currentFocus)} dpt" else "Fokus: AUTO"
                applyPreviewSettings()
            } else focusLabel.text = if (manualFocus) "Fokus-Regler: $p" else "Fokus: AUTO"
        }
        persistSettings()
    }

    private fun onZoomProgress(p: Int, send: Boolean) {
        if (remoteMode) {
            zoomLabel.text = "Zoom-Regler: $p"
            if (send) sendRemote("/control?zoom=$p")
        } else {
            currentZoom = 1f + (maxZoom - 1f) * p.coerceIn(0, 1000) / 1000f
            zoomLabel.text = "Zoom: ${"%.1f".format(currentZoom)}x"
            applyPreviewSettings()
        }
        persistSettings()
    }

    private fun currentSettingsState() = SettingsState(manualExposure, isoBar.progress, exposureBar.progress, focusBar.progress, zoomBar.progress, wbIndex)

    private fun initHistory() {
        undoStack.clear(); redoStack.clear(); undoStack.add(currentSettingsState()); updateUndoRedoButtons()
    }

    private fun commitHistory() {
        if (suppressHistory) return
        val now = currentSettingsState()
        if (undoStack.lastOrNull() != now) {
            undoStack.add(now)
            if (undoStack.size > 50) undoStack.removeAt(0)
            redoStack.clear(); updateUndoRedoButtons()
        }
    }

    private fun undoSettings() {
        if (undoStack.size <= 1) return
        redoStack.add(undoStack.removeAt(undoStack.lastIndex))
        applySettingsState(undoStack.last()); updateUndoRedoButtons()
    }

    private fun redoSettings() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(next); applySettingsState(next); updateUndoRedoButtons()
    }

    private fun applySettingsState(s: SettingsState) {
        suppressHistory = true
        manualExposure = s.manualExposure
        wbIndex = s.wbIndex.coerceIn(0, wbModes.lastIndex)
        isoBar.progress = s.isoProgress.coerceIn(0, 1000)
        exposureBar.progress = s.exposureProgress.coerceIn(0, 1000)
        focusBar.progress = s.focusProgress.coerceIn(0, 1000)
        zoomBar.progress = s.zoomProgress.coerceIn(0, 1000)
        manualFocus = focusBar.progress > 0
        updateModeButton(); updateWbButton(); refreshLabels(); persistSettings()
        suppressHistory = false
        if (remoteMode) sendAllRemoteSettings() else applyPreviewSettings()
    }

    private fun updateUndoRedoButtons() {
        if (::undoButton.isInitialized) undoButton.isEnabled = undoStack.size > 1
        if (::redoButton.isInitialized) redoButton.isEnabled = redoStack.isNotEmpty()
    }

    private fun loadPersistentSettings() {
        suppressHistory = true
        manualExposure = prefs.getBoolean("manualExposure", false)
        wbIndex = prefs.getInt("wbIndex", 0).coerceIn(0, wbModes.lastIndex)
        isoBar.progress = prefs.getInt("isoProgress", 100).coerceIn(0, 1000)
        exposureBar.progress = prefs.getInt("exposureProgress", 500).coerceIn(0, 1000)
        focusBar.progress = prefs.getInt("focusProgress", 0).coerceIn(0, 1000)
        zoomBar.progress = prefs.getInt("zoomProgress", 0).coerceIn(0, 1000)
        manualFocus = focusBar.progress > 0
        updateModeButton(); updateWbButton(); refreshLabels()
        suppressHistory = false
    }

    private fun persistSettings() {
        if (!::isoBar.isInitialized) return
        prefs.edit().putBoolean("manualExposure", manualExposure).putInt("wbIndex", wbIndex)
            .putInt("isoProgress", isoBar.progress).putInt("exposureProgress", exposureBar.progress)
            .putInt("focusProgress", focusBar.progress).putInt("zoomProgress", zoomBar.progress).apply()
    }

    private fun refreshLabels() {
        onIsoProgress(isoBar.progress, false); onExposureProgress(exposureBar.progress, false)
        onFocusProgress(focusBar.progress, false); onZoomProgress(zoomBar.progress, false)
        updateModeButton(); updateWbButton()
    }

    private fun updateModeButton() { modeButton.text = if (manualExposure) "Belichtung: MANUELL" else "Belichtung: AUTO" }
    private fun updateWbButton() { wbButton.text = "Weißabgleich: ${wbNames[wbIndex.coerceIn(0, wbNames.lastIndex)]}" }

    private fun setAutoFocusState(recordHistory: Boolean) {
        focusBar.progress = 0; manualFocus = false; focusLabel.text = "Fokus: AUTO"; persistSettings()
        if (recordHistory) commitHistory()
    }

    private fun requestAutofocusCenter() {
        setAutoFocusState(true)
        if (remoteMode) {
            sendRemote("/focus?x=500&y=500"); status.text = "Autofokus am S25 ausgelöst."
        } else {
            focusAtNormalized(0.5f, 0.5f); status.text = "Autofokus ausgelöst."
        }
    }

    private fun startCamera() {
        if (!hostMode) selectHostMode()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100); return
        }
        openBackCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openBackCamera()
    }

    private fun openBackCamera() {
        try {
            closeCamera()
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: error("Keine Rückkamera gefunden")
            val c = manager.getCameraCharacteristics(id)
            characteristics = c
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            sensorRect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            onIsoProgress(isoBar.progress, false); onExposureProgress(exposureBar.progress, false)
            onFocusProgress(focusBar.progress, false); onZoomProgress(zoomBar.progress, false)
            val ip = localIpv4() ?: "?"
            status.text = "HOST $ip:8765 | Camera2: OK | Kamera $id | Zoom bis ${"%.1f".format(maxZoom)}x\nTelefon speichert keine Fotos dauerhaft."
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; startPreview(device) }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (camera === device) camera = null; runOnUiThread { status.append("\nKamerafehler $error") } }
            }, bgHandler)
        } catch (t: Throwable) { showError("Kamera öffnen", t) }
    }

    private fun startPreview(device: CameraDevice) {
        if (!preview.isAvailable) {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { startPreview(device) }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
            return
        }
        try {
            val surface = Surface(preview.surfaceTexture ?: error("SurfaceTexture fehlt"))
            val jpegSize = chooseJpegSize(characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            imageReader?.close()
            imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ receiveCapturedJpeg(it) }, bgHandler)
            }
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            device.createCaptureSession(listOf(surface, imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; refreshLabels(); applyPreviewSettings(); runOnUiThread { status.append("\nLivebild: OK | WLAN-Remote bereit | Autofokus aktiv") } }
                override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { status.append("\nLivebild: FEHLER") } }
            }, bgHandler)
        } catch (t: Throwable) { showError("Livebild", t) }
    }

    private fun chooseJpegSize(map: StreamConfigurationMap?): Size = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(4000, 3000)

    private fun applyPreviewSettings() {
        val s = session ?: return
        val b = previewBuilder ?: return
        try {
            if (manualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
            } else b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (manualFocus && minFocusDistance > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
            } else b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AWB_MODE, wbModes[wbIndex])
            sensorRect?.let { b.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(it, currentZoom)) }
            s.setRepeatingRequest(b.build(), null, bgHandler)
        } catch (t: Throwable) { showError("Einstellungen", t) }
    }

    private fun focusAtNormalized(nxRaw: Float, nyRaw: Float) {
        val s = session ?: return
        val b = previewBuilder ?: return
        val active = sensorRect ?: return
        try {
            manualFocus = false
            val crop = cropForZoom(active, currentZoom)
            val cx = crop.left + (crop.width() * nxRaw.coerceIn(0f, 1f)).toInt()
            val cy = crop.top + (crop.height() * nyRaw.coerceIn(0f, 1f)).toInt()
            val halfW = (crop.width() * 0.06f).toInt().coerceAtLeast(20)
            val halfH = (crop.height() * 0.06f).toInt().coerceAtLeast(20)
            val rect = Rect((cx-halfW).coerceAtLeast(crop.left), (cy-halfH).coerceAtLeast(crop.top), (cx+halfW).coerceAtMost(crop.right), (cy+halfH).coerceAtMost(crop.bottom))
            val meter = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
            if ((characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meter))
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    try {
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(b.build(), null, bgHandler)
                    } catch (_: Throwable) {}
                }
            }, bgHandler)
            runOnUiThread { focusBar.progress = 0; focusLabel.text = "Fokus: AUTO / Punkt gesetzt"; persistSettings() }
        } catch (t: Throwable) { showError("Autofokus", t) }
    }

    private fun issueStillCapture(): Boolean {
        val device = camera ?: return false
        val reader = imageReader ?: return false
        val s = session ?: return false
        return try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                if (manualExposure) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
                } else set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (manualFocus && minFocusDistance > 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
                } else set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, wbModes[wbIndex])
                sensorRect?.let { set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(it, currentZoom)) }
                set(CaptureRequest.JPEG_ORIENTATION, 90)
            }
            s.capture(b.build(), null, bgHandler)
            runOnUiThread { status.append("\nFoto aufgenommen – Übertragung zum Tablet …") }
            true
        } catch (t: Throwable) { showError("Foto", t); false }
    }

    private fun receiveCapturedJpeg(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
            transferCaptureLatch?.let { transferCaptureBytes = bytes; it.countDown() }
        } finally { image.close() }
    }

    private fun captureJpegForRemote(): ByteArray? = synchronized(captureLock) {
        transferCaptureBytes = null
        val latch = CountDownLatch(1); transferCaptureLatch = latch
        if (!issueStillCapture()) { transferCaptureLatch = null; return@synchronized null }
        val ok = latch.await(12, TimeUnit.SECONDS)
        val data = if (ok) transferCaptureBytes else null
        transferCaptureLatch = null; transferCaptureBytes = null
        data
    }

    private fun captureRemoteToTablet() {
        if (!remoteMode || remoteBase.isBlank()) { status.text = "Bitte zuerst mit dem S25 verbinden."; return }
        shutterButton.isEnabled = false
        status.text = "Foto wird auf dem S25 aufgenommen und direkt zum Tablet übertragen …"
        thread(name = "PhotoboxRemoteCapture", isDaemon = true) {
            try {
                val conn = URL("$remoteBase/capture?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                conn.connectTimeout = 2500; conn.readTimeout = 15000; conn.useCaches = false
                val code = conn.responseCode
                if (code != 200) error("S25 meldet HTTP $code")
                val bytes = conn.inputStream.use { it.readBytes() }; conn.disconnect()
                if (bytes.size < 10_000) error("Bilddaten unvollständig (${bytes.size} Bytes)")
                val name = "Collectooow_Photobox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                saveJpegOnTablet(bytes, name)
                runOnUiThread { status.text = "Gespeichert NUR auf Tablet:\n$name\nPictures/Collectooow Photobox/Rohbilder"; shutterButton.isEnabled = true }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "Fotoübertragung fehlgeschlagen:\n${t.message ?: t.javaClass.simpleName}\nAuf dem Handy wurde keine dauerhafte Fotodatei angelegt."; shutterButton.isEnabled = true }
            }
        }
    }

    private fun saveJpegOnTablet(bytes: ByteArray, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Collectooow Photobox/Rohbilder")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Tablet konnte die Bilddatei nicht anlegen")
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Tablet-Ausgabedatei konnte nicht geöffnet werden")
        } catch (t: Throwable) {
            try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        }
    }

    private fun applyRemoteQuery(query: String) {
        val params = parseQuery(query)
        runOnUiThread {
            params["iso"]?.toIntOrNull()?.let { isoBar.progress = it.coerceIn(0,1000); onIsoProgress(isoBar.progress,false) }
            params["exposure"]?.toIntOrNull()?.let { exposureBar.progress = it.coerceIn(0,1000); onExposureProgress(exposureBar.progress,false) }
            params["focus"]?.toIntOrNull()?.let { focusBar.progress = it.coerceIn(0,1000); onFocusProgress(focusBar.progress,false) }
            params["zoom"]?.toIntOrNull()?.let { zoomBar.progress = it.coerceIn(0,1000); onZoomProgress(zoomBar.progress,false) }
            params["manual"]?.let { manualExposure = it == "1"; updateModeButton(); applyPreviewSettings() }
            params["wb"]?.toIntOrNull()?.let { wbIndex = it.coerceIn(0, wbModes.lastIndex); updateWbButton(); applyPreviewSettings() }
            persistSettings()
        }
    }

    private fun applyRemoteFocus(query: String) {
        val params = parseQuery(query)
        val x = (params["x"]?.toIntOrNull() ?: 500).coerceIn(0,1000) / 1000f
        val y = (params["y"]?.toIntOrNull() ?: 500).coerceIn(0,1000) / 1000f
        runOnUiThread { setAutoFocusState(false); focusAtNormalized(x,y) }
    }

    private fun parseQuery(query: String): Map<String,String> = query.split('&').mapNotNull {
        val p = it.split('=', limit=2)
        if (p.size==2) URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8") else null
    }.toMap()

    private fun snapshotBytes(): ByteArray? {
        val latch = CountDownLatch(1); var bmp: Bitmap? = null
        runOnUiThread { try { if (preview.isAvailable) bmp = preview.bitmap } catch (_: Throwable) {}; latch.countDown() }
        latch.await(1200, TimeUnit.MILLISECONDS)
        val b = bmp ?: return null
        return try { ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG,65,out); out.toByteArray() } } finally { b.recycle() }
    }

    private inner class RemoteServer(private val port: Int) {
        @Volatile private var running=false
        private var socket: ServerSocket?=null
        fun start() {
            running=true
            thread(name="PhotoboxRemoteServer", isDaemon=true) {
                try {
                    socket=ServerSocket(port)
                    while(running) { val client=socket?.accept() ?: break; thread(isDaemon=true){ handle(client) } }
                } catch(t:Throwable) { if(running) runOnUiThread { status.append("\nRemote-Server Fehler: ${t.message}") } }
            }
        }
        fun stop(){ running=false; try{socket?.close()}catch(_:Throwable){}; socket=null }
        private fun handle(client: java.net.Socket) {
            client.use { c ->
                try {
                    c.soTimeout=16000
                    val request=BufferedReader(InputStreamReader(c.getInputStream())).readLine() ?: return
                    val target=request.split(' ').getOrNull(1) ?: "/"
                    val path=target.substringBefore('?'); val query=target.substringAfter('?',"")
                    when(path) {
                        "/ping" -> respondText(c,"OK")
                        "/control" -> { applyRemoteQuery(query); respondText(c,"OK") }
                        "/focus" -> { applyRemoteFocus(query); respondText(c,"OK") }
                        "/capture", "/shutter" -> { val bytes=captureJpegForRemote(); if(bytes==null) respondText(c,"CAPTURE FAILED",503) else respondJpeg(c,bytes) }
                        "/preview.jpg" -> { val bytes=snapshotBytes(); if(bytes==null) respondText(c,"NO PREVIEW",503) else respondJpeg(c,bytes) }
                        else -> respondText(c,"Collectooow Photobox 0.8")
                    }
                } catch(_:Throwable){}
            }
        }
        private fun respondText(c:java.net.Socket,text:String,code:Int=200){
            val data=text.toByteArray(); val head="HTTP/1.1 $code ${if(code==200)"OK" else "ERROR"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply{write(head.toByteArray());write(data);flush()}
        }
        private fun respondJpeg(c:java.net.Socket,data:ByteArray){
            val head="HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: no-store\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply{write(head.toByteArray());write(data);flush()}
        }
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_:Throwable){ null }

    private fun cropForZoom(sensor:Rect, zoom:Float):Rect {
        val z=zoom.coerceAtLeast(1f); val w=(sensor.width()/z).toInt().coerceAtLeast(2); val h=(sensor.height()/z).toInt().coerceAtLeast(2)
        val left=sensor.centerX()-w/2; val top=sensor.centerY()-h/2; return Rect(left,top,left+w,top+h)
    }

    private fun logMap(progress:Int,min:Long,max:Long):Long {
        if(min<=0 || max<=min) return min
        val t=progress/1000.0
        return exp(ln(min.toDouble())+(ln(max.toDouble())-ln(min.toDouble()))*t).toLong().coerceIn(min,max)
    }

    private fun formatExposure(ns:Long):String {
        val sec=ns/1_000_000_000.0
        return if(sec>=1.0) "${"%.2f".format(sec)} s" else "1/${(1.0/sec.coerceAtLeast(0.000001)).toInt()} s"
    }

    private fun closeCamera(){
        try{session?.close()}catch(_:Throwable){}; session=null
        try{camera?.close()}catch(_:Throwable){}; camera=null
        try{imageReader?.close()}catch(_:Throwable){}; imageReader=null; previewBuilder=null
    }

    private fun showError(where:String,t:Throwable){ runOnUiThread { status.text="$where: ${t.javaClass.simpleName}: ${t.message ?: "Fehler"}" } }
}
