package ro.awebdesign.brickpilot

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Linia găsită într-un cadru.
 * positions: poziția liniei pe benzile din BAND_Y, -1 stânga .. 1 dreapta, NaN unde lipsește.
 * curve: linia din imagine, perechi (x -1..1, y 0..1 de sus în jos), pentru desen.
 * ground: aceeași linie văzută de sus, perechi (lateral -1..1, înainte 0..1); robotul e la (0, 0).
 */
class LineResult(
    val positions: FloatArray,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestampMs: Long,
    val curve: FloatArray = FloatArray(0),
    val ground: FloatArray = FloatArray(0)
) {
    val found get() = positions.any { !it.isNaN() }
}

/**
 * Găsește o linie închisă la culoare pe podea, cu OpenCV:
 * 1. prag adaptiv: un pixel e „închis” față de podeaua din jurul lui, deci umbrele largi și lumina
 *    inegală nu contează;
 * 2. curățare morfologică: rosturile de parchet și punctele izolate dispar;
 * 3. componente conexe: linia e un singur obiect lung și subțire, nu o pată, o umbră lată sau
 *    marginea unei umbre (închisă doar pe o parte);
 * 4. dintre candidați câștigă cel care pornește din fața robotului și e cel mai aproape de linia
 *    din cadrul anterior;
 * 5. linia aleasă e trecută în vederea de sus (perspectivă), ca regulatorul să știe geometria reală.
 */
class LineDetector(private val onResult: (LineResult) -> Unit) : ImageAnalysis.Analyzer {

    private val gray = Mat()
    private val upright = Mat()
    private val small = Mat()
    private val blurred = Mat()
    private val binary = Mat()
    private val labels = Mat()
    private val stats = Mat()
    private val centroids = Mat()
    private val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    private val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

    // Unde a pornit linia (jos, în fața robotului) în cadrul anterior, -1..1
    private var lastBottomX = Float.NaN
    private var missedFrames = 0

    fun reset() {
        lastBottomX = Float.NaN
        missedFrames = 0
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val buf = plane.buffer
            val row = ByteArray(w)
            if (gray.rows() != h || gray.cols() != w) gray.create(h, w, CvType.CV_8UC1)
            // Doar luminanța: o linie neagră se vede clar în Y
            for (y in 0 until h) {
                buf.position(y * plane.rowStride)
                if (plane.pixelStride == 1) {
                    buf.get(row, 0, w)
                } else {
                    for (x in 0 until w) row[x] = buf.get(y * plane.rowStride + x * plane.pixelStride)
                }
                gray.put(y, 0, row)
            }
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(gray, upright, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(gray, upright, Core.ROTATE_180)
                270 -> Core.rotate(gray, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> gray.copyTo(upright)
            }
            onResult(detectMat(upright, System.currentTimeMillis()))
        } finally {
            image.close()
        }
    }

    /** Pentru teste: lum(x, y) dă luminanța 0..255 în cadrul văzut drept. Întoarce pozițiile pe benzi. */
    fun detect(width: Int, height: Int, lum: (Int, Int) -> Int): FloatArray = detectFull(width, height, lum).positions

