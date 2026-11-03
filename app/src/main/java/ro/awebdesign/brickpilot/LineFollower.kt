package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decizia pentru urmărirea liniei: regulator PD pe poziția netezită a liniei, plus căutare când se pierde. */
class LineFollower {

    enum class Mode { FOLLOW, RETRACE, SEARCH, LOST }

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
    // Drumul parcurs, ca să se poată întoarce acolo unde a văzut linia ultima dată
    private val history = CommandHistory(RETRACE_MS)
    private var searchStartMs = 0L

    fun reset() {
        mode = Mode.FOLLOW
        filtered = Float.NaN
        lastErr = 0f
        lastMs = 0L
        lastSeenMs = 0L
        lastSide = 0
        history.clear()
        searchStartMs = 0L
    }

    fun decide(r: LineResult, nowMs: Long): Avoider.Drive {
        // La viteză mare contează mai mult benzile îndepărtate: robotul reacționează la curbă înainte să ajungă în ea
        val lookahead = ((speedFactor - LOOKAHEAD_FROM) / LOOKAHEAD_SPAN).coerceIn(0f, 1f)
        val raw = position(r, lookahead)
        if (raw.isNaN()) {
            lastMs = 0L
            filtered = Float.NaN
            // Linia a ieșit din imagine: întâi refacem înapoi drumul parcurs, până o vedem din nou
            if (mode == Mode.FOLLOW && lastSeenMs > 0) {
                mode = if (history.startReplay(nowMs)) Mode.RETRACE else Mode.SEARCH
                searchStartMs = nowMs
            }
            if (mode == Mode.RETRACE) {
                val c = history.replay(nowMs)
                if (c != null) {
                    // Vitezele roților inversate refac același arc înapoi; le plafonăm păstrând raportul
                    val scale = min(1f, RETRACE_SPEED * speedFactor / max(1f, abs(c.first) + abs(c.second)))
                    return Avoider.Drive(-(c.first * scale).roundToInt(), -(c.second * scale).roundToInt())
                }
                mode = Mode.SEARCH
                searchStartMs = nowMs
            }
            // Apoi ne rotim spre partea unde a fost ultima dată
            if (mode == Mode.SEARCH && lastSide != 0 && nowMs - searchStartMs < SEARCH_MS) {
                return drive(0, SEARCH_TURN * lastSide)
            }
            mode = Mode.LOST
            return drive(0, 0)
        }
        if (mode != Mode.FOLLOW) history.stopReplay()

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
        val d = drive(forward, turn)
        history.record(nowMs, d.forward.toFloat(), d.turn.toFloat())
        return d
    }

    private fun drive(forward: Int, turn: Int) =
        Avoider.Drive((forward * speedFactor).roundToInt(), (turn * speedFactor).roundToInt())

    companion object {
        // Ponderi pe benzi, de jos în sus (aceeași ordine ca LineDetector.BAND_Y)
        val BAND_WEIGHTS = floatArrayOf(0.35f, 0.25f, 0.2f, 0.12f, 0.08f)
        // Ponderile la viteză maximă: privirea merge spre benzile de sus
        val FAR_WEIGHTS = floatArrayOf(0.1f, 0.15f, 0.2f, 0.25f, 0.3f)
        const val LOOKAHEAD_FROM = 0.8f
        const val LOOKAHEAD_SPAN = 1.0f

        const val BASE_FORWARD = 30
        const val SLOWDOWN = 0.6f
        const val KP = 45f
        const val KD = 3f
        const val MAX_TURN = 45
        // Netezire ușoară: mai mult ar întârzia reacția cu câteva cadre
        const val SMOOTHING = 0.85f
        const val SIDE_DEADBAND = 0.1f
        const val SEARCH_TURN = 35
        const val SEARCH_MS = 2000L
        const val RETRACE_MS = 2500L
        const val RETRACE_SPEED = 35f

        /** Poziția ponderată a liniei, NaN dacă nu e pe nicio bandă. */
        fun position(r: LineResult, lookahead: Float = 0f): Float {
            // Benzile apropiate contează mai mult, cele îndepărtate anticipează curbele
            var sum = 0f
            var weights = 0f
            for (b in r.positions.indices) {
                val p = r.positions[b]
                if (p.isNaN()) continue
                val w = BAND_WEIGHTS[b] + (FAR_WEIGHTS[b] - BAND_WEIGHTS[b]) * lookahead
                sum += p * w
                weights += w
            }
            return if (weights == 0f) Float.NaN else sum / weights
        }
    }
}
