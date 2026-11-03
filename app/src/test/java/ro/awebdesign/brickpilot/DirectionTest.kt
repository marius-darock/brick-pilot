package ro.awebdesign.brickpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifică sensurile pe robotul real: A = motorul stâng, montat în oglindă (comanda pozitivă îl
 * învârte înapoi), B = motorul drept, montat normal. Setarea implicită: A inversat, B nu.
 */
class DirectionTest {

    init {
        OpenCvForTests.load()
    }

    // Cum se mișcă fizic roțile pentru o comandă: pozitiv = înainte
    private fun wheels(d: Avoider.Drive): Pair<Int, Int> {
        val p = Drivetrain.toPorts(d, invertA = true, invertB = false)
        val left = -p.a      // A e montat în oglindă
        val right = p.b
        return Pair(left, right)
    }

    @Test
    fun forwardDrivesBothWheelsForward() {
        val (l, r) = wheels(Avoider.Drive(40, 0))
        assertTrue(l > 0 && r > 0)
        assertEquals(l, r)
    }

    @Test
    fun positiveTurnTurnsRight() {
        // Dreapta = roata stângă înainte, cea dreaptă înapoi
        val (l, r) = wheels(Avoider.Drive(0, 40))
        assertTrue(l > 0 && r < 0)
    }

    @Test
    fun lineOnRightSteersRight() {
        val f = LineFollower()
        val d = f.decide(LineResult(FloatArray(5) { 0.5f }, 640, 480, 0), 1000)
        val (l, r) = wheels(d)
        assertTrue("roata stângă trebuie să meargă mai repede", l > r)
        assertTrue(d.forward > 0)
    }

    @Test
    fun lineOnLeftSteersLeft() {
        val f = LineFollower()
        val (l, r) = wheels(f.decide(LineResult(FloatArray(5) { -0.5f }, 640, 480, 0), 1000))
        assertTrue(r > l)
    }

