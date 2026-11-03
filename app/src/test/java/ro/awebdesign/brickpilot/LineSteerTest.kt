package ro.awebdesign.brickpilot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Mașina cu direcție pe o linie dreaptă, simulată cu ce face sistemul real greu de condus: cadrul
 * camerei e vechi de ~230 ms până ajunge comanda la motor, motorul de direcție are viteză limitată,
 * iar mecanismul are joc. În acest model regulatorul vechi pierde linia la viteză mare, ca pe mașină.
 */
class LineSteerTest {

    private class Run(val amplitude: Float, val lostFrames: Int, val c: LineSteerController)

    private fun drive(speedFactor: Float, steerScale: Float = 1f, old: Boolean = false): Run {
        val lf = LineFollower().also { it.speedFactor = speedFactor }
        val c = LineSteerController().also { it.speedFactor = speedFactor; it.steerScale = steerScale }
        var y = 0.10f
        var psi = 0f
        var motor = 0f
        var wheel = 0f
        val delayed = ArrayDeque<Float>()
        var amplitude = 0f
        var lost = 0
        for (i in 0 until FRAMES) {
            // Mașina la dreapta liniei (y > 0) vede linia la stânga (eroare < 0)
            delayed.addLast(-(y + LOOK * sin(psi)) / HALF_VIEW)
            if (delayed.size > DELAY_FRAMES) {
                val e = delayed.removeFirst()
                val r = if (abs(e) > 1f) {
                    lost++
                    LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0)
                } else {
                    LineResult(FloatArray(5) { e }, 640, 480, 0)
                }
                val now = i * 1000L / 30
                val cmd = if (old) Drivetrain.toSteering(lf.decide(r, now), 1.8f * steerScale) else c.decide(r, now)
                motor += (cmd.steer * MAX_STEER - motor).coerceIn(-STEER_RATE / 30f, STEER_RATE / 30f)
                // Roata urmează motorul doar după ce jocul din mecanism e consumat
                if (motor - wheel > BACKLASH) wheel = motor - BACKLASH
                else if (wheel - motor > BACKLASH) wheel = motor + BACKLASH
                val v = cmd.speed / 100f * V_MAX
                psi += v / WHEELBASE * tan(wheel) / 30f
                y += v * sin(psi) / 30f
            }
            if (i > FRAMES * 2 / 3) amplitude = max(amplitude, abs(y))
        }
        return Run(amplitude, lost, c)
    }

    /**
     * Același model, dar mașina primește și linia văzută de sus, ca de la detectorul OpenCV, pe o linie
     * dreaptă sau pe un cerc de rază 1 m (curbă la dreapta). Întoarce abaterea maximă la final.
     */
    private fun drivePursuit(speedFactor: Float, curve: Boolean, tune: (LineSteerController) -> Unit = {}): Run {
        val c = LineSteerController().also { it.speedFactor = speedFactor; tune(it) }
        fun line(s: Float): Pair<Float, Float> =
            if (curve) Pair(R * sin(s / R), R * (1 - kotlin.math.cos(s / R))) else Pair(s, 0f)
        var x = 0f
        var y = 0.08f
        var psi = 0f
        var motor = 0f
        var wheel = 0f
        val delayed = ArrayDeque<LineResult>()
        var amplitude = 0f
        var lost = 0
        var s0 = 0f
        for (i in 0 until FRAMES) {
            // Punctele liniei din fața mașinii, în coordonatele mașinii: f înainte, r la dreapta
            val ground = ArrayList<Float>()
            var e = Float.NaN
            var best = Float.MAX_VALUE
            var s = s0 - 0.3f
            while (s < s0 + 2f) {
                val (lx, ly) = line(s)
                val dx = lx - x
                val dy = ly - y
                val f = dx * kotlin.math.cos(psi) + dy * sin(psi)
                val r = -dx * sin(psi) + dy * kotlin.math.cos(psi)
                if (f in 0.05f * DEPTH..DEPTH && abs(r) <= GROUND_HALF) {
                    ground.add(r / GROUND_HALF)
                    ground.add(f / DEPTH)
                }
                if (abs(f - LOOK) < best) {
                    best = abs(f - LOOK)
                    e = r / HALF_VIEW
                }
                s += 0.02f
            }
            delayed.addLast(
                if (ground.size < 4 || abs(e) > 1f) {
                    LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0)
                } else {
                    LineResult(FloatArray(5) { e }, 640, 480, 0, ground = ground.toFloatArray())
                }
            )
            if (delayed.size > DELAY_FRAMES) {
                val r = delayed.removeFirst()
                if (!r.found) lost++
                val cmd = c.decide(r, i * 1000L / 30)
                motor += (cmd.steer * MAX_STEER - motor).coerceIn(-STEER_RATE / 30f, STEER_RATE / 30f)
                if (motor - wheel > BACKLASH) wheel = motor - BACKLASH
                else if (wheel - motor > BACKLASH) wheel = motor + BACKLASH
                val v = cmd.speed / 100f * V_MAX
                psi += v / WHEELBASE * tan(wheel) / 30f
                x += v * kotlin.math.cos(psi) / 30f
                y += v * sin(psi) / 30f
            }
            // Cât de departe e mașina de linie
            var dist = Float.MAX_VALUE
            var bestS = s0
            var s2 = s0 - 0.3f
            while (s2 < s0 + 1f) {
                val (lx, ly) = line(s2)
                val d = kotlin.math.hypot(lx - x, ly - y)
                if (d < dist) { dist = d; bestS = s2 }
                s2 += 0.01f
            }
            s0 = bestS
            if (i > FRAMES * 2 / 3) amplitude = max(amplitude, dist)
        }
        return Run(amplitude, lost, c)
    }

    @Test
    fun pursuitStaysOnAStraightLineAtEverySpeed() {
        for (sf in listOf(0.6f, 1f, 1.5f, 2f)) {
            val r = drivePursuit(sf, curve = false)
            assertTrue("viteza $sf: a pierdut linia ${r.lostFrames} cadre", r.lostFrames == 0)
            assertTrue("viteza $sf: abatere ${r.amplitude}", r.amplitude < 0.03f)
        }
    }

    @Test
    fun pursuitFollowsACurve() {
        for (sf in listOf(0.6f, 1f, 1.5f, 2f)) {
            val r = drivePursuit(sf, curve = true)
            assertTrue("viteza $sf: a pierdut linia în curbă ${r.lostFrames} cadre", r.lostFrames == 0)
            assertTrue("viteza $sf: abatere în curbă ${r.amplitude}", r.amplitude < 0.04f)
        }
    }

    @Test
    fun oldControllerLosesTheLineAtSpeed() {
        // Ce se vedea pe mașină: încet merge, repede iese de pe linie oricât de tare virează
        assertTrue(drive(0.6f, old = true).lostFrames == 0)
        assertTrue(drive(2f, old = true).lostFrames > 100)
        assertTrue(drive(2f, steerScale = 1.25f, old = true).lostFrames > 100)
    }

    @Test
    fun staysOnTheLineAtEverySpeed() {
        for (sf in listOf(0.6f, 1f, 1.5f, 2f)) {
            val r = drive(sf)
            assertTrue("viteza $sf: a pierdut linia ${r.lostFrames} cadre", r.lostFrames == 0)
            assertTrue("viteza $sf: oscilează ${r.amplitude}", r.amplitude < 0.03f)
        }
    }

    @Test
    fun learnsToSlowDownWhenItOscillates() {
        val r = drive(2f)
        assertTrue("nu a învățat: g=${r.c.learnedGain} v=${r.c.learnedSpeed}", r.c.learnedSpeed < 1f && r.c.learnedGain < 1f)
    }

    @Test
    fun stableDrivingKeepsWhatItLearned() {
        val r = drive(0.6f)
        assertTrue(r.c.learnedGain == 1f && r.c.learnedSpeed == 1f)
    }

    @Test
    fun steersTowardsTheLine() {
        val c = LineSteerController()
        val right = c.decide(LineResult(FloatArray(5) { 0.5f }, 640, 480, 0), 1000)
        assertTrue(right.steer > 0 && right.speed > 0)
    }

    @Test
    fun slowsDownWhenFarFromTheLine() {
        val c = LineSteerController()
        val near = c.decide(LineResult(FloatArray(5) { 0.05f }, 640, 480, 0), 1000).speed
        c.reset()
        val far = c.decide(LineResult(FloatArray(5) { 0.8f }, 640, 480, 0), 1000).speed
        assertTrue(far < near)
    }

    @Test
    fun retracesThenSearchesTowardsTheLastSide() {
        val c = LineSteerController()
        val none = LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0)
        var t = 1000L
        // Merge pe o curbă la dreapta, apoi pierde linia
        repeat(30) { c.decide(LineResult(FloatArray(5) { 0.6f }, 640, 480, 0), t); t += 33 }
        val back = c.decide(none, t)
        assertTrue("dă înapoi pe drumul parcurs", c.mode == LineSteerController.Mode.RETRACE && back.speed < 0)
        assertTrue("cu același unghi", back.steer > 0)
        // După ce istoricul se termină, caută înainte spre partea liniei
        t += LineSteerController.RETRACE_MS + 100
        c.decide(none, t)
        val search = c.decide(none, t + 50)
        assertTrue(c.mode == LineSteerController.Mode.SEARCH && search.steer > 0 && search.speed > 0)
        val gone = c.decide(none, t + 5000)
        assertTrue(gone.speed == 0)
    }

    @Test
    fun retraceStopsWhenTheLineIsBack() {
        val c = LineSteerController()
        var t = 1000L
        repeat(30) { c.decide(LineResult(FloatArray(5) { 0.3f }, 640, 480, 0), t); t += 33 }
        c.decide(LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0), t)
        val again = c.decide(LineResult(FloatArray(5) { 0.2f }, 640, 480, 0), t + 300)
        assertTrue(c.mode == LineSteerController.Mode.FOLLOW && again.speed > 0)
    }

    companion object {
        const val FRAMES = 900
        const val V_MAX = 1.5f          // m/s la viteză 100
        const val WHEELBASE = 0.18f     // m
        const val MAX_STEER = 0.5f      // rad, ~30°
        const val STEER_RATE = 1.5f     // rad/s, cât de repede schimbă motorul unghiul
        const val BACKLASH = 0.1f       // rad, jocul din mecanism
        const val LOOK = 0.25f          // m, cât de departe în față se uită camera
        const val HALF_VIEW = 0.15f     // m, jumătatea lățimii văzute
        const val DELAY_FRAMES = 7
        const val DEPTH = 0.5f          // m, cât de departe vede camera podeaua în vederea de sus
        const val GROUND_HALF = DEPTH * PurePursuit.GROUND_ASPECT
        const val R = 1.0f              // m, raza curbei
    }
}
