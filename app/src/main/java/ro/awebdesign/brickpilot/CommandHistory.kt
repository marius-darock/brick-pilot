package ro.awebdesign.brickpilot

/**
 * Ultimele comenzi de mers, ca robotul să se poată întoarce pe drumul parcurs. Comenzile se redau de
 * la cea mai nouă spre cea mai veche, cu viteza inversată: la tipul 2 același unghi al roților în mers
 * înapoi refac același arc, la tipul 1 vitezele inversate ale roților la fel.
 */
class CommandHistory(private val keepMs: Long) {

    private class Entry(val timeMs: Long, val a: Float, val b: Float)

    private val entries = ArrayDeque<Entry>()
    private var replayStartMs = 0L
    private var replayFromMs = 0L

    val isReplaying get() = replayStartMs > 0L

    fun clear() {
        entries.clear()
        replayStartMs = 0L
    }

    fun record(nowMs: Long, a: Float, b: Float) {
        entries.addLast(Entry(nowMs, a, b))
        while (entries.isNotEmpty() && nowMs - entries.first().timeMs > keepMs) entries.removeFirst()
    }

    /** Începe întoarcerea: comenzile se redau în ordine inversă, în același ritm în care au fost date. */
    fun startReplay(nowMs: Long): Boolean {
        if (entries.isEmpty()) return false
        replayStartMs = nowMs
        replayFromMs = entries.last().timeMs
        return true
    }

    fun stopReplay() {
        replayStartMs = 0L
    }

    /** Comanda de redat acum, (a, b) ca la înregistrare, sau null când istoricul s-a terminat. */
    fun replay(nowMs: Long): Pair<Float, Float>? {
        if (!isReplaying) return null
        val at = replayFromMs - (nowMs - replayStartMs)
        // Cea mai nouă comandă dată înainte de momentul „at”
        val e = entries.lastOrNull { it.timeMs <= at }
        if (e == null || at < entries.first().timeMs) {
            stopReplay()
            entries.clear()
            return null
        }
        return Pair(e.a, e.b)
    }
}
