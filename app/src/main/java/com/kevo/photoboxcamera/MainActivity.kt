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
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
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

            setupSeekBars()
            setControlsEnabled(false)
            preview.visibility = View.GONE

            findViewById<Button>(R.id.hostMode).setOnClickListener { selectHostMode() }
            findViewById<Button>(R.id.remoteMode).setOnClickListener { selectRemoteMode() }
            findViewById<Button>(R.id.connectRemote).setOnClickListener { connectRemote() }
            scanButton.setOnClickListener { startCamera() }

            modeButton.setOnClickListener {
                manualExposure = !manualExposure
                modeButton.text = if (manualExposure) "Belichtung: MANUELL" else "Belichtung: AUTO"
                if (remoteMode) sendRemote("/control?manual=${if (manualExposure) 1 else 0}") else applyPreviewSettings()
            }
            wbButton.setOnClickListener {
                wbIndex = (wbIndex + 1) % wbModes.size
                wbButton.text = "Weißabgleich: ${wbNames[wbIndex]}"
                if (remoteMode) sendRemote("/control?wb=$wbIndex") else applyPreviewSettings()
            }
            shutterButton.setOnClickListener {
                if (remoteMode) sendRemote("/shutter") else takePhoto()
            }
        } catch (t: Throwable) {
            setContentView(TextView(this).apply {
                text = "Photobox 0.6 STARTFEHLER\n${t.javaClass.name}\n${t.message ?: "ohne Meldung"}"
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
        remotePanel.visibility = View.GONE
        remotePreview.visibility = View.GONE
        preview.visibility = View.VISIBLE
        scanButton.visibility = View.VISIBLE
        setControlsEnabled(true)
        val ip = localIpv4() ?: "IP nicht gefunden"
        server?.stop()
        server = RemoteServer(8765).also { it.start() }
        status.text = "S25 HOST-MODUS\nWLAN-Adresse: $ip:8765\nAuf dem Tablet 'Tablet / Remote' wählen und diese IP eingeben."
    }

    private fun selectRemoteMode() {
        hostMode = false
        remoteMode = true
        closeCamera()
        server?.stop(); server = null
        preview.visibility = View.GONE
        scanButton.visibility = View.GONE
        remotePanel.visibility = View.VISIBLE
        remotePreview.visibility = View.VISIBLE
        setControlsEnabled(true)
        status.text = "TABLET REMOTE-MODUS\nIP-Adresse vom S25 eingeben und verbinden."
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
            runOnUiThread { status.text = if (ok) "Verbunden mit S25: OK" else "Verbindung fehlgeschlagen: $remoteBase" }
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
                conn.readTimeout = 2000
                ok = conn.responseCode in 200..299
                conn.inputStream?.close()
                conn.disconnect()
            } catch (_: Throwable) {}
            callback?.invoke(ok)
        }
    }

    private fun setupSeekBars() {
        isoBar.progress = 100
        exposureBar.progress = 500
        focusBar.progress = 0
        zoomBar.progress = 0
        isoBar.setOnSeekBarChangeListener(simpleSeek { p ->
            if (remoteMode) { isoLabel.text = "ISO-Regler: $p"; sendRemote("/control?iso=$p") }
            else setIsoProgress(p)
        })
        exposureBar.setOnSeekBarChangeListener(simpleSeek { p ->
            if (remoteMode) { exposureLabel.text = "Belichtungs-Regler: $p"; sendRemote("/control?exposure=$p") }
            else setExposureProgress(p)
        })
        focusBar.setOnSeekBarChangeListener(simpleSeek { p ->
            if (remoteMode) { focusLabel.text = if (p == 0) "Fokus: AUTO" else "Fokus-Regler: $p"; sendRemote("/control?focus=$p") }
            else setFocusProgress(p)
        })
        zoomBar.setOnSeekBarChangeListener(simpleSeek { p ->
            if (remoteMode) { zoomLabel.text = "Zoom-Regler: $p"; sendRemote("/control?zoom=$p") }
            else setZoomProgress(p)
        })
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf<View>(isoBar, exposureBar, focusBar, zoomBar, modeButton, wbButton, shutterButton).forEach { it.isEnabled = enabled }
    }

    private fun setIsoProgress(p: Int) {
        val r = isoRange ?: return
        currentIso = r.lower + ((r.upper - r.lower) * p.coerceIn(0,1000) / 1000f).toInt()
        isoLabel.text = "ISO: $currentIso"
        if (manualExposure) applyPreviewSettings()
    }

    private fun setExposureProgress(p: Int) {
        val r = exposureRange ?: return
        currentExposureNs = logMap(p.coerceIn(0,1000), r.lower, r.upper)
        exposureLabel.text = "Belichtungszeit: ${formatExposure(currentExposureNs)}"
        if (manualExposure) applyPreviewSettings()
    }

    private fun setFocusProgress(p: Int) {
        if (minFocusDistance <= 0f) return
        manualFocus = p > 0
        currentFocus = minFocusDistance * p.coerceIn(0,1000) / 1000f
        focusLabel.text = if (manualFocus) "Fokus manuell: ${"%.2f".format(currentFocus)} dpt" else "Fokus: AUTO"
        applyPreviewSettings()
    }

    private fun setZoomProgress(p: Int) {
        currentZoom = 1f + (maxZoom - 1f) * p.coerceIn(0,1000) / 1000f
        zoomLabel.text = "Zoom: ${"%.1f".format(currentZoom)}x"
        applyPreviewSettings()
    }

    private fun startCamera() {
        if (!hostMode) selectHostMode()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
            return
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
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: error("Keine Rückkamera gefunden")
            val c = manager.getCameraCharacteristics(id)
            characteristics = c
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            sensorRect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            isoRange?.let { currentIso = currentIso.coerceIn(it.lower, it.upper) }
            exposureRange?.let { currentExposureNs = currentExposureNs.coerceIn(it.lower, it.upper) }
            isoLabel.text = "ISO: $currentIso (${isoRange ?: "?"})"
            exposureLabel.text = "Belichtungszeit: ${formatExposure(currentExposureNs)}"
            val ip = localIpv4() ?: "?"
            status.text = "HOST $ip:8765 | Camera2: OK | Kamera $id | Zoom bis ${"%.1f".format(maxZoom)}x"
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
            val texture = preview.surfaceTexture ?: error("SurfaceTexture fehlt")
            val surface = Surface(texture)
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSize = chooseJpegSize(map)
            imageReader?.close()
            imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader -> saveImage(reader) }, bgHandler)
            }
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            device.createCaptureSession(listOf(surface, imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    applyPreviewSettings()
                    runOnUiThread { status.append("\nLivebild: OK | WLAN-Remote bereit") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { status.append("\nLivebild: FEHLER") } }
            }, bgHandler)
        } catch (t: Throwable) { showError("Livebild", t) }
    }

    private fun chooseJpegSize(map: StreamConfigurationMap?): Size =
        map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(4000, 3000)

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

    private fun takePhoto() {
        val device = camera ?: return
        val reader = imageReader ?: return
        val s = session ?: return
        try {
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
            runOnUiThread { status.append("\nFoto ausgelöst …") }
        } catch (t: Throwable) { showError("Foto", t) }
    }

    private fun saveImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
            val name = "Collectooow_Photobox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Collectooow Photobox")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore konnte Datei nicht anlegen")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Ausgabedatei konnte nicht geöffnet werden")
            runOnUiThread { status.append("\nGespeichert: $name") }
        } catch (t: Throwable) { showError("Speichern", t) } finally { image.close() }
    }

    private fun applyRemoteQuery(query: String) {
        val params = query.split('&').mapNotNull {
            val p = it.split('=', limit = 2)
            if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
        runOnUiThread {
            params["iso"]?.toIntOrNull()?.let { isoBar.progress = it; setIsoProgress(it) }
            params["exposure"]?.toIntOrNull()?.let { exposureBar.progress = it; setExposureProgress(it) }
            params["focus"]?.toIntOrNull()?.let { focusBar.progress = it; setFocusProgress(it) }
            params["zoom"]?.toIntOrNull()?.let { zoomBar.progress = it; setZoomProgress(it) }
            params["manual"]?.let { manualExposure = it == "1"; modeButton.text = if (manualExposure) "Belichtung: MANUELL" else "Belichtung: AUTO"; applyPreviewSettings() }
            params["wb"]?.toIntOrNull()?.let { wbIndex = it.coerceIn(0, wbModes.lastIndex); wbButton.text = "Weißabgleich: ${wbNames[wbIndex]}"; applyPreviewSettings() }
        }
    }

    private fun snapshotBytes(): ByteArray? {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        runOnUiThread {
            try { if (preview.isAvailable) bmp = preview.bitmap } catch (_: Throwable) {}
            latch.countDown()
        }
        latch.await(1200, TimeUnit.MILLISECONDS)
        val b = bmp ?: return null
        return try { ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, 65, out); out.toByteArray() } } finally { b.recycle() }
    }

    private inner class RemoteServer(private val port: Int) {
        @Volatile private var running = false
        private var socket: ServerSocket? = null
        fun start() {
            running = true
            thread(name = "PhotoboxRemoteServer", isDaemon = true) {
                try {
                    socket = ServerSocket(port)
                    while (running) {
                        val client = socket?.accept() ?: break
                        thread(isDaemon = true) { handle(client) }
                    }
                } catch (t: Throwable) {
                    if (running) runOnUiThread { status.append("\nRemote-Server Fehler: ${t.message}") }
                }
            }
        }
        fun stop() { running = false; try { socket?.close() } catch (_: Throwable) {}; socket = null }
        private fun handle(client: java.net.Socket) {
            client.use { c ->
                try {
                    c.soTimeout = 2000
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    val request = reader.readLine() ?: return
                    val target = request.split(' ').getOrNull(1) ?: "/"
                    val path = target.substringBefore('?')
                    val query = target.substringAfter('?', "")
                    when (path) {
                        "/ping" -> respondText(c, "OK")
                        "/control" -> { applyRemoteQuery(query); respondText(c, "OK") }
                        "/shutter" -> { runOnUiThread { takePhoto() }; respondText(c, "OK") }
                        "/preview.jpg" -> {
                            val bytes = snapshotBytes()
                            if (bytes == null) respondText(c, "NO PREVIEW", 503) else respondJpeg(c, bytes)
                        }
                        else -> respondText(c, "Collectooow Photobox 0.6")
                    }
                } catch (_: Throwable) {}
            }
        }
        private fun respondText(c: java.net.Socket, text: String, code: Int = 200) {
            val data = text.toByteArray()
            val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray()); write(data); flush() }
        }
        private fun respondJpeg(c: java.net.Socket, data: ByteArray) {
            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: no-store\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray()); write(data); flush() }
        }
    }

    private fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Throwable) { null }
    }

    private fun cropForZoom(sensor: Rect, zoom: Float): Rect {
        val z = zoom.coerceAtLeast(1f)
        val w = (sensor.width() / z).toInt(); val h = (sensor.height() / z).toInt()
        val left = sensor.centerX() - w / 2; val top = sensor.centerY() - h / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun logMap(progress: Int, min: Long, max: Long): Long {
        val a = ln(min.toDouble()); val b = ln(max.toDouble())
        return exp(a + (b - a) * progress / 1000.0).toLong().coerceIn(min, max)
    }

    private fun formatExposure(ns: Long): String {
        val sec = ns / 1_000_000_000.0
        return if (sec < 0.5) "1/${(1.0 / sec).toInt()} s" else "${"%.3f".format(sec)} s"
    }

    private fun showError(where: String, t: Throwable) {
        runOnUiThread { status.text = "FEHLER bei $where\n${t.javaClass.simpleName}: ${t.message ?: "ohne Meldung"}" }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Throwable) {}; session = null
        try { camera?.close() } catch (_: Throwable) {}; camera = null
        try { imageReader?.close() } catch (_: Throwable) {}; imageReader = null
        previewBuilder = null
    }
}
