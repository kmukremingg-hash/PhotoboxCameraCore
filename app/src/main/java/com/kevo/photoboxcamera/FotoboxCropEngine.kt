package com.kevo.photoboxcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Nicht-generativer Fotobox-Cropper fuer die neue, fest aufgebaute Fotobox.
 *
 * 0.9.1:
 * - KEINE 180-Grad-Drehung.
 * - JPEG-Sensororientierung wird technisch normalisiert (nur falls die rohen Pixel quer liegen).
 * - Die orange Fotobox-Halterung dient als geometrische Referenz.
 * - Falls die Farberkennung wegen Licht/Weissabgleich scheitert, greift die feste
 *   Fotobox-Kalibrierung aus den beiden weissen Referenzaufnahmen.
 * - Innerhalb der Verpackung werden keinerlei helle/weisse Pixel entfernt.
 * - Die Aussparung oben rechts wird ausschliesslich geometrisch ausgeschnitten.
 */
object FotoboxCropEngine {

    data class Result(
        val bitmap: Bitmap,
        val cropRect: Rect,
        val message: String
    )

    private data class Rails(
        val left: Int,
        val right: Int,
        val bottom: Int,
        val source: String
    )

    fun process(jpeg: ByteArray): Result {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("JPEG konnte nicht gelesen werden")

        val orientationNormalized = decoded.width > decoded.height
        val src = if (orientationNormalized) rotateClockwise90(decoded) else decoded
        if (src !== decoded) decoded.recycle()

        val rails = detectOrangeRails(src) ?: calibratedReferenceRails(src)
            ?: run {
                src.recycle()
                error("Fotobox-Referenz konnte nicht bestimmt werden")
            }

        val cardWidth = rails.right - rails.left + 1
        if (cardWidth <= 50) {
            src.recycle()
            error("Erkannte Kartenbreite ist unplausibel")
        }

        // Aus den beiden weissen Referenzaufnahmen kalibriert.
        // Keine inhaltliche Drehung: die Karte bleibt in der aufgenommenen Orientierung.
        val top = max(0, (rails.bottom - cardWidth * 1.008f).toInt())
        val bottom = min(src.height - 1, rails.bottom)
        val left = max(0, rails.left)
        val right = min(src.width - 1, rails.right)
        val cw = right - left + 1
        val ch = bottom - top + 1

        if (cw <= 50 || ch <= 50 || right >= src.width || bottom >= src.height) {
            src.recycle()
            error("Fotobox-Zuschnitt ist unplausibel")
        }

        val crop = Bitmap.createBitmap(src, left, top, cw, ch)
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = buildCardPath(cw.toFloat(), ch.toFloat())
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(crop, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        crop.recycle()
        src.recycle()

        val orientationText = if (orientationNormalized) "JPEG-Orientierung normalisiert" else "Orientierung bereits korrekt"
        return Result(
            bitmap = out,
            cropRect = Rect(left, top, right + 1, bottom + 1),
            message = "Fotobox erkannt (${rails.source}) · $orientationText · keine 180°-Drehung · Außenkontur + Aussparung oben rechts"
        )
    }

    private fun rotateClockwise90(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun buildCardPath(w: Float, h: Float): Path = Path().apply {
        moveTo(0f, 0.120f * h)
        lineTo(0.075f * w, 0f)
        lineTo(0.655f * w, 0f)

        quadTo(0.680f * w, 0f, 0.690f * w, 0.025f * h)
        lineTo(0.690f * w, 0.125f * h)
        quadTo(0.690f * w, 0.145f * h, 0.675f * w, 0.145f * h)

        lineTo(0.525f * w, 0.145f * h)
        quadTo(0.510f * w, 0.145f * h, 0.510f * w, 0.125f * h)
        lineTo(0.510f * w, 0.095f * h)
        quadTo(0.510f * w, 0.075f * h, 0.490f * w, 0.075f * h)
        quadTo(0.465f * w, 0.075f * h, 0.465f * w, 0.100f * h)
        lineTo(0.465f * w, 0.190f * h)
        quadTo(0.465f * w, 0.225f * h, 0.500f * w, 0.225f * h)

        lineTo(0.855f * w, 0.225f * h)
        lineTo(0.910f * w, 0.175f * h)
        lineTo(w, 0.175f * h)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }

    /**
     * Farb-/Geometrieerkennung. Die Suchzonen liegen nur an den bekannten
     * Fotobox-Raendern, damit orange/rote Verpackungsgrafik in der Karte nicht stoert.
     */
    private fun detectOrangeRails(bitmap: Bitmap): Rails? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 300 || h < 300) return null

        val step = max(1, max(w, h) / 1400)
        val colCounts = IntArray(w)
        val rowCounts = IntArray(h)
        var sampledRows = 0
        var sampledCols = 0

        var y = 0
        while (y < h) {
            sampledRows++
            var x = 0
            while (x < w) {
                if (y == 0) sampledCols++
                if (isOrange(bitmap.getPixel(x, y))) {
                    colCounts[x]++
                    rowCounts[y]++
                }
                x += step
            }
            y += step
        }

        // Etwas toleranter als 0.9, da Weissabgleich und Spiegelungen die Orangeflaeche veraendern koennen.
        val minVertical = max(6, (sampledRows * 0.12f).toInt())
        val minHorizontal = max(6, (sampledCols * 0.20f).toInt())

        var leftCandidate = -1
        var x = 0
        while (x < (w * 0.28f).toInt()) {
            if (colCounts[x] >= minVertical) leftCandidate = x
            x += step
        }

        var rightCandidate = -1
        x = (w * 0.68f).toInt()
        while (x < w) {
            if (colCounts[x] >= minVertical) {
                rightCandidate = x
                break
            }
            x += step
        }

        var bottomCandidate = -1
        y = (h * 0.50f).toInt()
        while (y < h) {
            if (rowCounts[y] >= minHorizontal) {
                bottomCandidate = y
                break
            }
            y += step
        }

        if (leftCandidate < 0 || rightCandidate < 0 || bottomCandidate < 0) return null
        if (rightCandidate - leftCandidate < w * 0.38f) return null

        val leftEdge = refineLeftEdge(bitmap, leftCandidate, step)
        val rightEdge = refineRightEdge(bitmap, rightCandidate, step)
        val bottomEdge = refineTopEdgeOfHorizontalRail(bitmap, bottomCandidate, step)

        if (rightEdge <= leftEdge || bottomEdge <= 0) return null
        return Rails(leftEdge + 1, rightEdge - 1, bottomEdge - 1, "orange Halterung")
    }

