package today.torke.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import today.torke.kit.engine.Coordinate
import today.torke.kit.engine.DragMark
import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.Vec3
import today.torke.kit.input.RecordedRunProvider
import today.torke.kit.session.RunEvent
import today.torke.kit.session.RunFailure
import today.torke.kit.session.RunPhase
import today.torke.kit.session.RunSession

class RunSessionTest {
    private val down = Vec3(0.0, 0.0, -1.0)

    @Test fun demoRunGoesThroughEveryPhase() {
        val session = RunSession()
        val events = mutableListOf<RunEvent>()
        session.arm()
        for (sample in RecordedRunProvider.merge(RecordedRunProvider.demo().run)) {
            events += session.process(sample)
            if (session.snapshot.value.phase == RunPhase.Finished) break
        }
        // The demo may end before the session's own finish line: then the app finishes it
        if (session.snapshot.value.phase != RunPhase.Finished) assertNotNull(session.finish())

        assertEquals(RunPhase.Finished, session.snapshot.value.phase)
        assertEquals(RunEvent.Armed, events.first())
        assertTrue(events.any { it is RunEvent.Launched })
        val marks = events.filterIsInstance<RunEvent.Passed>().map { it.mark }
        assertEquals(DragMark.sixtyFeet, marks.first())
        assertTrue(DragMark.quarterMile in marks)
        val expected = RecordedRunProvider.demo().run.analyze()!!
        assertEquals(expected.quarterMile!!, session.snapshot.value.result!!.quarterMile!!, absoluteTolerance = 0.001)
    }

    @Test fun sessionMatchesTheAnalyzer() = runTest {
        val demo = RecordedRunProvider.demo(seed = 9)
        val session = RunSession()
        session.arm()
        session.run(demo)
        val expected = demo.run.analyze()!!
        val result = session.snapshot.value.result!!
        assertEquals(expected.quarterMile!!, result.quarterMile!!, absoluteTolerance = 0.001)
        assertEquals(expected.sixtyFeet!!, result.sixtyFeet!!, absoluteTolerance = 0.001)
    }

    @Test fun withoutArmingNothingIsRecorded() {
        val session = RunSession()
        for (sample in RecordedRunProvider.merge(RecordedRunProvider.demo().run)) assertEquals(emptyList(), session.process(sample))
        assertEquals(RunPhase.Idle, session.snapshot.value.phase)
        assertTrue(session.recording().motion.isEmpty())
    }

    @Test fun movingBeforeTheLaunchDisarms() {
        val session = RunSession()
        session.arm()
        val events = mutableListOf<RunEvent>()
        for (i in 0 until 150) events += session.process(RawMotionSample(i / 100.0, Vec3.ZERO, down))
        assertEquals(listOf<RunEvent>(RunEvent.Armed), events)
        // A single bump does not disarm
        events += session.process(RawMotionSample(1.5, Vec3(1.0, 0.0, 0.0), down))
        events += session.process(RawMotionSample(1.51, Vec3.ZERO, down))
        assertEquals(RunPhase.Armed, session.snapshot.value.phase)
        // Creeping forward without a launch does
        for (i in 0 until 80) events += session.process(RawMotionSample(1.6 + i / 100.0, Vec3(1.0, 0.0, 0.0), down))
        assertEquals(RunEvent.Disarmed, events.last())
        assertEquals(RunPhase.WaitingStill, session.snapshot.value.phase)
    }

    /** A launch that ramps up, like a real car, must still be caught. */
    @Test fun rampedLaunchIsDetected() {
        val session = RunSession()
        session.arm()
        val events = mutableListOf<RunEvent>()
        for (i in 0 until 400) {
            val t = i / 100.0
            val a = if (t < 2) 0.0 else minOf(6.0, (t - 2) * 20)   // 0 to 6 m/s² in 0.3 s
            events += session.process(RawMotionSample(t, Vec3(0.0, a, 0.0), down))
        }
        val launch = events.filterIsInstance<RunEvent.Launched>().firstOrNull()
        assertNotNull(launch, "$events")
        assertEquals(2.03, launch.time, absoluteTolerance = 0.05)
        assertFalse(RunEvent.Disarmed in events)
    }

    @Test fun finishingWithoutALaunchFails() {
        val session = RunSession()
        session.arm()
        assertNull(session.finish())
        assertEquals(RunPhase.Failed(RunFailure.noLaunch), session.snapshot.value.phase)
    }

    @Test fun gpsWithoutSpeedOnlyMovesTheLocation() {
        val session = RunSession()
        session.process(GPSSample(1.0, 3.0, 0.5))
        session.process(GPSSample(2.0, 0.0, GPSSample.NO_SPEED, latitude = 9.9, longitude = -84.1))
        assertEquals(3.0, session.snapshot.value.speed)
        assertEquals(Coordinate(9.9, -84.1), session.snapshot.value.location)
    }
}
