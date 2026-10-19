package com.driveflow.driver.reader.indrive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.driveflow.driver.core.di.AppGraph
import com.driveflow.driver.core.model.TripDistanceOcr
import com.driveflow.driver.core.model.TripKmResult
import com.driveflow.driver.core.util.TextParsers
import com.driveflow.driver.ocr.CaptureResult
import com.driveflow.driver.ocr.OcrEngine
import com.driveflow.driver.ocr.OcrLine
import com.driveflow.driver.ocr.OcrResult
import com.driveflow.driver.ocr.ScreenshotSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller callback to ask the overlay layer to hide the floating bar briefly during map OCR capture.
 */
interface OverlayVisibilityController {
    fun hideTemporarily(durationMs: Long)
}

/**
 * inDrive Trip Distance OCR extractor.
 * Reads the distance badge drawn on the inDrive route map.
 */
class InDriveTripDistanceOcr(
    private val screenshotSource: ScreenshotSource? = null,
    private val ocrEngine: OcrEngine? = null,
    private val overlayController: OverlayVisibilityController? = null,
    private val screenBoundsProvider: () -> Rect = { Rect(0, 0, 1080, 2400) }
) : TripDistanceOcr {
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        val DISTANCE_BOX_REGEX = Regex(
            """(\d+(?:[.,]\d+)?)\s*(?:كم|km|متر|mi|ميل)""",
            RegexOption.IGNORE_CASE
        )
        private val TIME_REGEX = Regex("""(?i)\bmins?\b|دقيق|دقائق""")
        private val KM_UNIT_REGEX = Regex("""(?i)km|كم|كلم""")
        private val METER_UNIT_REGEX = Regex("""(?i)متر|\bm\b|(?<![\u0621-\u064A])م(?![\u0621-\u064A])""")
        private const val MAX_SPEED_KM_PER_MIN = 2.0
    }

    override suspend fun readTripKm(mapBounds: Rect, pickupKm: Double?): TripKmResult {
        // If mapBounds is empty or invalid, report missing box
        if (mapBounds.width() <= 10 || mapBounds.height() <= 10) {
            recordFailure("trip_box_missing")
            return TripKmResult(null, "trip_box_missing")
        }

        // 1. Temporarily hide our overlay bar to prevent covering the route box
        overlayController?.hideTemporarily(150)
        delay(80) // Wait brief instant for overlay view hierarchy to hide

        // 2. Capture screenshot
        val captureResult = screenshotSource?.capture()
        if (captureResult == null || captureResult !is CaptureResult.Success) {
            recordFailure("capture_refused")
            return TripKmResult(null, "capture_refused")
        }
        val bitmap = captureResult.bitmap

        try {
            val screen = screenBoundsProvider()
            val regionFraction = RectF(
                mapBounds.left.toFloat() / screen.width().coerceAtLeast(1),
                mapBounds.top.toFloat() / screen.height().coerceAtLeast(1),
                mapBounds.right.toFloat() / screen.width().coerceAtLeast(1),
                mapBounds.bottom.toFloat() / screen.height().coerceAtLeast(1)
            )

            // 3. OCR on map region (color sampling happens before the bitmap is recycled)
            val engine = ocrEngine ?: return parseFromBitmap(bitmap, regionFraction, mapBounds)
            val ocrResult = engine.read(bitmap, regionFraction)
            return parseOcrResult(ocrResult, mapBounds, screen, bitmap, pickupKm)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private class Bubble(
        val box: Rect,
        val distLine: OcrLine,
        val km: Double,
        val minutes: Double?
    ) {
        var color: Int = 0 // 1 = lime, 2 = blue, 0 = unknown
    }

    /**
     * Parses the trip-distance bubble (lime) from OCR results over the map bounds.
     * Returns the A->B distance only (without pickup).
     */
    fun parseOcrResult(
        ocrResult: OcrResult,
        mapBounds: Rect,
        screenBounds: Rect,
        bitmap: Bitmap? = null,
        pickupKm: Double? = null
    ): TripKmResult {
        val numeric = ocrResult.lines.filter { line ->
            TextParsers.normalizeDigits(line.text).any { it.isDigit() }
        }
        if (numeric.isEmpty()) {
            recordFailure("trip_box_missing")
            return TripKmResult(null, "trip_box_missing")
        }

        // 1. Group lines into bubbles (horizontal overlap + small vertical gap)
        val groups = groupLines(numeric)

        // 2. Build candidates: the LOWER non-time line of each bubble is the distance
        val candidates = mutableListOf<Bubble>()
        var sawUnclearValue = false
        for (group in groups) {
            val sorted = group.sortedBy { it.boundsPx.top }
            val distCandidates = sorted.filter { !isTimeLine(it.text) }
            val distLine = distCandidates.lastOrNull() ?: continue
            val normalized = TextParsers.normalizeDigits(distLine.text)
            val hasTimeSibling = sorted.size > 1
            if (!hasTimeSibling && !KM_UNIT_REGEX.containsMatchIn(normalized)) continue // lone number (speed sign, etc.)
            val km = valueKm(normalized)
            if (km == null) { sawUnclearValue = true; continue }
            val minutes = sorted.firstOrNull { it !== distLine && it.boundsPx.top < distLine.boundsPx.top }
                ?.let { minutesOf(it.text) }
            val box = Rect(sorted.first().boundsPx)
            sorted.forEach { box.union(it.boundsPx) }
            candidates.add(Bubble(box, distLine, km, minutes))
        }
        if (candidates.isEmpty()) {
            val code = if (sawUnclearValue) "digits_unclear" else "trip_box_missing"
            recordFailure(code)
            return TripKmResult(null, code)
        }

        // 3. Speed sanity: > 2 km/min means a misread digit -> drop that bubble
        val sane = candidates.filter { c ->
            c.minutes == null || c.minutes <= 0.0 || c.km / c.minutes <= MAX_SPEED_KM_PER_MIN
        }
        if (sane.isEmpty()) {
            recordFailure("digits_unclear")
            return TripKmResult(null, "digits_unclear")
        }

        // 4. Choose the lime (trip) bubble by color, else by excluding the blue pickup bubble
        var chosen: Bubble? = null
        if (bitmap != null && !bitmap.isRecycled) {
            sane.forEach { it.color = classifyColor(bitmap, it.box) }
            val lime = sane.filter { it.color == 1 }
            if (lime.isNotEmpty()) chosen = lime.maxByOrNull { it.km }
        }
        if (chosen == null) {
            val notBlue = sane.filter { it.color != 2 }
            val remaining = if (pickupKm != null) {
                notBlue.filter { kotlin.math.abs(it.km - pickupKm) > 0.15 }
            } else notBlue
            chosen = when {
                remaining.isEmpty() -> null
                remaining.size == 1 -> remaining[0]
                else -> remaining.maxByOrNull { it.km }
            }
        }
        if (chosen == null) {
            recordFailure("trip_bubble_ambiguous")
            return TripKmResult(null, "trip_bubble_ambiguous")
        }

        // 5. Validity checks on the CHOSEN line only
        if (chosen.distLine.confidence < 0.60f) {
            recordFailure("digits_unclear")
            return TripKmResult(null, "digits_unclear")
        }

        val lineBounds = chosen.box
        val screenW = if (screenBounds.right > 0) screenBounds.right else 1080
        val screenH = if (screenBounds.bottom > 0) screenBounds.bottom else 2400
        val mapBottom = if (mapBounds.bottom > 0) mapBounds.bottom else 1200

        val edgeTolerance = 8
        if (lineBounds.left <= edgeTolerance ||
            lineBounds.top <= edgeTolerance ||
            lineBounds.right >= (screenW - edgeTolerance) ||
            lineBounds.bottom >= (screenH - edgeTolerance)
        ) {
            recordFailure("trip_km_clipped")
            return TripKmResult(null, "trip_km_clipped")
        }
        if (lineBounds.bottom >= (mapBottom - 4)) {
            recordFailure("trip_km_covered")
            return TripKmResult(null, "trip_km_covered")
        }

        return TripKmResult(value = chosen.km, failure = null)
    }

    private fun isTimeLine(text: String): Boolean =
        TIME_REGEX.containsMatchIn(TextParsers.normalizeDigits(text))

    private fun minutesOf(text: String): Double? {
        val n = TextParsers.normalizeDigits(text)
        if (n.contains("ساع") || Regex("""(?i)\bh(r|rs|our|ours)?\b""").containsMatchIn(n)) return null
        val v = TextParsers.parseNumber(n) ?: return null
        return if (v in 1.0..600.0) v else null
    }

    /** Value in km from a bubble line. Null when ambiguous or outside 0.1..300. */
    private fun valueKm(normalized: String): Double? {
        val num = TextParsers.parseNumber(normalized) ?: return null
        val hasKm = KM_UNIT_REGEX.containsMatchIn(normalized)
        val hasMeter = !hasKm && METER_UNIT_REGEX.containsMatchIn(normalized)
        val km = when {
            hasKm -> num
            hasMeter -> num / 1000.0
            else -> {
                val hasDecimal = Regex("""\d[.,]\d""").containsMatchIn(normalized)
                if (hasDecimal || num < 100.0) num else return null
            }
        }
        return if (km in 0.1..300.0) km else null
    }

    private fun groupLines(lines: List<OcrLine>): List<List<OcrLine>> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i].boundsPx
            val b = lines[j].boundsPx
            val overlapsH = a.left < b.right && b.left < a.right
            val upper = if (a.top <= b.top) a else b
            val lower = if (a.top <= b.top) b else a
            val height = maxOf(a.height(), b.height()).coerceAtLeast(1)
            val gap = lower.top - upper.bottom
            if (overlapsH && gap < height * 1.6f && gap > -height) {
                parent[find(i)] = find(j)
            }
        }
        return lines.indices.groupBy { find(it) }.values.map { idx -> idx.map { lines[it] } }
    }

    /** 1 = lime (trip bubble), 2 = blue (pickup bubble), 0 = unknown. Samples just outside the text box. */
    private fun classifyColor(bitmap: Bitmap, box: Rect): Int {
        val off = maxOf(6, box.height() / 5)
        val midX = (box.left + box.right) / 2
        val midY = (box.top + box.bottom) / 2
        val pts = listOf(
            midX to box.top - off,
            midX to box.bottom + off,
            box.left - off to midY,
            box.right + off to midY,
            box.left - off to box.top - off,
            box.right + off to box.bottom + off
        )
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for ((x, y) in pts) {
            val cx = x.coerceIn(0, bitmap.width - 1)
            val cy = y.coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(cx, cy)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return 0
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        val hue = hsv[0]; val sat = hsv[1]; val v = hsv[2]
        return when {
            hue in 60f..110f && sat > 0.45f && v > 0.6f -> 1
            hue in 190f..250f && sat > 0.4f -> 2
            else -> 0
        }
    }

    private fun parseFromBitmap(bitmap: Bitmap, regionFraction: RectF, mapBounds: Rect): TripKmResult {
        recordFailure("trip_box_missing")
        return TripKmResult(null, "trip_box_missing")
    }

    private fun recordFailure(reason: String) {
        scope.launch {
            try {
                if (AppGraph.isInitialized) {
                    AppGraph.settingsStore.incrementDiagnosticCounter("ocr_fail:$reason")
                    AppGraph.settingsStore.incrementDiagnosticCounter("ocr_fail:indrive:$reason")
                }
            } catch (_: Exception) {}
        }
    }
}
