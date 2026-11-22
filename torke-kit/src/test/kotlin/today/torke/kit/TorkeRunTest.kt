package today.torke.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import today.torke.kit.engine.DemoRun
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.Vec3
import today.torke.kit.format.TorkeRun

class TorkeRunTest {
    @Test fun roundTrip() {
        val (motion, gps) = DemoRun.generate(seed = 3)
        val base = TorkeRun(recordedAt = "2027-01-15T08:00:00Z", device = "test", motion = motion, gps = gps)
        val run = base.copy(result = base.analyze())
        assertEquals(run, TorkeRun.decode(run.encode()))
    }

    @Test fun vectorsAreArraysAndTheFormatIsWritten() {
        val json = TorkeRun(motion = listOf(RawMotionSample(1.0, Vec3(1.0, 2.0, 3.0), Vec3(0.0, 0.0, -1.0))), gps = emptyList()).encode()
        assertTrue("\"user\":[1.0,2.0,3.0]" in json, json)
        assertTrue("\"format\":\"torke.run\"" in json, json)
        assertTrue("null" !in json, json)
    }

    @Test fun rejectsOtherFormatsAndNewerVersions() {
        assertFailsWith<TorkeRun.FormatException> { TorkeRun.decode("""{"format":"x","version":1,"motion":[],"gps":[]}""") }
        assertFailsWith<TorkeRun.FormatException> { TorkeRun.decode("""{"format":"torke.run","version":99,"motion":[],"gps":[]}""") }
    }

    /** The file TorkeKit for iOS wrote: Android must read it and get the same times. */
    @Test fun sharedFixtureFromIos() {
        val text = javaClass.getResource("/demo-42.torkerun.json")!!.readText()
        val run = TorkeRun.decode(text)
        val stored = run.result!!
        val again = run.analyze()!!
        assertEquals(stored.quarterMile!!, again.quarterMile!!, absoluteTolerance = 0.0005)
        assertEquals(stored.sixtyFeet!!, again.sixtyFeet!!, absoluteTolerance = 0.0005)
        assertEquals(stored.zeroTo100!!, again.zeroTo100!!, absoluteTolerance = 0.0005)
        assertEquals(stored.quarterMileKmh!!, again.quarterMileKmh!!, absoluteTolerance = 0.05)
    }

    /** Same seed, same run: DemoRun on Android generates what iOS generated. */
    @Test fun demoRunMatchesIos() {
        val ios = TorkeRun.decode(javaClass.getResource("/demo-42.torkerun.json")!!.readText())
        val (motion, gps) = DemoRun.generate(seed = 42)
        assertEquals(ios.motion.size, motion.size)
        assertEquals(ios.gps.size, gps.size)
        assertEquals(ios.gps.last().speed, gps.last().speed, absoluteTolerance = 1e-9)
        assertEquals(ios.motion[500].user.y, motion[500].user.y, absoluteTolerance = 1e-9)
    }
}
