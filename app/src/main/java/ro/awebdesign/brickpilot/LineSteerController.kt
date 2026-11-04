package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * Urmărirea liniei pentru mașina cu direcție (tip 2).
 *
 * La o mașină cu direcție același unghi al roților o întoarce mai repede cu cât merge mai repede, iar
 * comanda ajunge la roți cu întârziere (cameră, Bluetooth, motorul de direcție). Un regulator simplu
 * corectează prea mult, trece de linie și oscilează tot mai tare. De aceea:
 * - unghiul scade cu viteza, ca mașina să se întoarcă la fel de repede la orice viteză;
 * - eroarea e prezisă cu întârzierea sistemului, deci roțile se îndreaptă înainte să treacă de linie;
 * - în curbe și când roțile sunt bracate tare încetinește;
 * - dacă oscilează, scade singur cât de tare virează și cât de repede merge; pe linie dreaptă și
 *   stabilă revine treptat. Ce a învățat se poate salva între porniri.
 */
class LineSteerController {

    enum class Mode { FOLLOW, RETRACE, SEARCH, LOST }

    @Volatile var mode = Mode.FOLLOW
        private set

    /** Factor de viteză din slider, 1.0 = valorile de bază. */
    @Volatile var speedFactor = 1f

    /** Sliderul Steer, 1.0 = valoarea implicită. */
    @Volatile var steerScale = 1f

    /** Învățat din mers: cât din unghi și cât din viteză se folosesc, salvate între porniri. */
    @Volatile var learnedGain = 1f
    @Volatile var learnedSpeed = 1f

    private var filtered = Float.NaN
    private var dFiltered = 0f
    private var lastMs = 0L
    private var lastSeenMs = 0L
    private var lastSide = 0

    // Detectorul de oscilație: momentele în care eroarea mare și-a schimbat semnul
    private val crossings = ArrayDeque<Long>()
    private var lastBigSign = 0
    private var stableSinceMs = 0L
    private var sameSideSinceMs = 0L

    // Drumul parcurs, ca să se poată întoarce acolo unde a văzut linia ultima dată
    private val history = CommandHistory(RETRACE_MS)

    /** Pure pursuit pe linia văzută de sus, când detectorul o dă. */
    val pursuit = PurePursuit()
    private var searchStartMs = 0L

    fun reset() {
        mode = Mode.FOLLOW
        filtered = Float.NaN
        dFiltered = 0f
        lastMs = 0L
        lastSeenMs = 0L
        lastSide = 0
        crossings.clear()
        lastBigSign = 0
        stableSinceMs = 0L
        sameSideSinceMs = 0L
        history.clear()
        pursuit.reset()
        searchStartMs = 0L
    }

    fun decide(r: LineResult, nowMs: Long): Drivetrain.SteerDrive {
        val speedNorm = speedFactor * learnedSpeed
        val lookahead = ((speedNorm - LineFollower.LOOKAHEAD_FROM) / LineFollower.LOOKAHEAD_SPAN).coerceIn(0f, 1f)
        val raw = LineFollower.position(r, lookahead)

        if (raw.isNaN()) return lost(nowMs)
        if (mode != Mode.FOLLOW) {
            // Linia a reapărut: oprim întoarcerea și căutarea
            history.stopReplay()
            searchStartMs = 0L
        }

        val err = if (filtered.isNaN()) raw else filtered + SMOOTHING * (raw - filtered)
        val dt = (nowMs - lastMs) / 1000f
        if (lastMs > 0 && dt > 0f && dt < 0.3f) {
            val d = (err - filtered) / dt
            dFiltered += D_SMOOTHING * (d - dFiltered)
        } else {
            dFiltered = 0f
        }
        filtered = err
        lastMs = nowMs
        lastSeenMs = nowMs
        if (abs(err) > SIDE_DEADBAND) lastSide = if (err > 0) 1 else -1
        mode = Mode.FOLLOW
        // Cu linia văzută de sus țintim un punct de pe ea (pure pursuit); altfel, regulatorul pe poziție
        val k = pursuit.curvature(r.ground, speedNorm, nowMs)
        learn(err, nowMs, adjustGain = k.isNaN())

        // Unde va fi linia când comanda ajunge la roți
        val predicted = err + dFiltered * LATENCY_S
        val steer = if (!k.isNaN()) {
            (K_PURSUIT * k * steerScale).coerceIn(-1f, 1f)
        } else {
            // La viteză mare același unghi întoarce mai repede: unghiul scade cu viteza
            val schedule = 1f / max(MIN_SCHEDULE_SPEED, speedNorm).pow(SPEED_EXPONENT)
            (K_STEER * predicted * schedule * steerScale * learnedGain).coerceIn(-1f, 1f)
        }

        // Încetinim în curbe și cu roțile bracate, ca să aibă timp să corecteze
        val slow = 1f - SLOWDOWN * min(1f, abs(predicted)) - STEER_SLOWDOWN * abs(steer)
        val cmd = Drivetrain.SteerDrive(steer, speed(BASE_SPEED * max(MIN_SLOW, slow)))
        history.record(nowMs, cmd.steer, cmd.speed.toFloat())
        return cmd
    }


