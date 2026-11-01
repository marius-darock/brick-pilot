package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Decizia pentru urmărirea liniei: regulator PD pe poziția netezită a liniei, plus căutare când se pierde. */
class LineFollower {

    enum class Mode { FOLLOW, SEARCH, LOST }

    @Volatile var mode = Mode.FOLLOW
        private set

    /** Factor de viteză, 1.0 = valorile de bază. */
    @Volatile var speedFactor = 1f

    private var filtered = Float.NaN
    private var lastErr = 0f
    private var lastMs = 0L
    private var lastSeenMs = 0L
    // +1 = dreapta, -1 = stânga; partea în care a fost linia ultima dată
    private var lastSide = 0

    fun reset() {
        mode = Mode.FOLLOW
        filtered = Float.NaN
        lastErr = 0f
        lastMs = 0L
        lastSeenMs = 0L
        lastSide = 0
    }

    fun decide(r: LineResult, nowMs: Long): Avoider.Drive {
        val raw = position(r)
        if (raw.isNaN()) {
            lastMs = 0L
            filtered = Float.NaN
            // Linia a ieșit din imagine: ne rotim spre partea unde a fost ultima dată
            if (lastSide != 0 && lastSeenMs > 0 && nowMs - lastSeenMs < SEARCH_MS) {
                mode = Mode.SEARCH
                return drive(0, SEARCH_TURN * lastSide)
            }
            mode = Mode.LOST
            return drive(0, 0)
        }

        // Netezire, ca un cadru zgomotos să nu dea o smucitură
        val err = if (filtered.isNaN()) raw else filtered + SMOOTHING * (raw - filtered)
        filtered = err
        val dt = (nowMs - lastMs) / 1000f
        val dErr = if (lastMs > 0 && dt > 0f && dt < 0.3f) (err - lastErr) / dt else 0f
        lastErr = err
        lastMs = nowMs
        lastSeenMs = nowMs
        if (abs(err) > SIDE_DEADBAND) lastSide = if (err > 0) 1 else -1
        mode = Mode.FOLLOW

        // turn > 0 = dreapta, deci linia din dreapta (err > 0) trage robotul la dreapta
        val turn = (KP * err + KD * dErr).roundToInt().coerceIn(-MAX_TURN, MAX_TURN)
        // În curbe strânse încetinim, ca linia să nu iasă din imagine
        val forward = (BASE_FORWARD * (1f - SLOWDOWN * min(1f, abs(err)))).roundToInt()
        return drive(forward, turn)
    }

    private fun drive(forward: Int, turn: Int) =
        Avoider.Drive((forward * speedFactor).roundToInt(), (turn * speedFactor).roundToInt())

    companion object {
        // Ponderi pe benzi, de jos în sus (aceeași ordine ca LineDetector.BAND_Y)
        val BAND_WEIGHTS = floatArrayOf(0.35f, 0.25f, 0.2f, 0.12f, 0.08f)

        const val BASE_FORWARD = 30
        const val SLOWDOWN = 0.6f
        const val KP = 45f
        const val KD = 3f
        const val MAX_TURN = 45
        const val SMOOTHING = 0.6f
        const val SIDE_DEADBAND = 0.1f
        const val SEARCH_TURN = 35
        const val SEARCH_MS = 2000L

        /** Poziția ponderată a liniei, NaN dacă nu e pe nicio bandă. */
        fun position(r: LineResult): Float {
            // Benzile apropiate contează mai mult, cele îndepărtate anticipează curbele
            var sum = 0f
            var weights = 0f
            for (b in r.positions.indices) {
                val p = r.positions[b]
                if (p.isNaN()) continue
                sum += p * BAND_WEIGHTS[b]
                weights += BAND_WEIGHTS[b]
            }
            return if (weights == 0f) Float.NaN else sum / weights
        }
    }
}
