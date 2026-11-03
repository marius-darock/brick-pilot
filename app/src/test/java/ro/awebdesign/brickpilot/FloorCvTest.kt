package ro.awebdesign.brickpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.ROWS

/** Podeaua și distanța până la perete, cu OpenCV, pe imagini YUV sintetice de 160x120. */
class FloorCvTest {

    init {
        OpenCvForTests.load()
    }

    // Grila reală de pe parchetul lucios (Y/U/V pe celulă), cu reflexie în dreapta, din FloorTest
    private val glossy = listOf(
        "116/124/135 127/123/134 147/124/134 135/122/136 163/122/134 174/122/134 180/122/134 189/122/133 186/122/133",
        "112/123/135 120/122/136 133/122/136 137/121/136 153/121/135 175/122/134 171/122/134 173/122/134 179/122/133",
        "112/121/136 118/121/137 123/121/137 140/121/136 150/121/135 170/122/134 167/122/134 166/122/134 173/122/133",
        "117/121/137 116/120/137 119/121/137 137/121/136 142/122/135 154/122/134 165/122/134 160/122/134 165/122/134",
        "111/121/137 115/121/137 114/121/137 124/121/137 136/121/136 141/122/135 151/122/134 155/121/135 156/122/135",
        "107/121/136 111/121/136 112/121/136 117/121/136 127/122/136 131/121/136 139/122/135 145/121/135 153/122/135",
        "107/121/136 110/121/136 112/121/136 114/122/136 120/122/136 125/122/136 128/122/135 137/122/135 145/121/135"
    )

    /** Imaginea 160x120: sus (35%) o continuare a primului rând, jos grila întinsă lin, ca o podea reală. */
    private fun glossyImage(): Mat {
        val grid = Mat(ROWS, COLS, CvType.CV_8UC3)
        glossy.forEachIndexed { r, line ->
            line.trim().split(Regex(" +")).forEachIndexed { c, cell ->
                val p = cell.split("/").map { it.toInt().toByte() }
                grid.put(r, c, byteArrayOf(p[0], p[1], p[2]))
            }
        }
        val img = Mat(120, 160, CvType.CV_8UC3)
        val top = (120 * FloorAnalyzer.TOP_IGNORE).toInt()
        val floor = Mat()
        Imgproc.resize(grid, floor, Size(160.0, (120 - top).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        floor.copyTo(img.submat(top, 120, 0, 160))
        val sky = Mat()
        Imgproc.resize(grid.row(0), sky, Size(160.0, top.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        sky.copyTo(img.submat(0, top, 0, 160))
        return img
    }

    private fun box(img: Mat, x0: Int, x1: Int, y0: Int, y1: Int, y: Int, u: Int, v: Int): Mat {
        val m = img.clone()
        for (yy in y0 until y1) for (xx in x0 until x1) m.put(yy, xx, byteArrayOf(y.toByte(), u.toByte(), v.toByte()))
        return m
    }

    private fun analyze(img: Mat): FloorResult = FloorAnalyzer { }.analyzeYuv(img, 640, 480, 0)

    @Test
    fun glossyFloorIsFreeAndFarAway() {
        val r = analyze(glossyImage())
        assertFalse("parchetul lucios gol nu are obstacole", r.obstacle.any { it })
        assertTrue("nimic în față: ${r.frontDistance}", r.frontDistance > 0.9f)
    }

    @Test
    fun wallAheadGivesAShorterDistanceWhenCloser() {
        // Perete închis la culoare pe toată lățimea, întâi departe, apoi aproape
        val far = analyze(box(glossyImage(), 0, 160, 0, 60, 50, 125, 131)).frontDistance
        val near = analyze(box(glossyImage(), 0, 160, 0, 95, 50, 125, 131)).frontDistance
        assertTrue("peretele apare: $far", far < 0.9f)
        assertTrue("mai aproape = distanță mai mică: $near < $far", near < far)
    }

    @Test
    fun objectAtTheSideDoesNotShortenTheFrontDistance() {
        // Un scaun în marginea din stânga nu e în fața robotului
        val r = analyze(box(glossyImage(), 0, 30, 50, 110, 50, 125, 131))
        assertTrue("în față e liber: ${r.frontDistance}", r.frontDistance > 0.9f)
    }

    @Test
    fun parquetSeamsDoNotStopTheRobot() {
        // Rosturi subțiri de 1 px, închise, pe toată imaginea
        var img = glossyImage()
        for (x in 0 until 160 step 12) img = box(img, x, x + 1, 0, 120, 70, 122, 135)
        val r = analyze(img)
        assertTrue("rosturile nu sunt perete: ${r.frontDistance}", r.frontDistance > 0.9f)
        assertFalse(r.obstacle.any { it })
    }

    @Test
    fun avoiderStopsBeforeAWallTheGridStillCallsFree() {
        val a = Avoider()
        val free = floatArrayOf(1f, 1f, 1f)
        a.decide(free, 1000, front = 0.1f)
        assertTrue(a.mode == Avoider.Mode.SPIN)
        val b = Avoider()
        b.decide(free, 1000, front = 0.3f)
        assertTrue(b.mode == Avoider.Mode.SLOW)
    }
}
