package ro.awebdesign.brickpilot

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * Vederea de sus a podelei. Zona de imagine dintre rândul topFrac și marginea de jos e un trapez pe
 * podea (aceeași lățime de podea apare mai îngustă departe), pe care îl întindem într-un dreptunghi:
 * lateral -1..1, înainte 0 (lângă robot) .. 1 (marginea de sus a zonei).
 */
object GroundView {

    // La marginea de sus a zonei, lățimea podelei de jos ocupă doar 2·TOP_HALF_WIDTH din imagine
    const val TOP_HALF_WIDTH = 0.25

    fun homography(w: Int, h: Int, top: Int): Mat {
        val src = MatOfPoint2f(
            Point(0.0, h.toDouble()), Point(w.toDouble(), h.toDouble()),
            Point(w * (0.5 + TOP_HALF_WIDTH), top.toDouble()), Point(w * (0.5 - TOP_HALF_WIDTH), top.toDouble())
        )
        val dst = MatOfPoint2f(Point(-1.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(-1.0, 1.0))
        val hm = Imgproc.getPerspectiveTransform(src, dst)
        src.release()
        dst.release()
        return hm
    }

    /** Punctele (x, y) din imagine devin perechi (lateral, înainte) pe podea. */
    fun toGround(pts: List<Point>, w: Int, h: Int, top: Int): FloatArray {
        if (pts.isEmpty()) return FloatArray(0)
        val hm = homography(w, h, top)
        val input = MatOfPoint2f(*pts.toTypedArray())
        val output = MatOfPoint2f()
        Core.perspectiveTransform(input, output, hm)
        val out = output.toArray()
        hm.release()
        input.release()
        output.release()
        val g = FloatArray(out.size * 2)
        for (i in out.indices) {
            g[2 * i] = out[i].x.toFloat()
            g[2 * i + 1] = out[i].y.toFloat()
        }
        return g
    }
}
