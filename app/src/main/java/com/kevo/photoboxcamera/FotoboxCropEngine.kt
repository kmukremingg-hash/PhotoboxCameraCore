package com.kevo.photoboxcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Nicht-generativer Fotobox-Cropper fuer die neue, fest aufgebaute Fotobox.
 *
 * Wichtig fuer 0.9:
 * - KEINE 180-Grad-Drehung.
 * - Das Kamerafoto wird bereits richtig herum geliefert.
 * - Die orange Fotobox-Halterung dient nur als geometrische Referenz.
 * - Innerhalb der Verpackung werden keinerlei helle/weiße Pixel entfernt.
 * - Die Aussparung oben rechts wird ausschließlich geometrisch ausgeschnitten.
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
        val bottom: Int
    )

    fun process(jpeg: ByteArray): Result {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("JPEG konnte nicht gelesen werden")

        val rails = detectOrangeRails(src)
            ?: error("Fotobox-Rahmen/Halterung im Bild nicht sicher erkannt")

        val cardWidth = rails.right - rails.left + 1
        if (cardWidth <= 50) error("Erkannte Kartenbreite ist unplausibel")

        // Aus den beiden weißen Referenzaufnahmen kalibriert.
        // Keine Drehung: die Karte wird in der aufgenommenen Orientierung verarbeitet.
        val top = max(0, (rails.bottom - cardWidth * 1.008f).toInt())
        val bottom = min(src.height - 1, rails.bottom)
        val left = max(0, rails.left)
        val right = min(src.width - 1, rails.right)
        val cw = right - left + 1
        val ch = bottom - top + 1

        if (cw <= 50 || ch <= 50) error("Fotobox-Zuschnitt ist unplausibel")

        val crop = Bitmap.createBitmap(src, left, top, cw, ch)
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = buildCardPath(cw.toFloat(), ch.toFloat())
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(crop, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        if (crop !== src) crop.recycle()
        src.recycle()

        return Result(
            bitmap = out,
            cropRect = Rect(left, top, right + 1, bottom + 1),
            message = "Fotobox erkannt · keine Drehung · Außenkontur + Aussparung oben rechts"
        )
    }

    private fun buildCardPath(w: Float, h: Float): Path = Path().apply {
        // Geometrische Außenkontur aus den zwei weißen Fotobox-Referenzen.
        // Es werden nur Bereiche AUSSERHALB der Kartenform transparent.
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

        val minVertical = max(8, (sampledRows * 0.22f).toInt())
        val minHorizontal = max(8, (sampledCols * 0.32f).toInt())

        var leftCandidate = -1
        var x = 0
        while (x < (w * 0.25f).toInt()) {
            if (colCounts[x] >= minVertical) leftCandidate = x
            x += step
        }

        var rightCandidate = -1
        x = (w * 0.72f).toInt()
        while (x < w) {
            if (colCounts[x] >= minVertical) {
                rightCandidate = x
                break
            }
            x += step
        }

        var bottomCandidate = -1
        y = (h * 0.55f).toInt()
        while (y < h) {
            if (rowCounts[y] >= minHorizontal) {
                bottomCandidate = y
                break
            }
            y += step
        }

        if (leftCandidate < 0 || rightCandidate < 0 || bottomCandidate < 0) return null
        if (rightCandidate - leftCandidate < w * 0.40f) return null

        val leftEdge = refineLeftEdge(bitmap, leftCandidate, step)
        val rightEdge = refineRightEdge(bitmap, rightCandidate, step)
        val bottomEdge = refineTopEdgeOfHorizontalRail(bitmap, bottomCandidate, step)

        if (rightEdge <= leftEdge || bottomEdge <= 0) return null
        return Rails(leftEdge + 1, rightEdge - 1, bottomEdge - 1)
    }

    private fun refineLeftEdge(bitmap: Bitmap, around: Int, step: Int): Int {
        val from = max(0, around - step * 4)
        val to = min(bitmap.width - 1, around + step * 4)
        var best = around
        for (x in from..to) {
            if (orangeColumnRatio(bitmap, x) > 0.16f) best = x
        }
        return best
    }

    private fun refineRightEdge(bitmap: Bitmap, around: Int, step: Int): Int {
        val from = max(0, around - step * 4)
        val to = min(bitmap.width - 1, around + step * 4)
        for (x in from..to) {
            if (orangeColumnRatio(bitmap, x) > 0.16f) return x
        }
        return around
    }

    private fun refineTopEdgeOfHorizontalRail(bitmap: Bitmap, around: Int, step: Int): Int {
        val from = max(0, around - step * 5)
        val to = min(bitmap.height - 1, around + step * 5)
        for (y in from..to) {
            if (orangeRowRatio(bitmap, y) > 0.25f) return y
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
        return r > 150 && g in 55..190 && b < 135 && (r - g) > 40 && (g - b) > 0
    }
}
