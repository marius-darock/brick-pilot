package ro.awebdesign.brickpilot

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Poziția liniei pe fiecare bandă orizontală, -1 stânga .. 1 dreapta, NaN dacă lipsește. */
class LineResult(
    val positions: FloatArray,      // de jos în sus, ca în BAND_Y
    val frameWidth: Int,
    val frameHeight: Int,
    val timestampMs: Long
) {
    val found get() = positions.any { !it.isNaN() }
}

/**
 * Caută o linie neagră pe podea: pe câteva benzi orizontale din partea de jos a cadrului
 * găsește segmentul mult mai închis decât restul benzii, legat de segmentul de pe banda de dedesubt.
 */
class LineDetector(private val onResult: (LineResult) -> Unit) : ImageAnalysis.Analyzer {

    private val values = IntArray(SAMPLES_X)
    private val sorted = IntArray(SAMPLES_X)
    private val column = IntArray(3)
    private val lastPos = FloatArray(BANDS) { Float.NaN }

    fun reset() = lastPos.fill(Float.NaN)

    override fun analyze(image: ImageProxy) {
        try {
            val rawW = image.width
            val rawH = image.height
            val rot = image.imageInfo.rotationDegrees
            val upW = if (rot == 90 || rot == 270) rawH else rawW
            val upH = if (rot == 90 || rot == 270) rawW else rawH

            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride

            // Doar luminanța: o linie neagră se vede clar în Y
            val positions = detect(upW, upH) { ux, uy ->
                val x: Int
                val y: Int
                when (rot) {
                    90 -> { x = uy; y = rawH - 1 - ux }
                    180 -> { x = rawW - 1 - ux; y = rawH - 1 - uy }
                    270 -> { x = rawW - 1 - uy; y = ux }
                    else -> { x = ux; y = uy }
                }
                buf.get(y * rowStride + x * pixStride).toInt() and 0xFF
            }
            onResult(LineResult(positions, upW, upH, System.currentTimeMillis()))
        } finally {
            image.close()
        }
    }

    /** lum(x, y) dă luminanța 0..255 în cadrul văzut drept, de lățime width și înălțime height. */
    fun detect(width: Int, height: Int, lum: (Int, Int) -> Int): FloatArray {
        val positions = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            val uy = (height * BAND_Y[b]).toInt()
            for (i in 0 until SAMPLES_X) {
                val ux = ((i + 0.5f) * width / SAMPLES_X).toInt().coerceIn(0, width - 1)
                // Mediana a 3 pixeli pe verticală, contra zgomotului și a texturii podelei
                for (k in 0 until 3) column[k] = lum(ux, (uy + (k - 1) * BAND_HALF).coerceIn(0, height - 1))
                column.sort()
                values[i] = column[1]
            }
            // Linia e continuă: pe fiecare bandă o căutăm lângă cea de dedesubt
            val below = if (b > 0 && !positions[b - 1].isNaN()) positions[b - 1] else Float.NaN
            val pos = findLine(if (below.isNaN()) lastPos[b] else below, !below.isNaN())
            positions[b] = pos
            if (!pos.isNaN()) lastPos[b] = pos
        }
        return positions
    }

    private fun findLine(near: Float, mustBeNear: Boolean): Float {
        values.copyInto(sorted)
        sorted.sort()
        // Al doilea cel mai închis eșantion: o bandă îngustă ocupă doar câteva procente din lățime,
        // deci o percentilă ar rata-o, iar un singur pixel zgomotos nu contează
        val dark = sorted[1]
        val floor = sorted[SAMPLES_X / 2]
        if (floor - dark < MIN_CONTRAST) return Float.NaN
        val t = dark + (floor - dark) / 2

        var best = Float.NaN
        var bestScore = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < SAMPLES_X) {
            if (values[i] >= t) {
                i++
                continue
            }
            val start = i
            while (i < SAMPLES_X && values[i] < t) i++
            val width = i - start
            // Prea îngust e o rostă de parchet, prea lat e o umbră sau o mobilă
            if (width < MIN_WIDTH || width > SAMPLES_X * MAX_WIDTH) continue
            val center = (start + i) / 2f / SAMPLES_X * 2f - 1f
            if (mustBeNear && abs(center - near) > MAX_JUMP) continue
            val score = if (near.isNaN()) width.toFloat() else width - abs(center - near) * SAMPLES_X
            if (score > bestScore) {
                bestScore = score
                best = center
            }
        }
        return best
    }

    companion object {
        // Benzile, ca fracție din înălțimea cadrului, de jos în sus
        val BAND_Y = floatArrayOf(0.95f, 0.85f, 0.75f, 0.65f, 0.55f)
        val BANDS = BAND_Y.size
        const val SAMPLES_X = 160
        const val BAND_HALF = 2
        const val MIN_CONTRAST = 45
        const val MIN_WIDTH = 2
        const val MAX_WIDTH = 0.4f
        // Cât se poate muta linia între două benzi vecine (din lățimea totală -1..1)
        const val MAX_JUMP = 0.35f
    }
}
