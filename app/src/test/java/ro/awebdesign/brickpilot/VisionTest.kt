package ro.awebdesign.brickpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.FINE_COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.FINE_ROWS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.ROWS
import kotlin.math.abs

/** Recunoașterea liniei și a țintei pe imagini sintetice, plus regulile de mers care depind de ele. */
class VisionTest {

    // ---------------- Linia ----------------

    @Test
    fun strayDarkSpotOnOneBandIsNotALine() {
        // Doar o pată pe banda de jos, fără continuare în sus: nu e o linie
        val pos = LineDetector { }.detect(640, 480) { x, y -> if (x in 100..120 && y > 440) 20 else 160 }
        assertTrue(pos.all { it.isNaN() })
    }

    @Test
    fun spotNextToTheLineIsIgnored() {
        // Linia la centru pe toate benzile, o pată pe o singură bandă în dreapta
        val pos = LineDetector { }.detect(640, 480) { x, y ->
            if (x in 310..330) 30 else if (x in 520..540 && y in 350..370) 20 else 160
        }
        for (p in pos) assertEquals(0f, p, 0.03f)
    }

    @Test
    fun keepsTrackedLineWhenASideLineAppears() {
        val d = LineDetector { }
        // Întâi doar linia noastră, în stânga
        d.detect(640, 480) { x, _ -> if (x in 150..170) 30 else 160 }
        // Apoi apare și o linie completă în dreapta; rămânem pe cea urmărită
        val pos = d.detect(640, 480) { x, _ -> if (x in 150..170 || x in 470..490) 30 else 160 }
        for (p in pos) assertEquals(-0.5f, p, 0.03f)
    }

    @Test
    fun shadowEdgeIsNotALine() {
        // Jumătatea dreaptă a podelei e în umbră: o margine, nu o bandă închisă între două zone deschise
        val pos = LineDetector { }.detect(640, 480) { x, _ -> if (x > 420) 40 else 160 }
        assertTrue(pos.all { it.isNaN() })
    }

    @Test
    fun type1RetracesBackwardsAfterLosingTheLine() {
        val f = LineFollower()
        var t = 1000L
        repeat(30) { f.decide(LineResult(FloatArray(5) { 0.5f }, 640, 480, 0), t); t += 33 }
        val back = f.decide(LineResult(FloatArray(5) { Float.NaN }, 640, 480, 0), t)
        assertEquals(LineFollower.Mode.RETRACE, f.mode)
        assertTrue("înapoi", back.forward < 0)
        assertTrue("refăcând virajul la dreapta: înapoi cu turn negativ", back.turn < 0)
    }

    @Test
    fun historyReplaysNewestFirstAndEnds() {
        val h = CommandHistory(1000)
        h.record(0, 1f, 0f)
        h.record(500, 2f, 0f)
        h.record(900, 3f, 0f)
        assertTrue(h.startReplay(1000))
        assertEquals(3f, h.replay(1000)!!.first)
        assertEquals(2f, h.replay(1300)!!.first)
        assertEquals(1f, h.replay(1700)!!.first)
        assertNull(h.replay(2000))
        assertFalse(h.isReplaying)
    }

    // ---------------- Ținta după culoare ----------------

    private val floorY = 150
    private val floorU = 128
    private val floorV = 128

