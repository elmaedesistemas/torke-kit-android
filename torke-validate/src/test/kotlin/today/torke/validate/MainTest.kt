package today.torke.validate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    private fun runCapturing(vararg args: String): Pair<Int, String> {
        val lines = mutableListOf<String>()
        val code = run(args.toList()) { lines += it }
        return code to lines.joinToString("\n")
    }

    @Test fun setPasses() {
        val (code, text) = runCapturing("Fixtures/validation.json")
        assertEquals(0, code)
        assertTrue("PASS" in text)
    }

    @Test fun singleRunFromFlags() {
        val (code, text) = runCapturing("Fixtures/demo-42.torkerun.json", "--60ft", "2.30", "--json")
        assertEquals(1, code, text) // 0.2 s off: over the 0.10 target
        assertTrue("\"sixtyFeet\"" in text)
    }

    @Test fun badInput() {
        assertEquals(2, runCapturing("nope.json").first)
        assertEquals(2, runCapturing("Fixtures/demo-42.torkerun.json").first)
        assertEquals(2, runCapturing().first)
    }
}
