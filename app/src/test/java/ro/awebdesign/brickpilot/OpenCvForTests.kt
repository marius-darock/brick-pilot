package ro.awebdesign.brickpilot

/** Încarcă OpenCV pentru calculator o singură dată, înainte de testele care folosesc viziunea. */
object OpenCvForTests {
    private val loaded by lazy { nu.pattern.OpenCV.loadLocally() }

    fun load() = loaded
}
