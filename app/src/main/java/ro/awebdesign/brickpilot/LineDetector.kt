package ro.awebdesign.brickpilot

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * Caută o linie neagră pe podea. Pe fiecare bandă orizontală din partea de jos a cadrului găsește
 * toate segmentele mult mai închise decât podeaua din jurul lor, apoi alege lanțul de segmente care
 * se continuă de la o bandă la alta, cel mai aproape de linia din cadrul anterior.
 * Un segment singur, care nu se leagă de altele (o pată, capătul unei linii din lateral), nu contează:
 * linia trebuie văzută pe cel puțin MIN_BANDS benzi.
 */
class LineDetector(private val onResult: (LineResult) -> Unit) : ImageAnalysis.Analyzer {

    private class Segment(val center: Float, val width: Int)

    private val values = IntArray(SAMPLES_X)
    private val sorted = IntArray(SAMPLES_X)
    private val column = IntArray(ROWS_PER_BAND)
    // Linia din cadrul anterior, pe fiecare bandă; câte cadre la rând lipsește
    private val lastPos = FloatArray(BANDS) { Float.NaN }
    private var missedFrames = 0

    fun reset() {
        lastPos.fill(Float.NaN)
        missedFrames = 0
    }

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
        val candidates = Array(BANDS) { b ->
            val uy = (height * BAND_Y[b]).toInt()
            for (i in 0 until SAMPLES_X) {
                val ux = ((i + 0.5f) * width / SAMPLES_X).toInt().coerceIn(0, width - 1)
                // Mediana câtorva pixeli pe verticală, contra zgomotului și a texturii podelei
                for (k in 0 until ROWS_PER_BAND) {
                    column[k] = lum(ux, (uy + (k - ROWS_PER_BAND / 2) * ROW_STEP).coerceIn(0, height - 1))
                }
                column.sort()
                values[i] = column[ROWS_PER_BAND / 2]
            }
            findSegments()
        }