    fun detectFull(width: Int, height: Int, lum: (Int, Int) -> Int): LineResult {
        val m = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = lum(x, y).coerceIn(0, 255).toByte()
            m.put(y, 0, row)
        }
        val r = detectMat(m, 0)
        m.release()
        return r
    }

    /** Cadrul drept (luminanță), de orice mărime. */
    fun detectMat(frame: Mat, timestampMs: Long): LineResult {
        val fw = frame.cols()
        val fh = frame.rows()
        val w = WORK_WIDTH
        val h = (fh * WORK_WIDTH.toFloat() / fw).roundToInt()
        Imgproc.resize(frame, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val top = (h * ROI_TOP).toInt()
        val roi = small.submat(top, h, 0, w)
        val rh = h - top

        Imgproc.GaussianBlur(roi, blurred, Size(5.0, 5.0), 0.0)
        val block = (w * ADAPTIVE_BLOCK).toInt() or 1
        Imgproc.adaptiveThreshold(
            blurred, binary, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, ADAPTIVE_C
        )
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, openKernel)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, closeKernel)

        val n = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8)
        val gb = ByteArray(w * rh)
        blurred.get(0, 0, gb)
        val lb = IntArray(w * rh)
        labels.get(0, 0, lb)

        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        var bestRows: FloatArray? = null
        for (label in 1 until n) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0]
            val by = stats.get(label, Imgproc.CC_STAT_TOP)[0]
            if (area < w * rh * MIN_AREA) continue
            // O linie se întinde pe o bună parte din înălțime; o pată sau un capăt de linie, nu
            if (bh < rh * MIN_SPAN) continue
            // Grosimea medie: aria împărțită la lungime. Prea groasă = umbră, mobilă, nu bandă
            val thickness = area / max(1.0, hypot(bw, bh))
            if (thickness > w * MAX_THICKNESS || thickness < MIN_THICKNESS) continue

            val rows = rowCenters(lb, label, w, rh)
            if (!darkerThanBothSides(gb, rows, w, rh, thickness)) continue

            val bottomRow = (by + bh).toInt()
            val bottomX = lowestCenter(rows)
            // Pornește din fața robotului: mult mai probabil să fie linia noastră
            var score = bh.toFloat() / rh + if (bottomRow >= rh - rh * BOTTOM_ZONE) 1f else 0f
            if (!lastBottomX.isNaN() && !bottomX.isNaN()) score -= abs(bottomX - lastBottomX) * TRACK_WEIGHT
            if (score > bestScore) {
                bestScore = score
                best = label
                bestRows = rows
            }
        }
        roi.release()

        val positions = FloatArray(BAND_Y.size) { Float.NaN }
        val rows = bestRows
        if (best < 0 || rows == null) {
            if (++missedFrames > FORGET_AFTER_FRAMES) lastBottomX = Float.NaN
            return LineResult(positions, fw, fh, timestampMs)
        }
        missedFrames = 0
        lastBottomX = lowestCenter(rows)

        for (b in BAND_Y.indices) {
            val ry = (h * BAND_Y[b]).toInt() - top
            positions[b] = centerNear(rows, ry, w)
        }

        // Linia în imagine și văzută de sus
        val pts = ArrayList<Point>()
        val curve = ArrayList<Float>()
        var y = rh - 1
        while (y >= 0) {
            val cx = rows[y]
            if (!cx.isNaN()) {
                pts.add(Point(cx.toDouble(), (y + top).toDouble()))
                curve.add(cx / w * 2f - 1f)
                curve.add((y + top).toFloat() / h)
            }
            y -= CURVE_STEP
        }
        val ground = if (pts.size >= 2) GroundView.toGround(pts, w, h, top) else FloatArray(0)
        return LineResult(positions, fw, fh, timestampMs, curve.toFloatArray(), ground)
    }

    // Centrul liniei pe fiecare rând al zonei analizate (în pixeli din imaginea mică), NaN unde lipsește
    private fun rowCenters(lb: IntArray, label: Int, w: Int, rh: Int): FloatArray {
        val out = FloatArray(rh) { Float.NaN }
        for (y in 0 until rh) {
            var sum = 0
            var cnt = 0
            val base = y * w
            for (x in 0 until w) {
                if (lb[base + x] == label) {
                    sum += x
                    cnt++
                }
            }
            if (cnt > 0) out[y] = sum.toFloat() / cnt
        }
        return out
    }

    /**
     * O bandă e mai închisă decât podeaua de ambele părți. Marginea unei umbre e închisă doar pe o
     * parte, iar pragul adaptiv o marchează totuși ca o fâșie; aici o eliminăm.
     */
    private fun darkerThanBothSides(gb: ByteArray, rows: FloatArray, w: Int, rh: Int, thickness: Double): Boolean {
        val off = (thickness * 1.5 + SIDE_GAP).roundToInt()
        var checked = 0
        var good = 0
        var y = 0
        while (y < rh) {
            val c = rows[y]
            if (!c.isNaN()) {
                val cx = c.roundToInt()
                val inside = gb[y * w + cx].toInt() and 0xFF
                val l = cx - off
                val r = cx + off
                val left = if (l >= 0) gb[y * w + l].toInt() and 0xFF else -1
                val right = if (r < w) gb[y * w + r].toInt() and 0xFF else -1
                if (left >= 0 || right >= 0) {
                    checked++
                    val okL = left < 0 || left - inside >= SIDE_CONTRAST
                    val okR = right < 0 || right - inside >= SIDE_CONTRAST
                    if (okL && okR) good++
                }
            }
            y += 4
        }
        return checked > 0 && good >= checked * SIDE_OK_FRACTION
    }

    private fun lowestCenter(rows: FloatArray): Float {
        for (y in rows.indices.reversed()) if (!rows[y].isNaN()) return rows[y]
        return Float.NaN
    }

    private fun centerNear(rows: FloatArray, ry: Int, w: Int): Float {
        for (d in 0..BAND_SEARCH) {
            for (yy in intArrayOf(ry - d, ry + d)) {
                if (yy in rows.indices && !rows[yy].isNaN()) return rows[yy] / w * 2f - 1f
            }
        }
        return Float.NaN
    }

    companion object {
        // Benzile, ca fracție din înălțimea cadrului, de jos în sus (pentru desen și regulatorul simplu)
        val BAND_Y = floatArrayOf(0.95f, 0.85f, 0.75f, 0.65f, 0.55f)
        const val WORK_WIDTH = 320
        // Partea de sus a cadrului e prea departe; analizăm de aici în jos
        const val ROI_TOP = 0.45f
        // Fereastra pragului adaptiv, din lățime, și cât de închis trebuie să fie un pixel față de ea
        const val ADAPTIVE_BLOCK = 0.12f
        const val ADAPTIVE_C = 12.0
        const val MIN_AREA = 0.002
        // Linia trebuie să acopere cel puțin atât din înălțimea zonei analizate
        const val MIN_SPAN = 0.2
        const val MAX_THICKNESS = 0.12
        const val MIN_THICKNESS = 1.5
        const val SIDE_GAP = 3
        const val SIDE_CONTRAST = 25
        const val SIDE_OK_FRACTION = 0.6
        const val BOTTOM_ZONE = 0.25
        const val TRACK_WEIGHT = 0.8f
        const val FORGET_AFTER_FRAMES = 15
        const val BAND_SEARCH = 4
        const val CURVE_STEP = 4
    }
}
