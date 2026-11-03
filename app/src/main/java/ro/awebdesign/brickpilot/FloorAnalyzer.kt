package ro.awebdesign.brickpilot

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min

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
    val fineV: IntArray = IntArray(0),
    // Distanța până la primul obstacol din față, văzută de sus: 0 lipit de robot .. 1 nimic în zona văzută
    val frontDistance: Float = 1f
)

/**
 * Segmentare de podea cu OpenCV. Imaginea mică YUV e curățată cu un filtru median (rosturi, textură,
 * zgomot), apoi fiecare celulă din grilă e comparată cu podeaua din fața roților. Separat, podeaua liberă
 * e umplută pixel cu pixel din fața robotului (flood fill), iar marginea ei din față, văzută de sus, dă
 * distanța până la primul obstacol sau perete.
 */
class FloorAnalyzer(private val onResult: (FloorResult) -> Unit) : ImageAnalysis.Analyzer {

    @Volatile var threshold: Float = DEFAULT_THRESHOLD
    private var lastLogMs = 0L

    private val meanY = FloatArray(COLS * ROWS)
    private val meanU = FloatArray(COLS * ROWS)
    private val meanV = FloatArray(COLS * ROWS)

    private val yRaw = Mat()
    private val uRaw = Mat()
    private val vRaw = Mat()
    private val ySmall = Mat()
    private val uSmall = Mat()
    private val vSmall = Mat()
    private val yuvRaw = Mat()
    private val yuv = Mat()
    private val blurred = Mat()
    private val cells = Mat()
    private val fine = Mat()
    private val mask = Mat()

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val w = image.width
        val h = image.height
        val rot = image.imageInfo.rotationDegrees
        val planes = image.planes
        copyPlane(planes[0], w, h, yRaw)
        copyPlane(planes[1], w / 2, h / 2, uRaw)
        copyPlane(planes[2], w / 2, h / 2, vRaw)