    /** Podea cenușie, un obiect roșu și opțional un obstacol albastru (perete, mobilă). */
    private fun scene(redCol: Int, redRow: Int, blueCol: Int = -1, blueRow: Int = -1, near: Boolean = true): FloorResult {
        val fy = IntArray(FINE_COLS * FINE_ROWS) { floorY }
        val fu = IntArray(FINE_COLS * FINE_ROWS) { floorU }
        val fv = IntArray(FINE_COLS * FINE_ROWS) { floorV }
        fun paint(col: Int, row: Int, y: Int, u: Int, v: Int) {
            for (r in row - 2..row + 2) for (c in col - 2..col + 2) {
                if (r !in 0 until FINE_ROWS || c !in 0 until FINE_COLS) continue
                val i = r * FINE_COLS + c
                fy[i] = y; fu[i] = u; fv[i] = v
            }
        }
        paint(redCol, redRow, 90, 100, 190)
        if (blueCol >= 0) paint(blueCol, blueRow, 90, 190, 100)
        // Grila mare marchează obstacolele aproximativ unde sunt obiectele, ca la FloorAnalyzer
        val obstacle = BooleanArray(COLS * ROWS)
        fun mark(col: Int, row: Int) {
            val gc = col * COLS / FINE_COLS
            val frac = row / FINE_ROWS.toFloat()
            if (frac < FloorAnalyzer.TOP_IGNORE) return
            val gr = ((frac - FloorAnalyzer.TOP_IGNORE) / (1 - FloorAnalyzer.TOP_IGNORE) * ROWS).toInt().coerceIn(0, ROWS - 1)
            obstacle[gr * COLS + gc] = true
        }
        if (near) mark(redCol, redRow)
        if (blueCol >= 0) mark(blueCol, blueRow)
        return FloorResult(
            obstacle, FloatArray(COLS * ROWS), floorY.toFloat(), floorU.toFloat(), floorV.toFloat(),
            FloatArray(3), 640, 480, 0, fy, fu, fv
        )
    }

    @Test
    fun locksOnTheNearestObjectAndFollowsItsColor() {
        val tr = ObjectTracker()
        // Roșu aproape, în dreapta: devine ținta
        val first = tr.track(scene(redCol = 30, redRow = 26))
        assertTrue(tr.locked)
        assertNotNull(first)
        assertTrue(first!!.bearing > 0.3f)
        // Apare un obstacol albastru mai aproape, în stânga: ținta rămâne obiectul roșu
        val next = tr.track(scene(redCol = 28, redRow = 22, blueCol = 8, blueRow = 27))
        assertNotNull(next)
        assertTrue("rămâne pe obiectul roșu", next!!.bearing > 0.2f)
    }

    @Test
    fun forgetsTheTargetAfterItIsGoneForAWhile() {
        val tr = ObjectTracker()
        tr.track(scene(redCol = 20, redRow = 26))
        assertTrue(tr.locked)
        val empty = scene(redCol = -10, redRow = -10, near = false)
        repeat(ObjectTracker.LOST_FRAMES + 1) { assertNull(tr.track(empty)) }
        assertFalse(tr.locked)
    }

    @Test
    fun followerIgnoresOtherObstaclesOnceLocked() {
        val f = ObjectFollower()
        f.decide(scene(redCol = 32, redRow = 24), 1000)
        // Obstacolul albastru e mai aproape și în stânga; robotul virează totuși spre dreapta, spre țintă
        val d = f.decide(scene(redCol = 32, redRow = 22, blueCol = 6, blueRow = 28), 1033)
        assertTrue(f.locked)
        assertTrue(d.turn > 0)
    }

    // ---------------- Urmărirea la tipul 2 ----------------

    @Test
    fun type2FollowPointsTheWheelsAtTheTarget() {
        val f = ObjectFollower()
        val d = f.decide(scene(redCol = 34, redRow = 16), 1000)
        val s = f.steering(d, 1f)
        assertTrue("roțile spre dreapta, spre țintă", s.steer > 0)
        assertTrue(s.speed > 0)
    }

    @Test
    fun type2FollowBacksOffStraight() {
        val f = ObjectFollower()
        // Ținta lipită de robot, puțin în dreapta: înapoi, dar fără roțile bracate invers
        val d = f.decide(scene(redCol = 22, redRow = 29), 1000)
        val s = f.steering(d, 1f)
        assertTrue(s.speed < 0)
        assertEquals(0f, s.steer, 0.001f)
    }

    @Test
    fun type2FollowCreepsToTurnTowardsASideTarget() {
        val f = ObjectFollower()
        val s = f.steering(Avoider.Drive(0, 30), 1f)
        // Fără țintă nu se mișcă deloc
        assertEquals(0, s.speed)
        f.decide(scene(redCol = 36, redRow = 25), 1000)
        val creep = f.steering(Avoider.Drive(0, 30), 1f)
        assertTrue(creep.steer > 0 && creep.speed > 0)
        assertTrue(abs(creep.speed) < 30)
    }
}