    @Test
    fun lostLineSearchesTowardLastSide() {
        val f = LineFollower()
        f.decide(LineResult(FloatArray(5) { 0.6f }, 640, 480, 0), 1000)
        // Întâi refacem drumul înapoi; după ce istoricul se termină, caută prin rotire
        f.decide(LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0), 1100)
        val d = f.decide(LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0), 1100 + LineFollower.RETRACE_MS + 100)
        assertEquals(LineFollower.Mode.SEARCH, f.mode)
        val (l, r) = wheels(d)
        assertTrue("caută spre dreapta, unde a fost linia", l > 0 && r < 0)
    }

    @Test
    fun noLineAtStartStaysStill() {
        val f = LineFollower()
        val d = f.decide(LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0), 1000)
        assertEquals(0, d.forward)
        assertEquals(0, d.turn)
    }

    private fun floor(obstacleCols: IntRange, obstacleRows: IntRange): FloorResult {
        val cols = FloorAnalyzer.COLS
        val rows = FloorAnalyzer.ROWS
        val obstacle = BooleanArray(cols * rows) { i -> i % cols in obstacleCols && i / cols in obstacleRows }
        return FloorResult(obstacle, FloatArray(cols * rows), 0f, 0f, 0f, FloatArray(3), 640, 480, 0)
    }

    @Test
    fun followTurnsTowardObjectOnRightAndApproaches() {
        val f = ObjectFollower()
        // Obiect în dreapta, departe (sus în grilă)
        val d = f.decide(floor(6..8, 0..1), 1000)
        val (l, r) = wheels(d)
        assertTrue(d.forward > 0)
        assertTrue(l > r)
    }

    @Test
    fun followTurnsTowardObjectOnLeft() {
        val f = ObjectFollower()
        val (l, r) = wheels(f.decide(floor(0..2, 0..1), 1000))
        assertTrue(r > l)
    }

    @Test
    fun followApproachesObjectAtMediumDistance() {
        val f = ObjectFollower()
        // Obiect în centru, cu 4 rânduri libere în fața lui
        val (l, r) = wheels(f.decide(floor(3..5, 0..2), 1000))
        assertTrue("merge înainte spre obiect", l > 0 && r > 0)
    }

    @Test
    fun followBacksOffWhenTooClose() {
        val f = ObjectFollower()
        // Obiect în centru, lipit de robot (jos în grilă)
        val (l, r) = wheels(f.decide(floor(3..5, 0..6), 1000))
        assertTrue("dă înapoi", l < 0 && r < 0)
    }

    @Test
    fun followDoesNotBackForCloseThingAtTheSide() {
        val f = ObjectFollower()
        // Ceva lipit de robot, dar în marginea stângă (de exemplu o roată în imagine)
        val d = f.decide(floor(0..1, 0..6), 1000)
        assertEquals(0, d.forward)
        assertTrue(d.turn < 0)
    }

    @Test
    fun followStopsBackingAfterLimit() {
        val f = ObjectFollower()
        val close = floor(3..5, 0..6)
        assertTrue(f.decide(close, 1000).forward < 0)
        assertTrue(f.decide(close, 2000).forward < 0)
        // Ținta nu se îndepărtează: după 1,5 s de mers înapoi se oprește
        assertEquals(0, f.decide(close, 2600).forward)
        assertEquals(0, f.decide(close, 5000).forward)
    }

    @Test
    fun followStopsWithoutTarget() {
        val f = ObjectFollower()
        val d = f.decide(floor(IntRange.EMPTY, IntRange.EMPTY), 1000)
        assertEquals(ObjectFollower.Mode.NO_TARGET, f.mode)
        assertEquals(0, d.forward)
        assertEquals(0, d.turn)
    }

    @Test
    fun avoiderSpinsTowardFreeRight() {
        val a = Avoider()
        val (l, r) = wheels(a.decide(floatArrayOf(0.1f, 0.1f, 1f), 1000))
        assertEquals(Avoider.Mode.SPIN, a.mode)
        assertTrue(l > 0 && r < 0)
    }

    @Test
    fun avoiderSpinsTowardFreeLeft() {
        val a = Avoider()
        val (l, r) = wheels(a.decide(floatArrayOf(1f, 0.1f, 0.1f), 1000))
        assertTrue(r > 0 && l < 0)
    }

    @Test
    fun avoiderCruiseSteersAwayFromObstacleOnLeft() {
        val a = Avoider()
        // Stânga mai blocată, deci virează ușor la dreapta
        val (l, r) = wheels(a.decide(floatArrayOf(0.3f, 1f, 1f), 1000))
        assertTrue(l > r && r > 0)
    }

    @Test
    fun avoiderRespectsSpeedSetting() {
        val fast = Avoider().apply { speedFactor = 1f }.decide(floatArrayOf(0.1f, 0.1f, 1f), 1000)
        val slow = Avoider().apply { speedFactor = 0.5f }.decide(floatArrayOf(0.1f, 0.1f, 1f), 1000)
        assertEquals(fast.turn / 2, slow.turn)
    }

    @Test
    fun avoiderCornerKeepsDirectionThenEscapes() {
        val a = Avoider()
        val blockedRightFree = floatArrayOf(0.1f, 0.1f, 1f)
        val blockedLeftFree = floatArrayOf(1f, 0.1f, 0.1f)
        var t = 1000L
        a.decide(blockedRightFree, t)                 // rotire spre dreapta
        t += 900
        a.decide(blockedLeftFree, t)                  // se termină rotirea, blocat din nou
        // În colț nu schimbă partea, chiar dacă acum stânga pare liberă
        val (l, r) = wheels(a.decide(blockedLeftFree, t + 10))
        assertEquals(Avoider.Mode.SPIN, a.mode)
        assertTrue(l > 0 && r < 0)
        t += 900
        a.decide(blockedLeftFree, t)                  // al treilea blocaj la rând
        assertEquals(Avoider.Mode.ESCAPE_BACK, a.mode)
    }

    // ---------------- Detector de linie pe imagini sintetice ----------------

    private fun detect(lum: (Int, Int) -> Int): FloatArray = LineDetector { }.detect(640, 480, lum)

    @Test
    fun detectsNarrowTapeOnRight() {
        // Bandă de 20 px (3% din lățime) centrată la x = 480, adică +0.5
        val pos = detect { x, _ -> if (x in 470..490) 30 else 160 }
        for (p in pos) assertEquals(0.5f, p, 0.03f)
    }

    @Test
    fun ignoresParquetSeams() {
        // Rosturi de 1 px peste tot, exact pe eșantioane (x = 4i + 2), plus banda reală la x = 160 (-0.5)
        val pos = detect { x, _ -> if (x in 150..170) 30 else if (x % 48 == 2) 60 else 160 }
        for (p in pos) assertEquals(-0.5f, p, 0.03f)
    }

    @Test
    fun noLineOnPlainFloor() {
        val pos = detect { _, _ -> 150 }
        assertTrue(pos.all { it.isNaN() })
    }

    @Test
    fun followsCurveAcrossBands() {
        // Linie care se curbează spre dreapta pe măsură ce urcă în imagine (spre departe)
        val pos = detect { x, y ->
            val center = 320 + (480 - y) * 1.2f
            if (kotlin.math.abs(x - center) < 12) 30 else 160
        }
        assertTrue(pos.none { it.isNaN() })
        for (b in 1 until pos.size) assertTrue(pos[b] > pos[b - 1])
    }

    @Test
    fun ignoresDarkObjectAwayFromLine() {
        // Linia la centru; un obiect închis, departe de ea, apare doar pe benzile de sus
        val pos = detect { x, y -> if (x in 310..330) 30 else if (x in 560..600 && y < 330) 20 else 160 }
        for (p in pos) assertEquals(0f, p, 0.03f)
    }
}
