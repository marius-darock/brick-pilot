package ro.awebdesign.brickpilot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Mașina de tip 1 (două roți motoare) pe o linie dreaptă și pe o curbă, cu linia văzută de sus ca de la
 * detectorul OpenCV, întârzierea camerei și motoare care nu răspund instantaneu.
 */
class DiffPursuitTest {

    private class Run(val amplitude: Float, val lostFrames: Int)

    private fun drive(speedFactor: Float, curve: Boolean): Run {
        val f = LineFollower().also { it.speedFactor = speedFactor }
        fun line(s: Float): Pair<Float, Float> =
            if (curve) Pair(R * sin(s / R), R * (1 - cos(s / R))) else Pair(s, 0f)
        var x = 0f
        var y = 0.08f
        var psi = 0f
        var left = 0f
        var right = 0f
        val delayed = ArrayDeque<LineResult>()
        var amplitude = 0f
        var lost = 0
        var s0 = 0f
        for (i in 0 until FRAMES) {
            val ground = ArrayList<Float>()
            var e = Float.NaN
            var best = Float.MAX_VALUE
            var s = s0 - 0.3f
            while (s < s0 + 2f) {
                val (lx, ly) = line(s)
                val dx = lx - x
                val dy = ly - y
                val fwd = dx * cos(psi) + dy * sin(psi)
                val r = -dx * sin(psi) + dy * cos(psi)
                if (fwd in 0.05f * DEPTH..DEPTH && abs(r) <= GROUND_HALF) {
                    ground.add(r / GROUND_HALF)
                    ground.add(fwd / DEPTH)
                }
                if (abs(fwd - LOOK) < best) {
                    best = abs(fwd - LOOK)
                    e = r / HALF_VIEW
                }
                s += 0.02f
            }
            delayed.addLast(
                if (ground.size < 4 || abs(e) > 1f) LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0)
                else LineResult(FloatArray(5) { e }, 640, 480, 0, ground = ground.toFloatArray())
            )
            if (delayed.size > DELAY_FRAMES) {
                val r = delayed.removeFirst()
                if (!r.found) lost++
                val d = f.decide(r, i * 1000L / 30)
                // Roțile ajung la viteza cerută cu o mică întârziere
                left += ((d.forward + d.turn) - left) * (1f / 30f / MOTOR_TAU)
                right += ((d.forward - d.turn) - right) * (1f / 30f / MOTOR_TAU)
                val vl = left / 100f * V_MAX
                val vr = right / 100f * V_MAX
                psi += (vl - vr) / TRACK / 30f
                x += (vl + vr) / 2f * cos(psi) / 30f
                y += (vl + vr) / 2f * sin(psi) / 30f
            }
            var dist = Float.MAX_VALUE
            var bestS = s0
            var s2 = s0 - 0.3f
            while (s2 < s0 + 1f) {
                val (lx, ly) = line(s2)
                val dd = hypot(lx - x, ly - y)
                if (dd < dist) { dist = dd; bestS = s2 }
                s2 += 0.01f
            }
            s0 = bestS
            if (i > FRAMES * 2 / 3) amplitude = max(amplitude, dist)
        }
        return Run(amplitude, lost)
    }

    @Test
    fun followsStraightLineAndCurveAtEverySpeed() {
        for (curve in listOf(false, true)) for (sf in listOf(0.6f, 1f, 1.5f, 2f)) {
            val r = drive(sf, curve)
            val what = if (curve) "curbă" else "linie dreaptă"
            println("tip 1, $what, viteza $sf: abatere %.3f, pierdut %d".format(r.amplitude, r.lostFrames))
            assertTrue("$what, viteza $sf: a pierdut linia ${r.lostFrames} cadre", r.lostFrames == 0)
            assertTrue("$what, viteza $sf: abatere ${r.amplitude}", r.amplitude < 0.04f)
        }
    }

    companion object {
        const val FRAMES = 900
        const val V_MAX = 1.0f          // m/s la viteză 100
        const val TRACK = 0.15f         // m, distanța dintre roți
        const val MOTOR_TAU = 0.08f     // s
        const val LOOK = 0.25f
        const val HALF_VIEW = 0.15f
        const val DELAY_FRAMES = 7
        const val DEPTH = 0.5f
        const val GROUND_HALF = DEPTH * PurePursuit.GROUND_ASPECT
        const val R = 1.0f
    }
}
