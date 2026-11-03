package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cât poate vira motorul de direcție: centrul (mers drept) și abaterea sigură față de el, în grade de encoder. */
data class SteeringRange(val center: Int, val half: Int)

/**
 * Calibrarea direcției la mașina de tip 2: motorul A merge până la capătul din stânga, apoi până la
 * cel din dreapta, apoi se oprește la mijloc. Mijlocul devine reperul de mers drept pentru conexiunea
 * curentă, iar cursa folosită rămâne foarte puțin înaintea capetelor, ca mecanismul să nu forțeze.
 * Rulează pe thread-ul principal: onPosition la fiecare valoare de encoder, tick periodic.
 */
class SteeringCalibrator(private val out: Output) {

    interface Output {
        /** Rotește motorul de direcție cu viteză și putere limitate. */
        fun runSteer(speed: Int, maxPower: Int)
        fun brakeSteer()
        fun requestSteerPosition()
        fun centerSteer(position: Int)
        fun onEndFound(position: Int) = Unit
        fun onCalibrated(range: SteeringRange)
        fun onFailed(message: String)
    }

    enum class Phase { IDLE, WAIT_POSITION, TO_FIRST_END, TO_SECOND_END, DONE, FAILED }

    var phase = Phase.IDLE
        private set

    val isRunning get() = phase == Phase.WAIT_POSITION || phase == Phase.TO_FIRST_END || phase == Phase.TO_SECOND_END

    private var position: Int? = null
    private var positionMs = 0L
    private var phaseStartMs = 0L
    // Fereastra în care verificăm dacă motorul a mai avansat
    private var windowStartMs = 0L
    private var windowStartPos: Int? = null
    // Cel mai departe punct atins în faza curentă: el e capătul, nu poziția din clipa opririi
    private var extreme = 0
    private var firstEnd = 0

    fun start(nowMs: Long) {
        position = null
        phase = Phase.WAIT_POSITION
        phaseStartMs = nowMs
        out.requestSteerPosition()
    }

    fun cancel() {
        if (!isRunning) return
        out.brakeSteer()
        fail("Calibration stopped")
    }

    fun onPosition(degrees: Int, nowMs: Long) {
        position = degrees
        positionMs = nowMs
    }

    fun tick(nowMs: Long) {
        when (phase) {
            Phase.WAIT_POSITION -> when {
                position != null -> run(Phase.TO_FIRST_END, -CAL_SPEED, nowMs)
                nowMs - phaseStartMs > POSITION_TIMEOUT_MS -> fail("Motor A does not respond. Check port A.")
            }
            Phase.TO_FIRST_END, Phase.TO_SECOND_END -> {
                // Cerem poziția la fiecare pas: fără valori proaspete o întârziere a notificărilor
                // ar arăta ca un motor oprit și calibrarea s-ar opri la jumătatea cursei
                out.requestSteerPosition()
                val elapsed = nowMs - phaseStartMs
                if (elapsed > END_TIMEOUT_MS) {
                    out.brakeSteer()
                    fail("The steering never reached an end. The mechanism needs left and right end stops.")
                    return
                }
                val pos = position ?: return
                extreme = if (phase == Phase.TO_FIRST_END) min(extreme, pos) else max(extreme, pos)
                if (nowMs - positionMs > FRESH_MS) return
                // Capăt = sub câteva grade în toată fereastra. În opritor mecanismul se mai arcuiește
                // puțin, iar o valoare nouă de encoder nu înseamnă că mai avansează
                val start = windowStartPos
                if (start == null || abs(pos - start) > STALL_DEGREES) {
                    windowStartPos = pos
                    windowStartMs = nowMs
                    return
                }
                if (elapsed < START_GRACE_MS || nowMs - windowStartMs < STALL_MS) return
                out.onEndFound(extreme)
                if (phase == Phase.TO_FIRST_END) {
                    firstEnd = extreme
                    run(Phase.TO_SECOND_END, CAL_SPEED, nowMs)
                } else {
                    finish(firstEnd, extreme)
                }
            }
            else -> Unit
        }
    }

    private fun run(next: Phase, speed: Int, nowMs: Long) {
        phase = next
        phaseStartMs = nowMs
        windowStartMs = nowMs
        windowStartPos = position
        extreme = position ?: 0
        out.runSteer(speed, CAL_POWER)
    }

    private fun finish(a: Int, b: Int) {
        val range = rangeFromEnds(a, b)
        if (range == null) {
            out.brakeSteer()
            fail("The steering barely moves (${abs(b - a)}°). Check motor A and the mechanism.")
            return
        }
        phase = Phase.DONE
        out.centerSteer(range.center)
        out.onCalibrated(range)
    }

    private fun fail(message: String) {
        phase = Phase.FAILED
        out.onFailed(message)
    }

    companion object {
        const val CAL_SPEED = 35
        // Destulă putere ca un mecanism greu să nu se oprească din frecare înainte de capăt,
        // dar nu toată, ca motorul să nu forțeze în opritori
        const val CAL_POWER = 75
        const val START_GRACE_MS = 300L
        // Un mecanism greu se mișcă încet; doar o pauză mai lungă înseamnă capăt
        const val STALL_MS = 400L
        const val STALL_DEGREES = 6
        // O poziție mai veche de atât nu spune nimic despre mișcarea de acum
        const val FRESH_MS = 200L
        const val END_TIMEOUT_MS = 8000L
        const val POSITION_TIMEOUT_MS = 1500L
        const val MIN_TRAVEL = 20
        // Aproape toată cursa, dar cu câteva grade rezervă față de opritori
        const val USABLE = 0.95f
        const val END_MARGIN = 3

        fun rangeFromEnds(a: Int, b: Int): SteeringRange? {
            val travel = abs(b - a)
            if (travel < MIN_TRAVEL) return null
            val half = travel / 2
            val safe = min((half * USABLE).roundToInt(), half - END_MARGIN)
            return SteeringRange(center = min(a, b) + half, half = safe)
        }
    }
}
