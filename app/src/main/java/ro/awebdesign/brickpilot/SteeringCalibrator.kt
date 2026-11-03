package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Cât poate vira motorul de direcție: centrul și abaterea sigură față de el, în grade de encoder. */
data class SteeringRange(val center: Int, val half: Int)

/**
 * Calibrarea direcției la mașina de tip 2. Motorul A merge încet, cu putere mică, până se oprește
 * în capătul din stânga, apoi în cel din dreapta. Centrul e la mijloc, iar cursa folosită rămâne
 * puțin înaintea capetelor, ca mecanismul să nu forțeze.
 * Rulează pe thread-ul principal: onPosition la fiecare valoare de encoder, tick periodic.
 */
class SteeringCalibrator(private val out: Output) {

    interface Output {
        /** Rotește motorul de direcție cu viteză și putere limitate. */
        fun runSteer(speed: Int, maxPower: Int)
        fun brakeSteer()
        fun requestSteerPosition()
        fun centerSteer(position: Int)
        fun onCalibrated(range: SteeringRange)
        fun onFailed(message: String)
    }

    enum class Phase { IDLE, WAIT_POSITION, TO_FIRST_END, TO_SECOND_END, DONE, FAILED }

    var phase = Phase.IDLE
        private set

    val isRunning get() = phase == Phase.WAIT_POSITION || phase == Phase.TO_FIRST_END || phase == Phase.TO_SECOND_END

    private var position: Int? = null
    private var phaseStartMs = 0L
    private var lastMoveMs = 0L
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
        // Hubul trimite o valoare nouă doar când motorul s-a mișcat cel puțin câteva grade
        if (position != degrees) lastMoveMs = nowMs
        position = degrees
    }

    fun tick(nowMs: Long) {
        when (phase) {
            Phase.WAIT_POSITION -> when {
                position != null -> run(Phase.TO_FIRST_END, -CAL_SPEED, nowMs)
                nowMs - phaseStartMs > POSITION_TIMEOUT_MS -> fail("Motor A does not respond. Check port A.")
            }
            Phase.TO_FIRST_END, Phase.TO_SECOND_END -> {
                val elapsed = nowMs - phaseStartMs
                if (elapsed > END_TIMEOUT_MS) {
                    out.brakeSteer()
                    fail("The steering never reached an end. The mechanism needs left and right end stops.")
                    return
                }
                if (elapsed < START_GRACE_MS || nowMs - lastMoveMs < STALL_MS) return
                val pos = position ?: return
                if (phase == Phase.TO_FIRST_END) {
                    firstEnd = pos
                    run(Phase.TO_SECOND_END, CAL_SPEED, nowMs)
                } else {
                    finish(firstEnd, pos)
                }
            }
            else -> Unit
        }
    }

    private fun run(next: Phase, speed: Int, nowMs: Long) {
        phase = next
        phaseStartMs = nowMs
        lastMoveMs = nowMs
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
        const val CAL_SPEED = 25
        // Puterea mică limitează forța cu care motorul apasă în capete
        const val CAL_POWER = 40
        const val START_GRACE_MS = 300L
        const val STALL_MS = 250L
        const val END_TIMEOUT_MS = 4000L
        const val POSITION_TIMEOUT_MS = 1500L
        const val MIN_TRAVEL = 20
        // Folosim doar o parte din jumătatea cursei, dar rămânem cel puțin câteva grade departe de capete
        const val USABLE = 0.85f
        const val END_MARGIN = 5

        fun rangeFromEnds(a: Int, b: Int): SteeringRange? {
            val travel = abs(b - a)
            if (travel < MIN_TRAVEL) return null
            val half = travel / 2
            val safe = min((half * USABLE).roundToInt(), half - END_MARGIN)
            return SteeringRange(center = min(a, b) + half, half = safe)
        }
    }
}
