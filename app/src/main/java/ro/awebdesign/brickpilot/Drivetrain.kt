package ro.awebdesign.brickpilot

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Legătura dintre comenzile de mers și porturile hubului.
 * Tipul 1: portul A e motorul stâng, portul B e motorul drept, virează prin diferența de viteză.
 * Tipul 2: portul A e motorul de direcție din față, portul B e motorul de tracțiune.
 */
object Drivetrain {

    enum class VehicleType { DIFFERENTIAL, STEERING }

    class PortSpeeds(val a: Int, val b: Int)

    /** Tipul 2: unghiul roților -1 (stânga) .. 1 (dreapta) și viteza de tracțiune. */
    class SteerDrive(val steer: Float, val speed: Int)

    /** Tipul 2: poziția motorului A în grade de encoder și viteza motorului B. */
    class SteerPorts(val steerPosition: Int, val speedB: Int)

    /** turn > 0 virează la dreapta: roata stângă (A) merge mai repede decât cea dreaptă (B). */
    fun toPorts(d: Avoider.Drive, invertA: Boolean, invertB: Boolean): PortSpeeds {
        val m = Avoider.mix(d, invertLeft = invertA, invertRight = invertB)
        return PortSpeeds(a = m.left, b = m.right)
    }

    /**
     * Aceeași comandă pentru o mașină cu direcție. Raportul turn/forward dă curbura, ca la tipul 1,
     * deci nu depinde de viteza aleasă. Mersul înapoi inversează direcția, ca mașina să se rotească
     * tot în sensul cerut.
     */
    fun toSteering(d: Avoider.Drive): SteerDrive {
        if (d.forward == 0) {
            if (d.turn == 0) return SteerDrive(0f, 0)
            // Nu se poate roti pe loc: dă înapoi cu roțile bracate invers, ca la o întoarcere din trei mișcări
            return SteerDrive(-sign(d.turn.toFloat()), -(abs(d.turn) * SPIN_REVERSE).roundToInt())
        }
        val steer = (d.turn.toFloat() / d.forward).coerceIn(-1f, 1f)
        return SteerDrive(steer, d.forward.coerceIn(-100, 100))
    }

    fun toSteerPorts(d: Avoider.Drive, range: SteeringRange, invertA: Boolean, invertB: Boolean): SteerPorts {
        val s = toSteering(d)
        val steer = if (invertA) -s.steer else s.steer
        return SteerPorts(
            steerPosition = range.center + (steer * range.half).roundToInt(),
            speedB = if (invertB) -s.speed else s.speed
        )
    }

    // Rotirea pe loc devine mers înapoi mai lent decât viteza ei
    const val SPIN_REVERSE = 0.6f
}
