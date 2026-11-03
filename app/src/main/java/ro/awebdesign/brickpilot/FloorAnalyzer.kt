package ro.awebdesign.brickpilot

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Rezultatul unui cadru: grila clasificată și spațiul liber pe cele trei treimi. */
class FloorResult(
    val obstacle: BooleanArray,     // COLS * ROWS, rând major, rândul 0 e cel de sus
    val distance: FloatArray,       // distanța fiecărei celule față de podea, pentru calibrare
    val refY: Float,
    val refU: Float,
    val refV: Float,
    val free: FloatArray,           // stânga, centru, dreapta, 0..1
    val frameWidth: Int,            // dimensiuni după rotire, așa cum le vede utilizatorul
    val frameHeight: Int,
    val timestampMs: Long,
    // Imaginea întreagă la rezoluție mică (FINE_COLS x FINE_ROWS, rând major, rândul 0 sus),
    // pentru recunoașterea țintei după culoare în modul urmărire
    val fineY: IntArray = IntArray(0),
    val fineU: IntArray = IntArray(0),
    val fineV: IntArray = IntArray(0)
)

/**
 * Segmentare de podea fără ML: compară fiecare celulă din grilă cu podeaua
 * din fața roților, în spațiul YUV.
 */
class FloorAnalyzer(private val onResult: (FloorResult) -> Unit) : ImageAnalysis.Analyzer {

    @Volatile var threshold: Float = DEFAULT_THRESHOLD
    private var lastLogMs = 0L

    private val sy = IntArray(SAMPLES * SAMPLES)
    private val su = IntArray(SAMPLES * SAMPLES)
    private val sv = IntArray(SAMPLES * SAMPLES)

    private val meanY = FloatArray(COLS * ROWS)
    private val meanU = FloatArray(COLS * ROWS)
    private val meanV = FloatArray(COLS * ROWS)

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val rawW = image.width
        val rawH = image.height
        val rot = image.imageInfo.rotationDegrees
        val upW = if (rot == 90 || rot == 270) rawH else rawW
        val upH = if (rot == 90 || rot == 270) rawW else rawH

        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride

