package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decizia de mers din free[stânga, centru, dreapta], cu ieșire din colțuri. */
class Avoider {

    enum class Mode { CRUISE, SLOW, SPIN, ESCAPE_BACK, ESCAPE_SPIN }

    data class Drive(val forward: Int, val turn: Int)

    data class Motors(val left: Int, val right: Int)

    @Volatile var mode = Mode.CRUISE
        private set

    /** Factor de viteză, 1.0 = valorile de bază. */
    @Volatile var speedFactor = 1f

    // +1 = dreapta, -1 = stânga
    private var lastDir = 1
    private var spinDir = 1
    private var stateUntilMs = 0L
    private var lastSpinEndMs = 0L
    // Câte rotiri la rând fără mers liber între ele
    private var cornerCount = 0

    fun reset() {
        mode = Mode.CRUISE
        stateUntilMs = 0L
        lastSpinEndMs = 0L
        cornerCount = 0
    }

    /** front: distanța până la primul obstacol din față, văzută de sus (0..1), de la FloorAnalyzer. */
    fun decide(free: FloatArray, nowMs: Long, front: Float = 1f): Drive {
        val left = free[0]
        val right = free[2]
        // Distanța reală până la perete are întâietate: aproape de el oprim și ne rotim,
        // la distanță medie mergem încet, chiar dacă grila încă vede podea
        val center = when {
            front < STOP_FRONT -> 0f
            front < SLOW_FRONT -> min(free[1], (CRUISE_FREE + SLOW_FREE) / 2f)
            else -> free[1]
        }

        // Manevrele nu se întrerup până expiră, orice ar vedea camera
        when (mode) {
            Mode.SPIN, Mode.ESCAPE_SPIN -> {
                if (nowMs < stateUntilMs) return drive(0, SPIN_TURN * spinDir)
                lastSpinEndMs = nowMs
                if (mode == Mode.ESCAPE_SPIN) cornerCount = 0
                mode = Mode.CRUISE
            }
            Mode.ESCAPE_BACK -> {
                if (nowMs < stateUntilMs) return drive(-ESCAPE_BACK_SPEED, 0)
                mode = Mode.ESCAPE_SPIN
                stateUntilMs = nowMs + scaled(ESCAPE_SPIN_MS)
                return drive(0, SPIN_TURN * spinDir)
            }
            else -> Unit
        }

        return when {
            center > CRUISE_FREE -> {
                mode = Mode.CRUISE
                drive(CRUISE_FORWARD, ((right - left) * CRUISE_GAIN).roundToInt())
            }
            center > SLOW_FREE -> {
                mode = Mode.SLOW
                drive(SLOW_FORWARD, SLOW_TURN * pickSide(left, right))
            }
            else -> startSpin(left, right, nowMs)
        }
    }

    private fun startSpin(left: Float, right: Float, nowMs: Long): Drive {
        // Blocat din nou imediat după o rotire: suntem într-un colț
        val again = lastSpinEndMs > 0 && nowMs - lastSpinEndMs < scaled(CORNER_WINDOW_MS)
        cornerCount = if (again) cornerCount + 1 else 0

        if (cornerCount >= ESCAPE_AFTER) {
            // Colț: dăm înapoi și facem o rotire lungă în aceeași direcție
            mode = Mode.ESCAPE_BACK
            stateUntilMs = nowMs + scaled(ESCAPE_BACK_MS)
            return drive(-ESCAPE_BACK_SPEED, 0)
        }

        // În colț păstrăm direcția, altfel ar face stânga-dreapta la nesfârșit
        if (cornerCount == 0) spinDir = pickSide(left, right)
        mode = Mode.SPIN
        stateUntilMs = nowMs + scaled(SPIN_MS)
        return drive(0, SPIN_TURN * spinDir)
    }

    // La viteză mai mică manevra durează mai mult, ca unghiul și distanța să rămână aceleași
    private fun scaled(ms: Long): Long = (ms / max(speedFactor, 0.1f)).toLong()

    private fun drive(forward: Int, turn: Int): Drive =
        Drive((forward * speedFactor).roundToInt(), (turn * speedFactor).roundToInt())

    // Histereză: schimbăm partea doar dacă cealaltă e clar mai liberă
    private fun pickSide(left: Float, right: Float): Int {
        val diff = right - left
        if (lastDir > 0 && diff < -SIDE_MARGIN) lastDir = -1
        else if (lastDir < 0 && diff > SIDE_MARGIN) lastDir = 1
        return lastDir
    }

    companion object {
        const val CRUISE_FREE = 0.65f
        const val SLOW_FREE = 0.40f
        const val CRUISE_FORWARD = 45
        const val CRUISE_GAIN = 35f
        const val SLOW_FORWARD = 25
        const val SLOW_TURN = 45
        const val SIDE_MARGIN = 0.15f
        // Distanța până la perete, în adâncimea zonei văzute: sub STOP se rotește, sub SLOW încetinește
        const val STOP_FRONT = 0.15f
        const val SLOW_FRONT = 0.35f

        // Duratele sunt la viteza 100%; la alte viteze se ajustează automat
        const val SPIN_TURN = 60
        const val SPIN_MS = 800L

        const val CORNER_WINDOW_MS = 2000L
        const val ESCAPE_AFTER = 2
        const val ESCAPE_BACK_SPEED = 40
        const val ESCAPE_BACK_MS = 700L
        const val ESCAPE_SPIN_MS = 1600L

        /** left = forward + turn, right = forward - turn, normalizat la 100. */
        fun mix(d: Drive, invertLeft: Boolean, invertRight: Boolean): Motors {
            var l = (d.forward + d.turn).toFloat()
            var r = (d.forward - d.turn).toFloat()
            val m = max(abs(l), abs(r))
            if (m > 100f) {
                l = l * 100f / m
                r = r * 100f / m
            }
            val li = l.roundToInt().let { if (invertLeft) -it else it }
            val ri = r.roundToInt().let { if (invertRight) -it else it }
            return Motors(li, ri)
        }
    }
}