        // Imagine mică, cu culoarea la aceeași rezoluție ca luminanța
        val size = Size((w / WORK_DIV).toDouble(), (h / WORK_DIV).toDouble())
        Imgproc.resize(yRaw, ySmall, size, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(uRaw, uSmall, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(vRaw, vSmall, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.merge(listOf(ySmall, uSmall, vSmall), yuvRaw)
        when (rot) {
            90 -> Core.rotate(yuvRaw, yuv, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(yuvRaw, yuv, Core.ROTATE_180)
            270 -> Core.rotate(yuvRaw, yuv, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> yuvRaw.copyTo(yuv)
        }
        val upW = if (rot % 180 == 90) h else w
        val upH = if (rot % 180 == 90) w else h
        onResult(analyzeYuv(yuv, upW, upH, System.currentTimeMillis()))
    }

    // Copiază un plan din ImageProxy într-un Mat cu un singur canal, ținând cont de pași
    private fun copyPlane(plane: ImageProxy.PlaneProxy, w: Int, h: Int, dst: Mat) {
        if (dst.rows() != h || dst.cols() != w || dst.type() != CvType.CV_8UC1) dst.create(h, w, CvType.CV_8UC1)
        val buf = plane.buffer
        val row = ByteArray(w)
        for (y in 0 until h) {
            val base = y * plane.rowStride
            if (plane.pixelStride == 1) {
                buf.position(base)
                buf.get(row, 0, w)
            } else {
                for (x in 0 until w) row[x] = buf.get(base + x * plane.pixelStride)
            }
            dst.put(y, 0, row)
        }
    }

    /** Imaginea YUV dreaptă, cu 3 canale, la rezoluție mică. frameWidth/Height: cadrul original. */
    fun analyzeYuv(img: Mat, frameWidth: Int, frameHeight: Int, now: Long): FloorResult {
        Imgproc.medianBlur(img, blurred, 5)
        val w = blurred.cols()
        val h = blurred.rows()
        val top = (h * TOP_IGNORE).toInt()
        val roi = blurred.submat(top, h, 0, w)

        // Media fiecărei celule din grilă, după curățare
        Imgproc.resize(roi, cells, Size(COLS.toDouble(), ROWS.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val cb = ByteArray(COLS * ROWS * 3)
        cells.get(0, 0, cb)
        for (i in 0 until COLS * ROWS) {
            meanY[i] = (cb[3 * i].toInt() and 0xFF).toFloat()
            meanU[i] = (cb[3 * i + 1].toInt() and 0xFF).toFloat()
            meanV[i] = (cb[3 * i + 2].toInt() and 0xFF).toFloat()
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

        val front = frontDistance(roi, w, h, top)
        roi.release()

        // Grila fină pentru recunoașterea țintei după culoare
        Imgproc.resize(blurred, fine, Size(FINE_COLS.toDouble(), FINE_ROWS.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val fb = ByteArray(FINE_COLS * FINE_ROWS * 3)
        fine.get(0, 0, fb)
        val fy = IntArray(FINE_COLS * FINE_ROWS) { fb[3 * it].toInt() and 0xFF }
        val fu = IntArray(FINE_COLS * FINE_ROWS) { fb[3 * it + 1].toInt() and 0xFF }
        val fv = IntArray(FINE_COLS * FINE_ROWS) { fb[3 * it + 2].toInt() and 0xFF }

        return FloorResult(
            obstacle, cls.distance, refY, refU, refV, free, frameWidth, frameHeight, now, fy, fu, fv, front
        )
    }

    /**
     * Podeaua liberă, umplută din fața robotului: un pixel e podea dacă seamănă cu vecinul lui de podea,
     * deci trecerile treptate de lumină rămân podea. Pe treimea din mijloc căutăm, de jos în sus, primul
     * pixel care nu mai e podea; cel mai apropiat, văzut de sus, e distanța până la obstacol (0..1).
     */
    private fun frontDistance(roi: Mat, w: Int, h: Int, top: Int): Float {
        val rh = roi.rows()
        mask.create(rh + 2, w + 2, CvType.CV_8UC1)
        mask.setTo(Scalar(0.0))
        val thr = threshold.toDouble()
        val diff = Scalar(thr * FILL_Y, thr * FILL_UV, thr * FILL_UV)
        Imgproc.floodFill(
            roi, mask, Point(w / 2.0, rh - 2.0), Scalar(0.0), null, diff, diff,
            4 or Imgproc.FLOODFILL_MASK_ONLY or (255 shl 8)
        )
        val mb = ByteArray((rh + 2) * (w + 2))
        mask.get(0, 0, mb)
        val pts = ArrayList<Point>()
        var x = w / 3
        while (x < 2 * w / 3) {
            for (y in rh - 1 downTo 0) {
                if (mb[(y + 1) * (w + 2) + x + 1].toInt() == 0) {
                    pts.add(Point(x.toDouble(), (y + top).toDouble()))
                    break
                }
            }
            x += 2
        }
        if (pts.isEmpty()) return 1f
        val g = GroundView.toGround(pts, w, h, top)
        var nearest = 1f
        for (i in 0 until g.size / 2) nearest = min(nearest, g[2 * i + 1])
        return nearest.coerceIn(0f, 1f)
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
        const val REF_FIRST_COL = 3
        const val REF_COUNT = 3
        const val DEFAULT_THRESHOLD = 14f
        // Cât de departe de referință poate ajunge podeaua prin treceri treptate, în multipli de prag
        const val GLOBAL_FACTOR = 3.5f
        const val FINE_COLS = 40
        const val FINE_ROWS = 30
        // Imaginea de lucru: cadrul împărțit la atât pe fiecare latură
        const val WORK_DIV = 4
        // Toleranța umplerii podelei, în multipli de prag; luminozitatea variază mai mult decât culoarea
        const val FILL_Y = 1.5
        const val FILL_UV = 0.6
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