        val top = upH * TOP_IGNORE
        val cellW = upW / COLS.toFloat()
        val cellH = (upH - top) / ROWS

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                var k = 0
                for (j in 0 until SAMPLES) {
                    val uy = (top + cellH * r + cellH * (j + 0.5f) / SAMPLES).toInt().coerceIn(0, upH - 1)
                    for (i in 0 until SAMPLES) {
                        val ux = (cellW * c + cellW * (i + 0.5f) / SAMPLES).toInt().coerceIn(0, upW - 1)
                        // Din coordonate verticale în coordonatele brute ale senzorului
                        val x: Int
                        val y: Int
                        when (rot) {
                            90 -> { x = uy; y = rawH - 1 - ux }
                            180 -> { x = rawW - 1 - ux; y = rawH - 1 - uy }
                            270 -> { x = rawW - 1 - uy; y = ux }
                            else -> { x = ux; y = uy }
                        }
                        sy[k] = yBuf.get(y * yRow + x * yPix).toInt() and 0xFF
                        val cx = x shr 1
                        val cy = y shr 1
                        su[k] = uBuf.get(cy * uRow + cx * uPix).toInt() and 0xFF
                        sv[k] = vBuf.get(cy * vRow + cx * vPix).toInt() and 0xFF
                        k++
                    }
                }
                // Mediana, nu media: liniile subțiri dintre plăcile de parchet nu mai trag valoarea
                val idx = r * COLS + c
                meanY[idx] = median(sy)
                meanU[idx] = median(su)
                meanV[idx] = median(sv)
            }
        }

        // Referința: cele 3 celule din mijlocul rândului de jos
        val bottom = (ROWS - 1) * COLS
        var refY = 0f
        var refU = 0f
        var refV = 0f
        for (c in REF_FIRST_COL until REF_FIRST_COL + REF_COUNT) {
            refY += meanY[bottom + c]
            refU += meanU[bottom + c]
            refV += meanV[bottom + c]
        }
        refY /= REF_COUNT
        refU /= REF_COUNT
        refV /= REF_COUNT

        val cls = classify(meanY, meanU, meanV, refY, refU, refV, threshold)
        val obstacle = cls.obstacle
        val distance = cls.distance

        val now = System.currentTimeMillis()
        if (now - lastLogMs > 1000) {
            lastLogMs = now
            logGrid(refY, refU, refV)
        }

        val free = FloatArray(3)
        val perThird = COLS / 3
        for (t in 0 until 3) {
            var rows = 0
            loop@ for (r in ROWS - 1 downTo 0) {
                for (c in t * perThird until (t + 1) * perThird) {
                    if (obstacle[r * COLS + c]) break@loop
                }
                rows++
            }
            free[t] = rows / ROWS.toFloat()
        }

        // Grila fină pentru urmărirea țintei: media a 2x2 pixeli în fiecare punct
        val fy = IntArray(FINE_COLS * FINE_ROWS)
        val fu = IntArray(FINE_COLS * FINE_ROWS)
        val fv = IntArray(FINE_COLS * FINE_ROWS)
        for (r in 0 until FINE_ROWS) {
            for (c in 0 until FINE_COLS) {
                var ty = 0
                var tu = 0
                var tv = 0
                for (j in 0..1) for (i in 0..1) {
                    val ux = ((c + 0.25f + i * 0.5f) * upW / FINE_COLS).toInt().coerceIn(0, upW - 1)
                    val uy = ((r + 0.25f + j * 0.5f) * upH / FINE_ROWS).toInt().coerceIn(0, upH - 1)
                    val x: Int
                    val y: Int
                    when (rot) {
                        90 -> { x = uy; y = rawH - 1 - ux }
                        180 -> { x = rawW - 1 - ux; y = rawH - 1 - uy }
                        270 -> { x = rawW - 1 - uy; y = ux }
                        else -> { x = ux; y = uy }
                    }
                    ty += yBuf.get(y * yRow + x * yPix).toInt() and 0xFF
                    tu += uBuf.get((y shr 1) * uRow + (x shr 1) * uPix).toInt() and 0xFF
                    tv += vBuf.get((y shr 1) * vRow + (x shr 1) * vPix).toInt() and 0xFF
                }
                val k = r * FINE_COLS + c
                fy[k] = ty / 4
                fu[k] = tu / 4
                fv[k] = tv / 4
            }
        }

        onResult(FloorResult(obstacle, distance, refY, refU, refV, free, upW, upH, now, fy, fu, fv))
    }

    private fun median(a: IntArray): Float {
        a.sort()
        return a[a.size / 2].toFloat()
    }

    // Valorile Y/U/V pe celule, o dată pe secundă, pentru calibrare prin logcat
    private fun logGrid(refY: Float, refU: Float, refV: Float) {
        Log.d(TAG, "ref Y=%.0f U=%.0f V=%.0f thr=%.0f".format(refY, refU, refV, threshold))
        for (r in 0 until ROWS) {
            val row = (0 until COLS).joinToString(" ") { c ->
                val i = r * COLS + c
                "%3.0f/%3.0f/%3.0f".format(meanY[i], meanU[i], meanV[i])
            }
            Log.d(TAG, "row $r: $row")
        }
    }

    class Classification(val obstacle: BooleanArray, val distance: FloatArray)

    companion object {
        const val COLS = 9
        const val ROWS = 7
        const val TOP_IGNORE = 0.35f
        const val SAMPLES = 5
        const val REF_FIRST_COL = 3
        const val REF_COUNT = 3
        const val DEFAULT_THRESHOLD = 14f
        // Cât de departe de referință poate ajunge podeaua prin treceri treptate, în multipli de prag
        const val GLOBAL_FACTOR = 3.5f
        const val FINE_COLS = 40
        const val FINE_ROWS = 30
        private const val TAG = "BrickPilot"

        // Chroma cântărește mai mult, e stabilă la umbre
        private fun dist(dy: Float, du: Float, dv: Float) = 0.4f * abs(dy) + abs(du) + abs(dv)

        /**
         * Podeaua e urmărită pornind de la referință: rândul de jos din centru spre margini, apoi fiecare
         * coloană de jos în sus. O celulă e podea dacă seamănă cu podeaua de lângă ea, deci trecerile
         * treptate de lumină (reflexii pe parchet lucios, umbre largi) rămân podea, iar marginile bruște
         * ale obiectelor devin obstacol. distance e numărul comparat cu pragul.
         */
        fun classify(
            y: FloatArray, u: FloatArray, v: FloatArray,
            refY: Float, refU: Float, refV: Float, thr: Float
        ): Classification {
            val over = BooleanArray(COLS * ROWS)
            val distance = FloatArray(COLS * ROWS)
            // Modelul de podea curent: ultima celulă de podea întâlnită pe drum
            val model = FloatArray(3)

            fun visit(i: Int) {
                val local = dist(y[i] - model[0], u[i] - model[1], v[i] - model[2])
                // Plafonul față de referință împiedică adaptarea să „alunece” treptat pe un obiect
                val global = dist(y[i] - refY, u[i] - refU, v[i] - refV) / GLOBAL_FACTOR
                distance[i] = maxOf(local, global)
                over[i] = distance[i] > thr
                if (!over[i]) {
                    model[0] = y[i]
                    model[1] = u[i]
                    model[2] = v[i]
                }
            }

            // Rândul de jos, din centru spre dreapta și spre stânga; baza fiecărei coloane
            val base = Array(COLS) { FloatArray(3) }
            val bottom = (ROWS - 1) * COLS
            for (side in listOf(COLS / 2 until COLS, COLS / 2 - 1 downTo 0)) {
                model[0] = refY
                model[1] = refU
                model[2] = refV
                for (c in side) {
                    visit(bottom + c)
                    model.copyInto(base[c])
                }
            }

            // Fiecare coloană, de jos în sus
            for (c in 0 until COLS) {
                base[c].copyInto(model)
                for (r in ROWS - 2 downTo 0) visit(r * COLS + c)
            }

            // O celulă izolată e de obicei o pată de pe podea; obstacolele reale ocupă celule vecine
            val obstacle = BooleanArray(COLS * ROWS)
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    val i = r * COLS + c
                    if (!over[i]) continue
                    obstacle[i] = (r > 0 && over[i - COLS]) || (r < ROWS - 1 && over[i + COLS]) ||
                        (c > 0 && over[i - 1]) || (c < COLS - 1 && over[i + 1])
                }
            }
            return Classification(obstacle, distance)
        }
    }
}