        val positions = bestChain(candidates)
        if (positions.any { !it.isNaN() }) {
            positions.copyInto(lastPos)
            missedFrames = 0
        } else if (++missedFrames > FORGET_AFTER_FRAMES) {
            // După o pierdere lungă nu mai preferăm locul vechi: linia poate reapărea oriunde
            lastPos.fill(Float.NaN)
        }
        return positions
    }

    // Toate segmentele închise de pe banda curentă, cu lățime plauzibilă și contrast față de vecini
    private fun findSegments(): List<Segment> {
        values.copyInto(sorted)
        sorted.sort()
        // Al doilea cel mai închis eșantion: o bandă îngustă ocupă doar câteva procente din lățime,
        // deci o percentilă ar rata-o, iar un singur pixel zgomotos nu contează
        val dark = sorted[1]
        val floor = sorted[SAMPLES_X / 2]
        if (floor - dark < MIN_CONTRAST) return emptyList()
        val t = dark + (floor - dark) / 2

        val out = ArrayList<Segment>()
        var i = 0
        while (i < SAMPLES_X) {
            if (values[i] >= t) {
                i++
                continue
            }
            val start = i
            var sum = 0
            while (i < SAMPLES_X && values[i] < t) sum += values[i++]
            val width = i - start
            // Prea îngust e o rostă de parchet, prea lat e o umbră sau o mobilă
            if (width < MIN_WIDTH || width > SAMPLES_X * MAX_WIDTH) continue
            // O zonă închisă lată lipită de marginea imaginii e o umbră care intră în cadru, nu o bandă
            if ((start == 0 || i == SAMPLES_X) && width > SAMPLES_X * EDGE_MAX_WIDTH) continue
            // Segmentul trebuie să fie clar mai închis decât podeaua de lângă el, pe ambele părți
            // unde există podea; o margine de umbră e închisă doar pe o parte
            val inside = sum / width
            val left = flank(start - FLANK, start)
            val right = flank(i, i + FLANK)
            val sides = listOfNotNull(left, right)
            if (sides.isEmpty() || sides.any { it - inside < MIN_CONTRAST * LOCAL_CONTRAST }) continue
            out.add(Segment((start + i) / 2f / SAMPLES_X * 2f - 1f, width))
        }
        return out
    }

    private fun flank(from: Int, to: Int): Int? {
        val a = max(0, from)
        val b = min(SAMPLES_X, to)
        if (b - a < FLANK / 2) return null
        var s = 0
        for (k in a until b) s += values[k]
        return s / (b - a)
    }

    /**
     * Lanțul de segmente de jos în sus: fiecare segment se leagă de unul de pe banda de dedesubt
     * (sau de pe cea de sub ea, dacă o bandă a fost ratată) aflat la cel mult MAX_JUMP. Câștigă lanțul
     * cel mai lung, iar dintre cele lungi cel mai aproape de linia din cadrul anterior.
     */
    private fun bestChain(candidates: Array<List<Segment>>): FloatArray {
        val score = Array(BANDS) { FloatArray(candidates[it].size) }
        val length = Array(BANDS) { IntArray(candidates[it].size) }
        val prevBand = Array(BANDS) { IntArray(candidates[it].size) { -1 } }
        val prevIdx = Array(BANDS) { IntArray(candidates[it].size) { -1 } }

        var bestBand = -1
        var bestIdx = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (b in 0 until BANDS) {
            for (k in candidates[b].indices) {
                val c = candidates[b][k].center
                // Lângă linia din cadrul anterior e mult mai probabil să fie linia noastră
                val own = 1f - if (lastPos[b].isNaN()) 0f else min(1f, abs(c - lastPos[b])) * TRACK_WEIGHT
                score[b][k] = own
                length[b][k] = 1
                for (gap in 1..2) {
                    val pb = b - gap
                    if (pb < 0) break
                    for (j in candidates[pb].indices) {
                        val jump = abs(c - candidates[pb][j].center)
                        if (jump > MAX_JUMP * gap) continue
                        val s = score[pb][j] + own - jump * JUMP_WEIGHT - (gap - 1) * GAP_PENALTY
                        if (s > score[b][k]) {
                            score[b][k] = s
                            length[b][k] = length[pb][j] + 1
                            prevBand[b][k] = pb
                            prevIdx[b][k] = j
                        }
                    }
                }
                if (length[b][k] >= MIN_BANDS && score[b][k] > bestScore) {
                    bestScore = score[b][k]
                    bestBand = b
                    bestIdx = k
                }
            }
        }

        val positions = FloatArray(BANDS) { Float.NaN }
        var b = bestBand
        var k = bestIdx
        while (b >= 0 && k >= 0) {
            positions[b] = candidates[b][k].center
            val pb = prevBand[b][k]
            k = prevIdx[b][k]
            b = pb
        }
        return positions
    }

    companion object {
        // Benzile, ca fracție din înălțimea cadrului, de jos în sus
        val BAND_Y = floatArrayOf(0.95f, 0.85f, 0.75f, 0.65f, 0.55f)
        val BANDS = BAND_Y.size
        const val SAMPLES_X = 160
        const val ROWS_PER_BAND = 5
        const val ROW_STEP = 2
        const val MIN_CONTRAST = 45
        // Cât din MIN_CONTRAST trebuie să fie diferența față de podeaua din imediata vecinătate
        const val LOCAL_CONTRAST = 0.6f
        const val FLANK = 6
        const val MIN_WIDTH = 2
        const val MAX_WIDTH = 0.4f
        const val EDGE_MAX_WIDTH = 0.1f
        // Cât se poate muta linia între două benzi vecine (din lățimea totală -1..1)
        const val MAX_JUMP = 0.35f
        // Linia trebuie văzută pe cel puțin atâtea benzi legate între ele
        const val MIN_BANDS = 2
        const val TRACK_WEIGHT = 0.6f
        const val JUMP_WEIGHT = 0.5f
        const val GAP_PENALTY = 0.3f
        const val FORGET_AFTER_FRAMES = 15
    }
}