    /**
     * Fester Referenzmodus fuer die neue Fotobox.
     * Die Werte stammen aus den zwei weissen, freigegebenen Fotobox-Referenzaufnahmen.
     * Weil Kamera, Abstand und Halterung praktisch konstant sind, ist dies der sichere
     * Fallback, wenn Farbe/Beleuchtung die Orange-Erkennung verhindert.
     */
    private fun calibratedReferenceRails(bitmap: Bitmap): Rails? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 300 || h < 300) return null

        val ratio = w.toFloat() / h.toFloat()
        // Erwartet wird nach technischer Orientierungsnormalisierung ein Hochformat um ca. 3:4.
        if (ratio !in 0.68f..0.82f) return null

        val left = (w * 0.018f).toInt().coerceIn(0, w - 3)
        val right = (w * 0.777f).toInt().coerceIn(left + 2, w - 1)
        val bottom = (h * 0.710f).toInt().coerceIn(2, h - 1)
        return Rails(left, right, bottom, "Fotobox-Referenz")
    }

    private fun refineLeftEdge(bitmap: Bitmap, around: Int, step: Int): Int {
        val from = max(0, around - step * 6)
        val to = min(bitmap.width - 1, around + step * 6)
        var best = around
        for (x in from..to) {
            if (orangeColumnRatio(bitmap, x) > 0.10f) best = x
        }
        return best
    }

    private fun refineRightEdge(bitmap: Bitmap, around: Int, step: Int): Int {
        val from = max(0, around - step * 6)
        val to = min(bitmap.width - 1, around + step * 6)
        for (x in from..to) {
            if (orangeColumnRatio(bitmap, x) > 0.10f) return x
        }
        return around
    }

    private fun refineTopEdgeOfHorizontalRail(bitmap: Bitmap, around: Int, step: Int): Int {
        val from = max(0, around - step * 7)
        val to = min(bitmap.height - 1, around + step * 7)
        for (y in from..to) {
            if (orangeRowRatio(bitmap, y) > 0.16f) return y
        }
        return around
    }

    private fun orangeColumnRatio(bitmap: Bitmap, x: Int): Float {
        val step = max(1, bitmap.height / 800)
        var orange = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            total++
            if (isOrange(bitmap.getPixel(x, y))) orange++
            y += step
        }
        return if (total == 0) 0f else orange.toFloat() / total
    }

    private fun orangeRowRatio(bitmap: Bitmap, y: Int): Float {
        val step = max(1, bitmap.width / 800)
        var orange = 0
        var total = 0
        var x = 0
        while (x < bitmap.width) {
            total++
            if (isOrange(bitmap.getPixel(x, y))) orange++
            x += step
        }
        return if (total == 0) 0f else orange.toFloat() / total
    }

    private fun isOrange(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        // Toleranter Orange-Bereich fuer wechselnde Belichtung/Weissabgleich.
        return r > 130 && g in 40..205 && b < 160 && (r - g) > 22 && (g - b) > -8
    }
}
