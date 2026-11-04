package ro.awebdesign.brickpilot

import kotlin.math.hypot

/**
 * Pure pursuit pe linia văzută de sus: alege punctul de pe linie aflat la o distanță care crește cu
 * viteza și dă curbura arcului de cerc care trece prin el. Poziția laterală a punctului e prezisă cu
 * întârzierea sistemului (cameră, Bluetooth, motoare), altfel mașina ar corecta prea târziu și ar oscila.
 * Unități: adâncimea zonei văzute = 1; lateral ±GROUND_ASPECT.
 */
class PurePursuit {

    internal var lookaheadMin = LOOKAHEAD_MIN
    internal var lookaheadPerSpeed = LOOKAHEAD_PER_SPEED
    internal var latency = LATENCY_S

    /** Punctul țintit (lateral -1..1, înainte 0..1); NaN fără linie văzută de sus. */
    @Volatile var aimX = Float.NaN
        private set
    @Volatile var aimY = Float.NaN
        private set

    private var lastAim = Float.NaN
    private var lastMs = 0L
    private var dAim = 0f

    fun reset() {
        lastAim = Float.NaN
        dAim = 0f
        aimX = Float.NaN
        aimY = Float.NaN
    }

    /** Curbura (1 / rază, în unitățile vederii de sus) spre punctul țintit, sau NaN. Pozitivă = dreapta. */
    fun curvature(ground: FloatArray, speedNorm: Float, nowMs: Long): Float {
        aimX = Float.NaN
        aimY = Float.NaN
        if (ground.size < 4) {
            lastAim = Float.NaN
            return Float.NaN
        }
        val lookahead = (lookaheadMin + lookaheadPerSpeed * speedNorm).coerceAtMost(LOOKAHEAD_MAX)
        // Media punctelor din jurul distanței țintite: un singur punct ar aduce tot zgomotul detecției
        var sx = 0f
        var sy = 0f
        var n = 0
        var farX = Float.NaN
        var farY = Float.NaN
        for (i in 0 until ground.size / 2) {
            val x = ground[2 * i] * GROUND_ASPECT
            val y = ground[2 * i + 1]
            if (y < 0f) continue
            farX = x
            farY = y
            val d = hypot(x, y)
            if (d >= lookahead - AIM_WINDOW && d <= lookahead + AIM_WINDOW) {
                sx += x
                sy += y
                n++
            }
        }
        // Linia e mai scurtă decât distanța țintită: mergem spre capătul ei
        val px = if (n > 0) sx / n else farX
        val py = if (n > 0) sy / n else farY
        if (px.isNaN() || py < MIN_AIM_DISTANCE) {
            lastAim = Float.NaN
            return Float.NaN
        }
        aimX = px / GROUND_ASPECT
        aimY = py

        val dt = (nowMs - lastMs) / 1000f
        if (!lastAim.isNaN() && dt > 0f && dt < 0.3f) {
            dAim += D_SMOOTHING * ((px - lastAim) / dt - dAim)
        } else {
            dAim = 0f
        }
        lastAim = px
        lastMs = nowMs
        // Predicția e limitată: nu poate muta ținta mai mult decât se mișcă realist în întârzierea sistemului
        val predicted = px + (dAim * latency).coerceIn(-MAX_PREDICTION, MAX_PREDICTION)
        // Curbura arcului prin punct: 2·lateral / distanță²
        return 2f * predicted / (predicted * predicted + py * py)
    }

    companion object {
        const val LOOKAHEAD_MIN = 0.35f
        const val LOOKAHEAD_PER_SPEED = 0.3f
        const val LOOKAHEAD_MAX = 0.9f
        const val MIN_AIM_DISTANCE = 0.1f
        // Lățimea podelei văzute față de adâncime: lateral -1..1 înseamnă ±GROUND_ASPECT
        const val GROUND_ASPECT = 0.5f
        // Cât înainte prezice punctul țintit; ales din simularea cu întârziere și joc în direcție
        const val LATENCY_S = 0.35f
        // Netezirea vitezei de deplasare a țintei; mai mult zgomot ar smuci direcția
        const val D_SMOOTHING = 0.2f
        const val AIM_WINDOW = 0.08f
        const val MAX_PREDICTION = 0.08f
    }
}
