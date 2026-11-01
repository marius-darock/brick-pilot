package ro.awebdesign.brickpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.COLS
import ro.awebdesign.brickpilot.FloorAnalyzer.Companion.ROWS

/** Clasificarea podelei pe valori reale citite de pe telefon și pe obstacole sintetice. */
class FloorTest {

    // Grila Y/U/V din logcat, robotul pe parchet lucios gol, cu reflexie puternică în dreapta
    private val glossyEmptyFloor = listOf(
        "116/124/135 127/123/134 147/124/134 135/122/136 163/122/134 174/122/134 180/122/134 189/122/133 186/122/133",
        "112/123/135 120/122/136 133/122/136 137/121/136 153/121/135 175/122/134 171/122/134 173/122/134 179/122/133",
        "112/121/136 118/121/137 123/121/137 140/121/136 150/121/135 170/122/134 167/122/134 166/122/134 173/122/133",
        "117/121/137 116/120/137 119/121/137 137/121/136 142/122/135 154/122/134 165/122/134 160/122/134 165/122/134",
        "111/121/137 115/121/137 114/121/137 124/121/137 136/121/136 141/122/135 151/122/134 155/121/135 156/122/135",
        "107/121/136 111/121/136 112/121/136 117/121/136 127/122/136 131/121/136 139/122/135 145/121/135 153/122/135",
        "107/121/136 110/121/136 112/121/136 114/122/136 120/122/136 125/122/136 128/122/135 137/122/135 145/121/135"
    )

    private class Grid(val y: FloatArray, val u: FloatArray, val v: FloatArray)

    private fun parse(rows: List<String>): Grid {
        val y = FloatArray(COLS * ROWS)
        val u = FloatArray(COLS * ROWS)
        val v = FloatArray(COLS * ROWS)
        rows.forEachIndexed { r, line ->
            line.trim().split(Regex("\\s+")).forEachIndexed { c, cell ->
                val p = cell.split("/").map { it.toFloat() }
                y[r * COLS + c] = p[0]
                u[r * COLS + c] = p[1]
                v[r * COLS + c] = p[2]
            }
        }
        return Grid(y, u, v)
    }

    private fun classify(g: Grid, thr: Float): BooleanArray {
        // Referința, ca în aplicație: media celor 3 celule din mijlocul rândului de jos
        val b = (ROWS - 1) * COLS
        val idx = (3..5).map { b + it }
        val refY = idx.map { g.y[it] }.average().toFloat()
        val refU = idx.map { g.u[it] }.average().toFloat()
        val refV = idx.map { g.v[it] }.average().toFloat()
        return FloorAnalyzer.classify(g.y, g.u, g.v, refY, refU, refV, thr).obstacle
    }

    @Test
    fun glossyFloorWithReflectionIsAllFloor() {
        val g = parse(glossyEmptyFloor)
        for (thr in listOf(10f, 14f, 20f, 27f)) {
            val obstacle = classify(g, thr)
            assertFalse("prag $thr: podeaua goală nu are obstacole", obstacle.any { it })
        }
    }

    // Pune un obiect în grilă: înlocuiește celulele date cu o culoare
    private fun withObject(g: Grid, cols: IntRange, rows: IntRange, y: Float, u: Float, v: Float): Grid {
        val ny = g.y.copyOf()
        val nu = g.u.copyOf()
        val nv = g.v.copyOf()
        for (r in rows) for (c in cols) {
            ny[r * COLS + c] = y
            nu[r * COLS + c] = u
            nv[r * COLS + c] = v
        }
        return Grid(ny, nu, nv)
    }

    private fun assertObject(obstacle: BooleanArray, cols: IntRange, rows: IntRange) {
        for (r in rows) for (c in cols) assertTrue("celula ($r,$c) e obstacol", obstacle[r * COLS + c])
    }

    @Test
    fun detectsDarkObjectOnGlossyFloor() {
        // Ușă sau mobilă închisă la culoare (wenge), sus în centru
        val g = withObject(parse(glossyEmptyFloor), 3..5, 0..2, 40f, 124f, 133f)
        val obstacle = classify(g, 14f)
        assertObject(obstacle, 3..5, 0..2)
        assertFalse(obstacle[5 * COLS + 4])
    }

    @Test
    fun detectsColoredObjectOnGlossyFloor() {
        // Cutie roșie în stânga
        val g = withObject(parse(glossyEmptyFloor), 0..2, 1..4, 90f, 110f, 165f)
        assertObject(classify(g, 14f), 0..2, 1..4)
    }

    @Test
    fun detectsWhiteWallAboveFloor() {
        // Perete alb pe rândurile de sus, pe toată lățimea
        val g = withObject(parse(glossyEmptyFloor), 0..8, 0..1, 235f, 128f, 128f)
        assertObject(classify(g, 14f), 0..8, 0..1)
    }

    @Test
    fun detectsObjectInBottomCorner() {
        // Picior de scaun lipit de robot, jos în stânga
        val g = withObject(parse(glossyEmptyFloor), 0..1, 4..6, 50f, 125f, 131f)
        val obstacle = classify(g, 14f)
        assertObject(obstacle, 0..1, 4..6)
        assertFalse("restul rândului de jos rămâne podea", obstacle[6 * COLS + 5])
    }

    @Test
    fun objectSoftEdgeDoesNotSlipThroughAdaptation() {
        // Margine moale: o celulă de tranziție între podea și obiect, ca la o graniță prinsă la mijloc
        var g = withObject(parse(glossyEmptyFloor), 3..5, 3..3, 75f, 123f, 134f)
        g = withObject(g, 3..5, 0..2, 35f, 124f, 133f)
        assertObject(classify(g, 14f), 3..5, 0..2)
    }
}
