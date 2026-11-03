package ro.awebdesign.brickpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Tipul 2: direcție pe motorul A, tracțiune pe motorul B. */
class SteeringTest {

    private val range = SteeringRange(center = 1000, half = 80)

    @Test
    fun straightKeepsWheelsCentered() {
        val p = Drivetrain.toSteerPorts(Avoider.Drive(40, 0), range, invertA = false, invertB = false)
        assertEquals(1000, p.steerPosition)
        assertEquals(40, p.speedB)
    }

    @Test
    fun positiveTurnSteersRight() {
        val s = Drivetrain.toSteering(Avoider.Drive(40, 20))
        assertTrue(s.steer > 0)
        assertTrue(s.speed > 0)
    }

    @Test
    fun curvatureDoesNotDependOnSpeedFactor() {
        val fast = Drivetrain.toSteering(Avoider.Drive(40, 20))
        val slow = Drivetrain.toSteering(Avoider.Drive(20, 10))
        assertEquals(fast.steer, slow.steer, 0.001f)
    }

    @Test
    fun spinBecomesReverseWithOppositeLock() {
        // Rotire pe loc spre dreapta: înapoi cu roțile bracate la stânga, botul merge spre dreapta
        val s = Drivetrain.toSteering(Avoider.Drive(0, 60))
        assertEquals(-1f, s.steer, 0.001f)
        assertTrue(s.speed < 0)
    }

    @Test
    fun reversingTurnRightSteersLeft() {
        val s = Drivetrain.toSteering(Avoider.Drive(-30, 15))
        assertTrue(s.steer < 0)
        assertEquals(-30, s.speed)
    }

    @Test
    fun steeringNeverLeavesCalibratedRange() {
        for (turn in -100..100 step 10) for (fwd in listOf(-40, -5, 0, 5, 40)) {
            val p = Drivetrain.toSteerPorts(Avoider.Drive(fwd, turn), range, invertA = false, invertB = false)
            assertTrue(abs(p.steerPosition - range.center) <= range.half)
        }
    }

    @Test
    fun invertFlipsSteeringAndDrive() {
        val n = Drivetrain.toSteerPorts(Avoider.Drive(40, 20), range, invertA = false, invertB = false)
        val i = Drivetrain.toSteerPorts(Avoider.Drive(40, 20), range, invertA = true, invertB = true)
        assertEquals(n.steerPosition - 1000, 1000 - i.steerPosition)
        assertEquals(n.speedB, -i.speedB)
    }

    @Test
    fun rangeStaysAwayFromEnds() {
        val r = SteeringCalibrator.rangeFromEnds(-50, 150)!!
        assertEquals(50, r.center)
        assertTrue(r.half < 100)
        assertTrue(r.half <= 100 - SteeringCalibrator.END_MARGIN)
        assertNull(SteeringCalibrator.rangeFromEnds(10, 20))
    }

    /** Un motor simulat cu opritori la -120 și +60 de grade. */
    private class FakeSteer : SteeringCalibrator.Output {
        var pos = 0
        var speed = 0
        var centered: Int? = null
        var range: SteeringRange? = null
        var failure: String? = null
        var requested = false

        override fun runSteer(speed: Int, maxPower: Int) { this.speed = speed }
        override fun brakeSteer() { speed = 0 }
        override fun requestSteerPosition() { requested = true }
        override fun centerSteer(position: Int) { centered = position; speed = 0 }
        override fun onCalibrated(range: SteeringRange) { this.range = range }
        override fun onFailed(message: String) { failure = message }

        fun step() { pos = (pos + speed / 5).coerceIn(-120, 60) }
    }

    private fun simulate(f: FakeSteer, c: SteeringCalibrator, answer: Boolean = true) {
        var now = 0L
        c.start(now)
        while (c.isRunning && now < 20_000) {
            now += 20
            f.step()
            // Hubul raportează doar mișcări de cel puțin 5 grade, plus răspunsul la cerere
            if (answer) c.onPosition(f.pos / 5 * 5, now)
            c.tick(now)
        }
    }

    @Test
    fun calibrationFindsBothEndsAndCenters() {
        val f = FakeSteer()
        val c = SteeringCalibrator(f)
        simulate(f, c)
        assertTrue(f.requested)
        assertNull(f.failure)
        val r = f.range!!
        assertEquals(-30, r.center)
        assertTrue(r.half in 60..85)
        assertEquals(r.center, f.centered)
        assertEquals(SteeringCalibrator.Phase.DONE, c.phase)
    }

    @Test
    fun calibrationFailsWhenMotorIsSilent() {
        val f = FakeSteer()
        val c = SteeringCalibrator(f)
        simulate(f, c, answer = false)
        assertNotNull(f.failure)
        assertNull(f.range)
    }

    @Test
    fun cancelStopsTheMotor() {
        val f = FakeSteer()
        val c = SteeringCalibrator(f)
        c.start(0)
        c.onPosition(0, 0)
        c.tick(20)
        assertTrue(f.speed != 0)
        c.cancel()
        assertEquals(0, f.speed)
        assertNotNull(f.failure)
    }
}
