package today.torke.kit

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import today.torke.kit.engine.AxisCalibration
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.RunAnalyzer
import today.torke.kit.format.TorkeRun
import today.torke.kit.input.RecordedRunProvider
import today.torke.kit.session.RunSession

class GPSRateTest {
    @Test fun snapshotReportsTheGpsRate() {
        for (hz in listOf(1.0, 5.0, 10.0)) {
            val sim = Simulator.constantLaunch(gpsHz = hz)
            val session = RunSession()
            assertNull(session.snapshot.value.gpsRate)
            session.arm()
            RecordedRunProvider.merge(TorkeRun(motion = sim.motion, gps = sim.gps)).forEach { session.process(it) }
            assertEquals(hz, session.snapshot.value.gpsRate ?: 0.0, hz * 0.05, "$hz Hz")
        }
    }

    /** A faster GPS keeps the quarter mile on target too (the engine is rate-agnostic). */
    @Test fun fasterGpsStaysAccurate() {
        for (hz in listOf(1.0, 10.0)) {
            val sim = Simulator.constantLaunch(gpsHz = hz)
            val r = RunAnalyzer.analyze(AxisCalibration.forwardAccel(sim.motion), sim.gps)!!
            val rollout = sqrt(2 * DragDistance.ROLLOUT / 5)
            assertEquals(sqrt(2 * DragDistance.QUARTER_MILE / 5) - rollout, r.quarterMile!!, 0.15, "$hz Hz")
        }
    }
}
