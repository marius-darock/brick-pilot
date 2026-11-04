package ro.awebdesign.brickpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Cadre reale de pe telefon: bandă pe parchet, rosturi și umbre. */
class RealFrameTest {

    init {
        OpenCvForTests.load()
    }

    private fun frame(name: String): Mat = Imgcodecs.imread("src/test/resources/frames/$name", Imgcodecs.IMREAD_GRAYSCALE)

    private fun show(r: LineResult) = r.positions.joinToString(" ") { if (it.isNaN()) "--" else "%.2f".format(it) }

    /** Parchetul real de pe mașină, cu benzile șterse: rosturile subțiri rămân. */
    private fun floorWithSeams(): Mat {
        val m = frame("tape_on_parquet.png")
        val tape = Mat()
        Imgproc.threshold(m, tape, 90.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(tape, tape, Imgproc.MORPH_OPEN, k)
        Imgproc.dilate(tape, tape, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0)))
        val around = Mat()
        Imgproc.medianBlur(m, around, 51)
        around.copyTo(m, tape)
        return m
    }

    // Pictează o bandă dreaptă, care se îngustează cu perspectiva
    private fun withTape(m: Mat, cx: Int, bottomWidth: Int, topWidth: Int): Mat {
        val top = (480 * LineDetector.ROI_TOP).toInt() - 30
        for (y in top until 480) {
            val half = (topWidth + (bottomWidth - topWidth) * (y - top) / (480 - top)) / 2.0
            for (x in (cx - half).toInt()..(cx + half).toInt()) m.put(y, x, byteArrayOf(30))
        }
        return m
    }

    @Test
    fun onlyTheRealTapesAreCandidates() {
        val r = LineDetector { }.detectMat(frame("tape_on_parquet.png"), 0)
        assertTrue("banda lipsește: ${show(r)}", r.found)
        // Banda din stânga, nu un rost
        for (p in r.positions) if (!p.isNaN()) assertTrue("nu e pe bandă: ${show(r)}", p < -0.7f)
    }

    @Test
    fun seamsAloneAreNotALine() {
        val r = LineDetector { }.detectMat(floorWithSeams(), 0)
        assertTrue("a luat un rost drept linie: ${show(r)}", !r.found)
    }

    @Test
    fun seamsAndShadowsAreNotALine() {
        val r = LineDetector { }.detectMat(frame("seams_and_shadows.png"), 0)
        assertTrue("a luat un rost sau o umbră drept linie: ${show(r)}", !r.found)
    }

    @Test
    fun thinTapeOnTheRealFloorIsFound() {
        // 6-8 mm la scara cadrului de pe mașină (banda reală are ~14 px jos): ~5 px jos, ~3 px sus
        val r = LineDetector { }.detectMat(withTape(floorWithSeams(), 320, 5, 3), 0)
        assertTrue("linia de 6-8 mm lipsește: ${show(r)}", r.positions.count { !it.isNaN() } >= 3)
        for (p in r.positions) if (!p.isNaN()) assertEquals(0f, p, 0.05f)
    }

    @Test
    fun seamsDoNotReplaceALostTape() {
        val d = LineDetector { }
        assertTrue(d.detectMat(withTape(floorWithSeams(), 320, 14, 6), 0).found)
        // Banda iese din imagine, rămân rosturile
        val r = d.detectMat(floorWithSeams(), 33)
        assertTrue("a sărit pe un rost: ${show(r)}", !r.found)
    }

    @Test
    fun tapeThroughAShadowIsNotLost() {
        // O umbră largă peste jumătate din podea, banda trece prin ea
        val m = withTape(floorWithSeams(), 320, 14, 6)
        val shadow = m.submat(200, 480, 280, 640)
        val darker = Mat()
        shadow.convertTo(darker, -1, 0.6, 0.0)
        darker.copyTo(shadow)
        val r = LineDetector { }.detectMat(m, 0)
        assertTrue("linia s-a pierdut în umbră: ${show(r)}", r.positions.count { !it.isNaN() } >= 3)
        for (p in r.positions) if (!p.isNaN()) assertEquals(0f, p, 0.06f)
    }
}
