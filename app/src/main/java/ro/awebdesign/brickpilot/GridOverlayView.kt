package ro.awebdesign.brickpilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.REF_COUNT
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.REF_FIRST_COL
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.ROWS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.TOP_IGNORE

/** Desenează peste preview ce vede robotul: obstacole roșu, podea verde, referința galben. */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile private var result: FloorResult? = null
    @Volatile private var line: LineResult? = null

    /** Modul linie: punctul țintit de pure pursuit, (lateral, înainte) în vederea de sus. */
    @Volatile var aim: FloatArray? = null

    /** Ținta recunoscută în modul urmărire, desenată cu un chenar. */
    @Volatile var target: TrackedTarget? = null

    private val obstaclePaint = Paint().apply { color = Color.argb(110, 255, 0, 0) }
    private val freePaint = Paint().apply { color = Color.argb(35, 0, 255, 0) }
    private val linePaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val refPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint().apply { color = Color.argb(200, 0, 200, 255) }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val targetPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 6f
    }
    private val lostPaint = Paint().apply {
        color = Color.argb(200, 255, 0, 0)
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val content = RectF()
    private val cell = RectF()

    fun update(r: FloorResult) {
        result = r
        line = null
        postInvalidateOnAnimation()
    }

    fun updateLine(r: LineResult) {
        line = r
        result = null
        postInvalidateOnAnimation()
    }

    // PreviewView e pe FIT_CENTER, deci cadrul ocupă un dreptunghi centrat
    private fun fitContent(frameWidth: Int, frameHeight: Int) {
        val scale = minOf(width / frameWidth.toFloat(), height / frameHeight.toFloat())
        val cw = frameWidth * scale
        val ch = frameHeight * scale
        content.set((width - cw) / 2f, (height - ch) / 2f, (width + cw) / 2f, (height + ch) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        line?.let {
            drawLine(canvas, it)
            return
        }
        val r = result ?: return
        fitContent(r.frameWidth, r.frameHeight)

        val top = content.top + content.height() * TOP_IGNORE
        val cellW = content.width() / COLS
        val cellH = (content.bottom - top) / ROWS

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                cell.set(
                    content.left + col * cellW, top + row * cellH,
                    content.left + (col + 1) * cellW, top + (row + 1) * cellH
                )
                canvas.drawRect(cell, if (r.obstacle[row * COLS + col]) obstaclePaint else freePaint)
                canvas.drawRect(cell, linePaint)
                // Distanța față de podea, comparată cu pragul din slider
                canvas.drawText(
                    r.distance[row * COLS + col].toInt().toString(),
                    cell.centerX(), cell.centerY() + textPaint.textSize / 3f, textPaint
                )
            }
        }

        cell.set(
            content.left + REF_FIRST_COL * cellW, content.bottom - cellH,
            content.left + (REF_FIRST_COL + REF_COUNT) * cellW, content.bottom
        )
        canvas.drawRect(cell, refPaint)

        // Bare verticale cu free[] pe fiecare treime
        val thirdW = content.width() / 3f
        for (t in 0 until 3) {
            val cx = content.left + thirdW * (t + 0.5f)
            val h = (content.bottom - top) * r.free[t]
            canvas.drawRect(cx - 6f, content.bottom - h, cx + 6f, content.bottom, barPaint)
        }

        target?.let {
            cell.set(
                content.left + it.left * content.width(), content.top + it.top * content.height(),
                content.left + it.right * content.width(), content.top + it.bottom * content.height()
            )
            canvas.drawRect(cell, targetPaint)
        }
    }

    // Modul linie: zona analizată, linia găsită (curba OpenCV) și punctul țintit
    private fun drawLine(canvas: Canvas, r: LineResult) {
        fitContent(r.frameWidth, r.frameHeight)
        val midX = content.centerX()
        val roiY = content.top + content.height() * LineDetector.ROI_TOP
        canvas.drawLine(content.left, roiY, content.right, roiY, linePaint)
        canvas.drawLine(midX, roiY, midX, content.bottom, linePaint)

        fun px(x: Float) = content.left + (x + 1f) / 2f * content.width()
        fun py(y: Float) = content.top + y * content.height()

        val c = r.curve
        if (c.size >= 4) {
            for (i in 1 until c.size / 2) {
                canvas.drawLine(px(c[2 * i - 2]), py(c[2 * i - 1]), px(c[2 * i]), py(c[2 * i + 1]), pathPaint)
            }
            // Punctul țintit: punctul curbei cel mai apropiat de țintă în vederea de sus
            val a = aim
            val g = r.ground
            if (a != null && g.size == c.size) {
                var best = 0
                var bestD = Float.MAX_VALUE
                for (i in 0 until g.size / 2) {
                    val d = abs(g[2 * i] - a[0]) + abs(g[2 * i + 1] - a[1])
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                canvas.drawCircle(px(c[2 * best]), py(c[2 * best + 1]), 18f, aimPaint)
            }
        } else {
            for (b in r.positions.indices) {
                val p = r.positions[b]
                if (!p.isNaN()) canvas.drawCircle(px(p), py(LineDetector.BAND_Y[b]), 14f, pointPaint)
            }
        }
        if (!r.found) canvas.drawRect(content, lostPaint)
    }
}
