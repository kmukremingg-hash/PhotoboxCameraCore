from pathlib import Path

main = Path('app/src/main/java/com/kevo/photoboxcamera/MainActivity.kt')
text = main.read_text(encoding='utf-8')

def replace_once(old: str, new: str, label: str):
    global text
    if old not in text:
        raise SystemExit(f'{label} not found; refusing unsafe patch')
    text = text.replace(old, new, 1)

replace_once(
'''    private lateinit var undoButton: Button\n    private lateinit var redoButton: Button''',
'''    private lateinit var undoButton: Button\n    private lateinit var redoButton: Button\n    private lateinit var cameraPanel: View\n    private lateinit var cropPanel: View\n    private lateinit var cropPreview: ImageView\n    private lateinit var cropStatus: TextView\n    private lateinit var navCameraButton: Button\n    private lateinit var navCropButton: Button\n    private lateinit var retryCropButton: Button\n    private lateinit var backToCameraButton: Button\n    private var lastCapturedJpeg: ByteArray? = null\n    private var lastCropBaseName: String? = null''',
'field insertion')

replace_once(
'''            undoButton = findViewById(R.id.undoSettings)\n            redoButton = findViewById(R.id.redoSettings)''',
'''            undoButton = findViewById(R.id.undoSettings)\n            redoButton = findViewById(R.id.redoSettings)\n            cameraPanel = findViewById(R.id.cameraPanel)\n            cropPanel = findViewById(R.id.cropPanel)\n            cropPreview = findViewById(R.id.cropPreview)\n            cropStatus = findViewById(R.id.cropStatus)\n            navCameraButton = findViewById(R.id.navCamera)\n            navCropButton = findViewById(R.id.navCrop)\n            retryCropButton = findViewById(R.id.retryCrop)\n            backToCameraButton = findViewById(R.id.backToCamera)''',
'view binding insertion')

replace_once(
'''            scanButton.setOnClickListener { startCamera() }''',
'''            scanButton.setOnClickListener { startCamera() }\n            navCameraButton.setOnClickListener { showCameraPanel() }\n            navCropButton.setOnClickListener { showCropPanel() }\n            backToCameraButton.setOnClickListener { showCameraPanel() }\n            retryCropButton.setOnClickListener {\n                val bytes = lastCapturedJpeg\n                if (bytes == null) {\n                    cropStatus.text = "Noch kein Fotobox-Foto für erneuten Crop vorhanden."\n                } else {\n                    cropStatus.text = "Fotocrop wird erneut ausgeführt …"\n                    val base = lastCropBaseName ?: "Collectooow_Photobox_${SimpleDateFormat(\"yyyyMMdd_HHmmss\", Locale.US).format(Date())}"\n                    thread(name = "PhotoboxRetryCrop", isDaemon = true) { processAndSaveCrop(bytes, base) }\n                }\n            }''',
'navigation listener insertion')

old_capture = '''    private fun captureRemoteToTablet() {\n        if (!remoteMode || remoteBase.isBlank()) { status.text = "Bitte zuerst mit dem S25 verbinden."; return }\n        shutterButton.isEnabled = false\n        status.text = "Foto wird auf dem S25 aufgenommen und direkt zum Tablet übertragen …"\n        thread(name = "PhotoboxRemoteCapture", isDaemon = true) {\n            try {\n                val conn = URL("$remoteBase/capture?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection\n                conn.connectTimeout = 2500; conn.readTimeout = 15000; conn.useCaches = false\n                val code = conn.responseCode\n                if (code != 200) error("S25 meldet HTTP $code")\n                val bytes = conn.inputStream.use { it.readBytes() }; conn.disconnect()\n                if (bytes.size < 10_000) error("Bilddaten unvollständig (${bytes.size} Bytes)")\n                val name = "Collectooow_Photobox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"\n                saveJpegOnTablet(bytes, name)\n                runOnUiThread { status.text = "Gespeichert NUR auf Tablet:\\n$name\\nPictures/Collectooow Photobox/Rohbilder"; shutterButton.isEnabled = true }\n            } catch (t: Throwable) {\n                runOnUiThread { status.text = "Fotoübertragung fehlgeschlagen:\\n${t.message ?: t.javaClass.simpleName}\\nAuf dem Handy wurde keine dauerhafte Fotodatei angelegt."; shutterButton.isEnabled = true }\n            }\n        }\n    }'''