    /**
     * Linia nu se mai vede. Întâi dăm înapoi pe drumul parcurs, cu aceleași unghiuri, până reapare;
     * dacă istoricul se termină fără ea, mergem încet înainte cu roțile spre partea unde a fost; apoi oprim.
     */
    private fun lost(nowMs: Long): Drivetrain.SteerDrive {
        lastMs = 0L
        filtered = Float.NaN
        dFiltered = 0f
        if (mode == Mode.FOLLOW && lastSeenMs > 0) {
            mode = if (history.startReplay(nowMs)) Mode.RETRACE else Mode.SEARCH
            if (mode == Mode.SEARCH) searchStartMs = nowMs
        }
        if (mode == Mode.RETRACE) {
            val c = history.replay(nowMs)
            if (c != null) {
                // Același unghi, viteză inversată și plafonată: arcul rămâne același, doar mai încet
                val back = -min(abs(c.second), RETRACE_SPEED * speedFactor).toInt()
                return Drivetrain.SteerDrive(c.first, if (c.second < 0f) -back else back)
            }
            mode = Mode.SEARCH
            searchStartMs = nowMs
        }
        if (mode == Mode.SEARCH && lastSide != 0 && nowMs - searchStartMs < SEARCH_MS) {
            return Drivetrain.SteerDrive(lastSide.toFloat(), speed(SEARCH_SPEED))
        }
        mode = Mode.LOST
        return Drivetrain.SteerDrive(0f, 0)
    }

    private fun speed(base: Float): Int = (base * speedFactor * learnedSpeed).toInt().coerceIn(-100, 100)

    private fun learn(err: Float, nowMs: Long, adjustGain: Boolean) {
        // Oscilație: eroarea mare își schimbă semnul de mai multe ori într-un timp scurt
        if (abs(err) > OSC_AMPLITUDE) {
            val s = sign(err).toInt()
            if (lastBigSign != 0 && s != lastBigSign) crossings.addLast(nowMs)
            lastBigSign = s
        }
        while (crossings.isNotEmpty() && nowMs - crossings.first() > OSC_WINDOW_MS) crossings.removeFirst()
        if (crossings.size >= OSC_CROSSINGS) {
            // Pure pursuit virează cât cere geometria: acolo oscilația se tratează doar prin viteză
            if (adjustGain) learnedGain = max(MIN_LEARNED_GAIN, learnedGain * GAIN_DOWN)
            learnedSpeed = max(MIN_LEARNED_SPEED, learnedSpeed * SPEED_DOWN)
            crossings.clear()
            stableSinceMs = nowMs
        }

        // Stabil pe linie: revenim încet spre viteza cerută
        if (abs(err) < STABLE_ERR) {
            if (stableSinceMs == 0L) stableSinceMs = nowMs
            if (nowMs - stableSinceMs > STABLE_MS) {
                learnedSpeed = min(1f, learnedSpeed + SPEED_UP)
                // Doar după o scădere; un câștig crescut pentru curbe rămâne
                if (learnedGain < 1f) learnedGain = min(1f, learnedGain + GAIN_UP)
                stableSinceMs = nowMs
            }
        } else {
            stableSinceMs = 0L
        }

        // Rămâne mult timp de aceeași parte a liniei: virează prea puțin în curbă
        if (adjustGain && abs(err) > UNDERSTEER_ERR && crossings.isEmpty()) {
            if (sameSideSinceMs == 0L) sameSideSinceMs = nowMs
            if (nowMs - sameSideSinceMs > UNDERSTEER_MS) {
                learnedGain = min(MAX_LEARNED_GAIN, learnedGain * GAIN_UNDERSTEER)
                sameSideSinceMs = nowMs
            }
        } else {
            sameSideSinceMs = 0L
        }
    }

    companion object {
        const val BASE_SPEED = 35f
        const val SEARCH_SPEED = 18f
        const val SEARCH_MS = 1500L
        // Cât din drumul parcurs poate reface înapoi și cu ce viteză maximă
        const val RETRACE_MS = 2500L
        const val RETRACE_SPEED = 25f
        const val SIDE_DEADBAND = 0.1f
        const val SMOOTHING = 0.85f
        const val D_SMOOTHING = 0.5f

        // Eroarea -1..1 devine unghi -1..1
        const val K_STEER = 1.6f

        // Curbura pure pursuit devine unghiul roților -1..1
        const val K_PURSUIT = 0.7f
        // Cameră ~90 ms + analiză + Bluetooth + motorul de direcție, rotunjit în sus
        const val LATENCY_S = 0.3f
        const val SPEED_EXPONENT = 0.8f
        const val MIN_SCHEDULE_SPEED = 0.5f
        const val SLOWDOWN = 0.5f
        const val STEER_SLOWDOWN = 0.3f
        const val MIN_SLOW = 0.4f

        const val OSC_AMPLITUDE = 0.3f
        const val OSC_WINDOW_MS = 2500L
        const val OSC_CROSSINGS = 2
        const val GAIN_DOWN = 0.85f
        const val SPEED_DOWN = 0.9f
        const val MIN_LEARNED_GAIN = 0.4f
        const val MIN_LEARNED_SPEED = 0.5f
        const val STABLE_ERR = 0.15f
        const val STABLE_MS = 3000L
        const val SPEED_UP = 0.03f
        const val GAIN_UP = 0.02f
        const val UNDERSTEER_ERR = 0.45f
        const val UNDERSTEER_MS = 1200L
        const val GAIN_UNDERSTEER = 1.05f
        const val MAX_LEARNED_GAIN = 1.5f
    }
}
