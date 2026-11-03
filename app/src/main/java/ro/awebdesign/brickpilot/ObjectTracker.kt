package ro.awebdesign.brickpilot

import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.FINE_COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.FINE_ROWS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.ROWS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.TOP_IGNORE
import kotlin.math.abs
import kotlin.math.hypot

/** Ținta găsită: direcție -1..1, distanță 0..1 ca în grila de podea, chenarul în fracții din cadru. */
class TrackedTarget(
    val bearing: Float,
    val distance: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Recunoaște ținta după culoare. Când nu are încă o țintă, ia culoarea celui mai apropiat obiect din
 * grila de podea și o ține minte. Apoi, la fiecare cadru, caută în toată imaginea zona de acea culoare
 * care nu seamănă cu podeaua, cea mai apropiată de locul unde a fost ținta în cadrul anterior.
 * Alte obstacole (pereți, mobilă, umbre) nu mai fură ținta, iar culoarea se adaptează încet la lumină.
 */
class ObjectTracker {

    @Volatile var locked = false
        private set

    private var targetY = 0f
    private var targetU = 0f
    private var targetV = 0f
    private var lastCol = FINE_COLS / 2f
    private var lastRow = FINE_ROWS / 2f
    private var missed = 0

    private val mask = BooleanArray(FINE_COLS * FINE_ROWS)
    private val seen = BooleanArray(FINE_COLS * FINE_ROWS)
    private val queue = IntArray(FINE_COLS * FINE_ROWS)

    fun reset() {
        locked = false
        missed = 0
    }

    fun track(r: FloorResult): TrackedTarget? {
        if (r.fineU.size != FINE_COLS * FINE_ROWS) return null
        if (!locked && !acquire(r)) return null

        for (i in mask.indices) {
            mask[i] = colorDist(r, i) < COLOR_TOL && floorDist(r, i) > FLOOR_SEP
        }
        seen.fill(false)

        var best: Blob? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val blob = flood(r, start)
            if (blob.size < MIN_BLOB) continue
            // Mare și aproape de locul unde a fost ținta: un obiect de aceeași culoare în altă parte pierde
            val score = blob.size - JUMP_WEIGHT * hypot(blob.col - lastCol, blob.row - lastRow)
            if (score > bestScore) {
                bestScore = score
                best = blob
            }
        }

        val b = best
        if (b == null) {
            if (++missed > LOST_FRAMES) locked = false
            return null
        }
        missed = 0
        lastCol = b.col
        lastRow = b.row
        // Lumina se schimbă pe drum; culoarea ținută minte o urmează încet
        targetY += ADAPT * (b.y - targetY)
        targetU += ADAPT * (b.u - targetU)
        targetV += ADAPT * (b.v - targetV)

        val bottom = (b.maxRow + 1f) / FINE_ROWS
        return TrackedTarget(
            bearing = (b.col + 0.5f) / FINE_COLS * 2f - 1f,
            distance = ((1f - bottom) / (1f - TOP_IGNORE)).coerceIn(0f, 1f),
            left = b.minCol / FINE_COLS.toFloat(),
            top = b.minRow / FINE_ROWS.toFloat(),
            right = (b.maxCol + 1f) / FINE_COLS,
            bottom = bottom
        )
    }

    private class Blob(
        val size: Int, val col: Float, val row: Float,
        val minCol: Int, val maxCol: Int, val minRow: Int, val maxRow: Int,
        val y: Float, val u: Float, val v: Float
    )

    private fun flood(r: FloorResult, start: Int): Blob {
        var head = 0
        var tail = 0
        queue[tail++] = start
        seen[start] = true
        var sc = 0f
        var sr = 0f
        var sy = 0f
        var su = 0f
        var sv = 0f
        var minC = FINE_COLS
        var maxC = -1
        var minR = FINE_ROWS
        var maxR = -1
        while (head < tail) {
            val i = queue[head++]
            val c = i % FINE_COLS
            val row = i / FINE_COLS
            sc += c
            sr += row
            sy += r.fineY[i]
            su += r.fineU[i]
            sv += r.fineV[i]
            if (c < minC) minC = c
            if (c > maxC) maxC = c
            if (row < minR) minR = row
            if (row > maxR) maxR = row
            for (n in intArrayOf(i - 1, i + 1, i - FINE_COLS, i + FINE_COLS)) {
                if (n < 0 || n >= mask.size || seen[n] || !mask[n]) continue
                // Fără trecere de la marginea dreaptă la cea stângă a rândului următor
                if (abs(n % FINE_COLS - c) > 1) continue
                seen[n] = true
                queue[tail++] = n
            }
        }
        val n = tail.toFloat()
        return Blob(tail, sc / n, sr / n, minC, maxC, minR, maxR, sy / n, su / n, sv / n)
    }

    /** Culoarea țintei: celulele fine din obiectul cel mai apropiat din grila de podea. */
    private fun acquire(r: FloorResult): Boolean {
        // Pe fiecare coloană a grilei, primul obstacol de jos în sus; ținem coloanele cele mai apropiate
        val hitRow = IntArray(COLS) { -1 }
        var nearestRow = -1
        for (c in 0 until COLS) {
            for (row in ROWS - 1 downTo 0) {
                if (r.obstacle[row * COLS + c]) {
                    hitRow[c] = row
                    break
                }
            }
            if (hitRow[c] > nearestRow) nearestRow = hitRow[c]
        }
        if (nearestRow < 0) return false

        val ys = ArrayList<Int>()
        val us = ArrayList<Int>()
        val vs = ArrayList<Int>()
        var colSum = 0f
        var rowSum = 0f
        for (c in 0 until COLS) {
            if (hitRow[c] < 0 || hitRow[c] < nearestRow - 1) continue
            val row = hitRow[c]
            val x0 = c / COLS.toFloat()
            val x1 = (c + 1) / COLS.toFloat()
            val y0 = TOP_IGNORE + (1f - TOP_IGNORE) * row / ROWS
            val y1 = TOP_IGNORE + (1f - TOP_IGNORE) * (row + 1) / ROWS
            for (fr in (y0 * FINE_ROWS).toInt() until (y1 * FINE_ROWS).toInt().coerceAtMost(FINE_ROWS)) {
                for (fc in (x0 * FINE_COLS).toInt() until (x1 * FINE_COLS).toInt().coerceAtMost(FINE_COLS)) {
                    val i = fr * FINE_COLS + fc
                    if (floorDist(r, i) <= FLOOR_SEP) continue
                    ys.add(r.fineY[i])
                    us.add(r.fineU[i])
                    vs.add(r.fineV[i])
                    colSum += fc
                    rowSum += fr
                }
            }
        }
        if (ys.size < MIN_SEED) return false
        val y = median(ys)
        val u = median(us)
        val v = median(vs)
        // O țintă care diferă de podea doar prin luminozitate e greu de urmărit; cerem o diferență clară
        val chroma = abs(u - r.refU) + abs(v - r.refV)
        if (chroma < MIN_CHROMA && abs(y - r.refY) < MIN_LUMA) return false
        targetY = y
        targetU = u
        targetV = v
        lastCol = colSum / ys.size
        lastRow = rowSum / ys.size
        missed = 0
        locked = true
        return true
    }

    private fun median(a: ArrayList<Int>): Float {
        a.sort()
        return a[a.size / 2].toFloat()
    }

    // Umbrele schimbă mai ales luminozitatea, deci ea contează mai puțin decât culoarea
    private fun colorDist(r: FloorResult, i: Int) =
        abs(r.fineU[i] - targetU) + abs(r.fineV[i] - targetV) + LUMA_WEIGHT * abs(r.fineY[i] - targetY)

    private fun floorDist(r: FloorResult, i: Int) =
        abs(r.fineU[i] - r.refU) + abs(r.fineV[i] - r.refV) + 0.4f * abs(r.fineY[i] - r.refY)

    companion object {
        const val COLOR_TOL = 24f
        const val FLOOR_SEP = 14f
        const val LUMA_WEIGHT = 0.3f
        const val MIN_BLOB = 3
        const val MIN_SEED = 4
        const val MIN_CHROMA = 12f
        const val MIN_LUMA = 45f
        const val JUMP_WEIGHT = 1.5f
        const val ADAPT = 0.1f
        // Cadre la rând fără țintă după care o uităm și alegem din nou cel mai apropiat obiect
        const val LOST_FRAMES = 20
    }
}