new_capture = '''    private fun captureRemoteToTablet() {\n        if (!remoteMode || remoteBase.isBlank()) { status.text = "Bitte zuerst mit dem S25 verbinden."; return }\n        shutterButton.isEnabled = false\n        status.text = "Foto wird aufgenommen → Tablet → automatischer Fotocrop …"\n        thread(name = "PhotoboxRemoteCapture", isDaemon = true) {\n            try {\n                val conn = URL("$remoteBase/capture?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection\n                conn.connectTimeout = 2500; conn.readTimeout = 15000; conn.useCaches = false\n                val code = conn.responseCode\n                if (code != 200) error("S25 meldet HTTP $code")\n                val bytes = conn.inputStream.use { it.readBytes() }; conn.disconnect()\n                if (bytes.size < 10_000) error("Bilddaten unvollständig (${bytes.size} Bytes)")\n\n                val base = "Collectooow_Photobox_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"\n                val rawName = "${base}_RAW.jpg"\n                saveJpegOnTablet(bytes, rawName)\n                lastCapturedJpeg = bytes\n                lastCropBaseName = base\n\n                runOnUiThread {\n                    status.text = "Rohbild auf Tablet gespeichert. Fotocrop läuft …\\n$rawName"\n                }\n                processAndSaveCrop(bytes, base)\n            } catch (t: Throwable) {\n                runOnUiThread {\n                    status.text = "Fotoübertragung fehlgeschlagen:\\n${t.message ?: t.javaClass.simpleName}\\nAuf dem Handy wurde keine dauerhafte Fotodatei angelegt."\n                    shutterButton.isEnabled = true\n                }\n            }\n        }\n    }'''

replace_once(old_capture, new_capture, 'captureRemoteToTablet')

helper_marker = '''    private fun saveJpegOnTablet(bytes: ByteArray, name: String) {'''
helpers = '''    private fun showCameraPanel() {\n        cameraPanel.visibility = View.VISIBLE\n        cropPanel.visibility = View.GONE\n    }\n\n    private fun showCropPanel() {\n        cameraPanel.visibility = View.GONE\n        cropPanel.visibility = View.VISIBLE\n    }\n\n    private fun processAndSaveCrop(bytes: ByteArray, baseName: String) {\n        try {\n            // 0.9: KEINE 180°-Drehung. Das Bild kommt bereits richtig herum aus der neuen Fotobox.\n            val result = FotoboxCropEngine.process(bytes)\n            val finalName = "${baseName}_FERTIG.png"\n            savePngOnTablet(result.bitmap, finalName)\n            runOnUiThread {\n                cropPreview.setImageBitmap(result.bitmap)\n                cropStatus.text = "${result.message}\\nGespeichert: $finalName\\nPictures/Collectooow Photobox/Fertig"\n                status.text = "Rohbild + Fertigbild auf Tablet gespeichert.\\nKeine 180°-Drehung."\n                showCropPanel()\n                shutterButton.isEnabled = true\n            }\n        } catch (t: Throwable) {\n            runOnUiThread {\n                cropStatus.text = "Fotocrop nicht möglich:\\n${t.message ?: t.javaClass.simpleName}\\nDas Rohbild wurde trotzdem sicher auf dem Tablet gespeichert."\n                status.text = "Rohbild gespeichert; automatische Fotobox-Erkennung fehlgeschlagen."\n                showCropPanel()\n                shutterButton.isEnabled = true\n            }\n        }\n    }\n\n    private fun savePngOnTablet(bitmap: Bitmap, name: String) {\n        val values = ContentValues().apply {\n            put(MediaStore.Images.Media.DISPLAY_NAME, name)\n            put(MediaStore.Images.Media.MIME_TYPE, "image/png")\n            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Collectooow Photobox/Fertig")\n        }\n        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)\n            ?: error("Tablet konnte die Fertigdatei nicht anlegen")\n        try {\n            contentResolver.openOutputStream(uri)?.use { out ->\n                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("PNG konnte nicht geschrieben werden")\n            } ?: error("Tablet-Fertigdatei konnte nicht geöffnet werden")\n        } catch (t: Throwable) {\n            try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}\n            throw t\n        }\n    }\n\n'''

replace_once(helper_marker, helpers + helper_marker, 'crop helper insertion')

text = text.replace('Photobox 0.8.1 STARTFEHLER', 'Photobox 0.9 STARTFEHLER')
text = text.replace('Collectooow Photobox 0.8.1', 'Collectooow Fotobox 0.9')
main.write_text(text, encoding='utf-8')

build = Path('app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
b = b.replace('versionCode = 9', 'versionCode = 10')
b = b.replace('versionName = "0.8.1"', 'versionName = "0.9"')
build.write_text(b, encoding='utf-8')

print('Collectooow Fotobox 0.9 integration patch applied')
