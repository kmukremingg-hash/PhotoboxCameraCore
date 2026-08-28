package com.kevo.photoboxcamera

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Optionale Verpackungsreparatur fuer bereits zugeschnittene Fotobox-Bilder.
 *
 * WICHTIG:
 * - Wird niemals automatisch ausgefuehrt.
 * - Der Nutzer muss die Reparatur im Fotocrop-Bereich ausdruecklich oeffnen.
 * - Einriss-Reparatur arbeitet nur in der festen Karten-Zone ueber dem Aufhaengeloch.
 * - Punkt-Reparatur wird nur an der vom Nutzer angetippten Verpackungsstelle ausgefuehrt.
 * - Das Modellauto soll nicht als Reparaturziel verwendet werden.
 */
object PackagingRepairEngine {

    data class RepairResult(
        val bitmap: Bitmap,
        val changedPixels: Int,
        val message: String
    )

    fun repairTear(source: Bitmap, widthPx: Int, blendPercent: Int): RepairResult {
        val w = source.width
        val h = source.height
        if (w < 100 || h < 100) error("Bild ist fuer die Verpackungsreparatur zu klein")

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()

        val centerX = (w * 0.49f).roundToInt()
        val repairWidth = widthPx.coerceIn(12, min(320, w / 2))
        val x0 = max(2, centerX - repairWidth / 2)
        val x1 = min(w - 3, centerX + repairWidth / 2)
        val y0 = 0
        val y1 = min(h - 3, max(8, (h * 0.085f).roundToInt()))
        val blend = (blendPercent.coerceIn(0, 100) / 100f)

        var changed = 0
        for (y in y0..y1) {
            val leftSample = findOpaqueSample(pixels, w, h, x0 - 1, y, -1)
            val rightSample = findOpaqueSample(pixels, w, h, x1 + 1, y, 1)
            if (leftSample == null && rightSample == null) continue

            for (x in x0..x1) {
                val index = y * w + x
                val src = pixels[index]
                if (Color.alpha(src) < 40) continue
                if (isOrange(src)) continue

                val t = ((x - x0).toFloat() / max(1, x1 - x0)).coerceIn(0f, 1f)
                val target = when {
                    leftSample != null && rightSample != null -> lerpColor(leftSample, rightSample, t)
                    leftSample != null -> leftSample
                    else -> rightSample!!
                }

                val edge = min(x - x0, x1 - x).toFloat()
                val feather = (edge / max(2f, repairWidth * 0.18f)).coerceIn(0f, 1f)
                val amount = blend * feather
                if (amount <= 0.01f) continue
                out[index] = blendColor(src, target, amount)
                changed++
            }
        }

        // Sehr leichte Glaettung nur innerhalb derselben Reparaturzone.
        repeat(2) {
            val tmp = out.copyOf()
            for (y in max(1, y0)..min(h - 2, y1)) {
                for (x in max(1, x0 + 1) until min(w - 1, x1)) {
                    val i = y * w + x
                    val p = tmp[i]
                    if (Color.alpha(p) < 40 || isOrange(p)) continue
                    val left = tmp[i - 1]
                    val right = tmp[i + 1]
                    val up = tmp[i - w]
                    val down = tmp[i + w]
                    if (listOf(left, right, up, down).any { Color.alpha(it) < 40 }) continue
                    out[i] = weightedAverage(p, left, right, up, down)
                }
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return RepairResult(
            bitmap,
            changed,
            "Einriss-Reparatur angewendet · nur Verpackungszone ueber dem Aufhaengeloch"
        )
    }

    fun repairSpot(source: Bitmap, centerX: Int, centerY: Int, radiusPx: Int, strengthPercent: Int): RepairResult {
        val w = source.width
        val h = source.height
        val cx = centerX.coerceIn(0, w - 1)
        val cy = centerY.coerceIn(0, h - 1)
        val radius = radiusPx.coerceIn(4, 90)
        val strength = strengthPercent.coerceIn(20, 100) / 100f

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val target = robustRingColor(pixels, w, h, cx, cy, radius)
            ?: error("Umgebung der Abriebstelle konnte nicht sicher ausgewertet werden")
        val out = pixels.copyOf()

        val x0 = max(0, cx - radius)
        val x1 = min(w - 1, cx + radius)
        val y0 = max(0, cy - radius)
        val y1 = min(h - 1, cy + radius)
        val rr = radius * radius
        val targetLum = luminance(target)
        var changed = 0

        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d2 = dx * dx + dy * dy
                if (d2 > rr) continue
                val i = y * w + x
                val src = pixels[i]
                if (Color.alpha(src) < 40) continue

                val distance = sqrt(d2.toFloat())
                val feather = (1f - distance / radius).coerceIn(0f, 1f)
                val lum = luminance(src)
                var abrasion = ((lum - targetLum + 20f) / 85f).coerceIn(0f, 1f)
                if (Color.red(src) > 210 && Color.green(src) > 210 && Color.blue(src) > 210) {
                    abrasion = max(abrasion, 0.80f)
                }
                val amount = strength * feather * abrasion
                if (amount <= 0.02f) continue
                out[i] = blendColor(src, target, amount)
                changed++
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return RepairResult(
            bitmap,
            changed,
            "Punkt-Reparatur angewendet · nur die angetippte helle Abriebstelle wurde bearbeitet"
        )
    }

    private fun findOpaqueSample(pixels: IntArray, w: Int, h: Int, startX: Int, y: Int, direction: Int): Int? {
        var x = startX
        var searched = 0
        while (x in 0 until w && searched < 180) {
            val p = pixels[y.coerceIn(0, h - 1) * w + x]
            if (Color.alpha(p) > 80 && !isOrange(p)) return p
            x += direction
            searched++
        }
        return null
    }

    private fun robustRingColor(pixels: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int): Int? {
        val inner = max(3, (radius * 1.2f).roundToInt())
        val outer = max(inner + 3, (radius * 2.2f).roundToInt())
        val step = max(1, radius / 4)
        val rs = mutableListOf<Int>()
        val gs = mutableListOf<Int>()
        val bs = mutableListOf<Int>()

        var y = max(0, cy - outer)
        while (y <= min(h - 1, cy + outer)) {
            var x = max(0, cx - outer)
            while (x <= min(w - 1, cx + outer)) {
                val dx = x - cx
                val dy = y - cy
                val d2 = dx * dx + dy * dy
                if (d2 in (inner * inner)..(outer * outer)) {
                    val p = pixels[y * w + x]
                    if (Color.alpha(p) > 80) {
                        val r = Color.red(p)
                        val g = Color.green(p)
                        val b = Color.blue(p)
                        if (!(r > 220 && g > 220 && b > 220)) {
                            rs.add(r); gs.add(g); bs.add(b)
                        }
                    }
                }
                x += step
            }
            y += step
        }
        if (rs.size < 6) return null
        return Color.argb(255, median(rs), median(gs), median(bs))
    }

    private fun median(values: MutableList<Int>): Int {
        values.sort()
        val m = values.size / 2
        return if (values.size % 2 == 1) values[m] else (values[m - 1] + values[m]) / 2
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.argb(
        ((Color.alpha(a) * (1f - t)) + Color.alpha(b) * t).roundToInt().coerceIn(0, 255),
        ((Color.red(a) * (1f - t)) + Color.red(b) * t).roundToInt().coerceIn(0, 255),
        ((Color.green(a) * (1f - t)) + Color.green(b) * t).roundToInt().coerceIn(0, 255),
        ((Color.blue(a) * (1f - t)) + Color.blue(b) * t).roundToInt().coerceIn(0, 255)
    )

    private fun blendColor(src: Int, target: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.argb(
            Color.alpha(src),
            (Color.red(src) * (1f - a) + Color.red(target) * a).roundToInt().coerceIn(0, 255),
            (Color.green(src) * (1f - a) + Color.green(target) * a).roundToInt().coerceIn(0, 255),
            (Color.blue(src) * (1f - a) + Color.blue(target) * a).roundToInt().coerceIn(0, 255)
        )
    }

    private fun weightedAverage(center: Int, left: Int, right: Int, up: Int, down: Int): Int = Color.argb(
        Color.alpha(center),
        (Color.red(center) * 0.55f + Color.red(left) * 0.15f + Color.red(right) * 0.15f + Color.red(up) * 0.075f + Color.red(down) * 0.075f).roundToInt(),
        (Color.green(center) * 0.55f + Color.green(left) * 0.15f + Color.green(right) * 0.15f + Color.green(up) * 0.075f + Color.green(down) * 0.075f).roundToInt(),
        (Color.blue(center) * 0.55f + Color.blue(left) * 0.15f + Color.blue(right) * 0.15f + Color.blue(up) * 0.075f + Color.blue(down) * 0.075f).roundToInt()
    )

    private fun luminance(color: Int): Float =
        0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)

    private fun isOrange(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r > 150 && g in 55..190 && b < 135 && (r - g) > 40 && (g - b) > 0
    }
}
