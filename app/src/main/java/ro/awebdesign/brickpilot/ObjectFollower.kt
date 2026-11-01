package ro.awebdesign.brickpilot

import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.ROWS
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Urmărește cel mai apropiat obiect din grila de podea: virează spre el și păstrează o distanță fixă.
 * Fără țintă stă pe loc.
 */
class ObjectFollower {

    enum class Mode { FOLLOW, HOLD, NO_TARGET }

    @Volatile var mode = Mode.NO_TARGET
        private set

    /** Direcția țintei, netezită, -1 stânga .. 1 dreapta. */
    @Volatile var bearing = 0f
        private set

    /** Distanța până la țintă, netezită, 0 lipit de robot .. 1 departe. */
    @Volatile var distance = 1f
        private set

    /** Factor de viteză, 1.0 = valorile de bază. */
    @Volatile var speedFactor = 1f

    private var hasTarget = false
    // Câte cadre la rând lipsește ținta; un cadru ratat nu oprește urmărirea
    private var missed = 0
    private var backStartMs = 0L
    // După mersul înapoi maxim nu mai dăm înapoi până nu se îndepărtează ținta
    private var backBlocked = false

    fun reset() {
        mode = Mode.NO_TARGET
        hasTarget = false
        missed = 0
        backStartMs = 0L
        backBlocked = false
    }

    fun decide(r: FloorResult, nowMs: Long): Avoider.Drive {
        val target = findTarget(r)
        if (target == null) {
            missed++
            if (missed > MAX_MISSED) {
                hasTarget = false
                backStartMs = 0L
                backBlocked = false
                mode = Mode.NO_TARGET
                return Avoider.Drive(0, 0)
            }
        } else {
            missed = 0
            // Netezire, ca zgomotul de la un cadru la altul să nu facă robotul să tremure
            if (hasTarget) {
                bearing += SMOOTHING * (target.first - bearing)
                distance += SMOOTHING * (target.second - distance)
            } else {
                bearing = target.first
                distance = target.second
            }
            hasTarget = true
        }
        if (!hasTarget) {
            mode = Mode.NO_TARGET
            return Avoider.Drive(0, 0)
        }

        val err = distance - TARGET_DISTANCE
        // Zonele moarte evită să meargă înainte-înapoi la fiecare cadru
        var forward = if (abs(err) < DISTANCE_DEADBAND) 0 else (err * KF).roundToInt().coerceIn(-MAX_BACK, MAX_FORWARD)
        if (err >= -DISTANCE_DEADBAND) backBlocked = false
        if (forward < 0) {
            // Dă înapoi doar pentru o țintă din față, nu pentru ceva apropiat din margine,
            // și nu la nesfârșit: un lucru care nu se îndepărtează nu e o țintă care vine spre robot
            if (backStartMs == 0L) backStartMs = nowMs
            if (nowMs - backStartMs > MAX_BACK_MS) backBlocked = true
            if (backBlocked || abs(bearing) > BACK_MAX_BEARING) forward = 0
        } else {
            backStartMs = 0L
        }
        // turn > 0 = dreapta, deci ținta din dreapta (bearing > 0) trage robotul la dreapta
        val turn = if (abs(bearing) < BEARING_DEADBAND) 0 else (bearing * KT).roundToInt()
        mode = if (forward == 0 && turn == 0) Mode.HOLD else Mode.FOLLOW
        return Avoider.Drive((forward * speedFactor).roundToInt(), (turn * speedFactor).roundToInt())
    }

    // Ținta: coloanele în care obstacolul e cel mai aproape de robot. Întoarce (direcție, distanță)
    private fun findTarget(r: FloorResult): Pair<Float, Float>? {
        val freeRows = IntArray(COLS)
        var nearest = ROWS
        for (c in 0 until COLS) {
            var rows = 0
            for (row in ROWS - 1 downTo 0) {
                if (r.obstacle[row * COLS + c]) break
                rows++
            }
            freeRows[c] = rows
            if (rows < nearest) nearest = rows
        }
        if (nearest >= ROWS) return null

        var sum = 0f
        var count = 0
        for (c in 0 until COLS) {
            if (freeRows[c] < ROWS && freeRows[c] <= nearest + 1) {
                sum += (c + 0.5f) / COLS * 2f - 1f
                count++
            }
        }
        return Pair(sum / count, nearest / ROWS.toFloat())
    }

    companion object {
        // Câte rânduri libere vrem între robot și țintă (2 din 7)
        const val TARGET_DISTANCE = 2f / 7f
        const val DISTANCE_DEADBAND = 0.1f
        const val BEARING_DEADBAND = 0.15f
        const val KF = 80f
        const val KT = 40f
        const val MAX_FORWARD = 40
        const val MAX_BACK = 25
        const val SMOOTHING = 0.35f
        const val MAX_MISSED = 5
        const val MAX_BACK_MS = 1500L
        const val BACK_MAX_BEARING = 0.35f
    }
}
