package ro.awebdesign.brickpilot

/**
 * Legătura dintre comenzile de mers și porturile hubului.
 * Portul A e motorul stâng, portul B e motorul drept.
 */
object Drivetrain {

    class PortSpeeds(val a: Int, val b: Int)

    /** turn > 0 virează la dreapta: roata stângă (A) merge mai repede decât cea dreaptă (B). */
    fun toPorts(d: Avoider.Drive, invertA: Boolean, invertB: Boolean): PortSpeeds {
        val m = Avoider.mix(d, invertLeft = invertA, invertRight = invertB)
        return PortSpeeds(a = m.left, b = m.right)
    }
}
