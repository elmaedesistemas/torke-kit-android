package today.torke.kit

import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import today.torke.kit.analysis.PowerEstimate
import today.torke.kit.analysis.RunQuality
import today.torke.kit.analysis.Weather
import today.torke.kit.analysis.corrected
import today.torke.kit.engine.AxisCalibration
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.IntervalAnalyzer
import today.torke.kit.engine.IntervalResult
import today.torke.kit.engine.RunAnalyzer
import today.torke.kit.engine.RunResult
import today.torke.kit.engine.halfMile
import today.torke.kit.engine.speedKmh
import today.torke.kit.engine.thousandFeet
import today.torke.kit.engine.time
import today.torke.kit.engine.zeroTo60mph
import today.torke.kit.format.TorkeRun
import today.torke.kit.input.RecordedRunProvider
import today.torke.kit.session.RunEvent
import today.torke.kit.session.RunMode
import today.torke.kit.session.RunPhase
import today.torke.kit.session.RunSession
import today.torke.kit.suspension.FourLinkHoles
import today.torke.kit.suspension.LengthUnit
import today.torke.kit.suspension.Point2
import today.torke.kit.suspension.SetupChange
import today.torke.kit.suspension.SuspensionGeometry
import today.torke.kit.suspension.SuspensionSetup
import today.torke.kit.suspension.TargetWindow
import today.torke.kit.suspension.ValueRange

class PhaseTwoTest {
    private fun analyze(sim: Simulator.Run, stop: Double = DragDistance.QUARTER_MILE + 20) =
        RunAnalyzer.analyze(AxisCalibration.forwardAccel(sim.motion), sim.gps, stop)!!

    @Test fun extraMarksMatchPhysics() {
        val sim = Simulator.constantLaunch(duration = 30.0)
        val r = analyze(sim, 850.0)
        val rollout = sqrt(2 * DragDistance.ROLLOUT / 5)
        assertEquals(sqrt(2 * DragDistance.THOUSAND_FEET / 5) - rollout, r.thousandFeet!!, absoluteTolerance = 0.05)
        assertEquals(sqrt(2 * DragDistance.HALF_MILE / 5) - rollout, r.halfMile!!, absoluteTolerance = 0.08)
        assertEquals(DragDistance.SPEED_60_MPH / 5, r.zeroTo60mph!!, absoluteTolerance = 0.05)
        assertEquals(100 / 3.6 / 5, r.time(100.0, 200.0)!!, absoluteTolerance = 0.08)
        assertEquals(sqrt(2 * 5 * 100.0) * 3.6, r.speedKmh(100.0)!!, absoluteTolerance = 1.5)
        assertNull(analyze(sim).halfMile)
    }

    @Test fun gForces() {
        val r = analyze(Simulator.constantLaunch())
        assertEquals(5 / DragDistance.G, r.launchG!!, absoluteTolerance = 0.04)
        assertEquals(5 / DragDistance.G, r.peakG!!, absoluteTolerance = 0.04)
    }

    @Test fun rollingAndBraking() {
        val rolling = Simulator.rolling()
        val r = IntervalAnalyzer.rolling(rolling.motion, rolling.gps)!!
        assertEquals(IntervalResult.Kind.rolling, r.kind)
        assertEquals(70 / 3.6 / 3, r.time, absoluteTolerance = 0.15)
        assertEquals(3 / DragDistance.G, r.peakG, absoluteTolerance = 0.05)
        assertNull(IntervalAnalyzer.rolling(rolling.motion, rolling.gps, 60.0, 250.0))

        val braking = Simulator.braking()
        val b = IntervalAnalyzer.braking(braking.motion, braking.gps)!!
        assertEquals(100 / 3.6 / 8, b.time, absoluteTolerance = 0.15)
        assertEquals((100 / 3.6).pow(2) / 16, b.distance, absoluteTolerance = 2.5)
        assertEquals(8 / DragDistance.G, b.peakG, absoluteTolerance = 0.08)
    }

    @Test fun sessionModes() {
        val session = RunSession()
        val rolling = Simulator.rolling()
        session.arm(RunMode.Rolling(60.0, 130.0))
        val events = mutableListOf<RunEvent>()
        for (s in RecordedRunProvider.merge(TorkeRun(motion = rolling.motion, gps = rolling.gps))) events += session.process(s)
        assertEquals(RunPhase.Finished, session.snapshot.value.phase)
        assertEquals(70 / 3.6 / 3, session.snapshot.value.interval!!.time, absoluteTolerance = 0.2)
        assertTrue(events.any { it is RunEvent.IntervalFinished })

        val braking = Simulator.braking()
        session.arm(RunMode.Braking(100.0))
        for (s in RecordedRunProvider.merge(TorkeRun(motion = braking.motion, gps = braking.gps))) session.process(s)
        assertEquals(RunPhase.Finished, session.snapshot.value.phase)
        assertEquals(100 / 3.6 / 8, session.snapshot.value.interval!!.time, absoluteTolerance = 0.2)
        assertEquals(session.snapshot.value.interval, session.recording().interval)
    }

    @Test fun weather() {
        val standard = Weather(25.0, 0.0, 990.0)
        assertEquals(1.0, standard.correctionFactor, absoluteTolerance = 1e-9)
        assertEquals(0.0, Weather(15.0, 0.0, 1013.25).densityAltitude, absoluteTolerance = 15.0)
        val hot = Weather(31.0, 70.0, 910.0)
        assertTrue(hot.densityAltitude in 1600.0..2100.0)
        val r = RunResult(0.0, 6.0, 2.0, 9.0, 130.0, 14.0, 160.0, 165.0, 0.0)
        val c = r.corrected(hot)
        assertTrue(c.quarterMile!! < 14 && c.quarterMileKmh!! > 160)
    }

    @Test fun power() {
        assertEquals(3306.93 * (99.419 / 234).pow(3), PowerEstimate.fromTrapSpeed(1500.0, 160.0), absoluteTolerance = 1.0)
        assertEquals(3306.93 * (5.825 / 14).pow(3), PowerEstimate.fromElapsedTime(1500.0, 14.0), absoluteTolerance = 1.0)
        val r = analyze(Simulator.constantLaunch())
        val curve = PowerEstimate.wheelPower(r, 1000.0, 0.0, 0.0).filter { it.speedKmh > 40 && it.speedKmh < 160 }
        val force = curve.sumOf { it.watts / (it.speedKmh / 3.6) } / curve.size
        assertEquals(5000.0, force, absoluteTolerance = 250.0)
    }

    @Test fun quality() {
        val clean = TorkeRun(motion = Simulator.constantLaunch(gpsHz = 5.0, altitudeSlope = 0.0).motion, gps = Simulator.constantLaunch(gpsHz = 5.0, altitudeSlope = 0.0).gps).quality()!!
        assertEquals(RunQuality.Grade.good, clean.grade, "${clean.issues}")

        val slopeSim = Simulator.constantLaunch(gpsHz = 5.0, altitudeSlope = 0.03)
        val slope = TorkeRun(motion = slopeSim.motion, gps = slopeSim.gps).quality()!!
        assertEquals(0.03, slope.slope!!, absoluteTolerance = 0.005)
        assertTrue(RunQuality.Issue.slope in slope.issues)
        assertEquals(RunQuality.Grade.fair, slope.grade)

        val tiltSim = Simulator.constantLaunch(gpsHz = 5.0, tiltAfter = 6.0)
        val tilt = TorkeRun(motion = tiltSim.motion, gps = tiltSim.gps).quality()!!
        assertTrue(RunQuality.Issue.phoneMoved in tilt.issues)
        assertEquals(RunQuality.Grade.poor, tilt.grade)

        val dropSim = Simulator.constantLaunch(gpsHz = 5.0)
        val drop = TorkeRun(motion = dropSim.motion, gps = dropSim.gps.filterNot { it.t > 5 && it.t < 9 }).quality()!!
        assertTrue(RunQuality.Issue.gpsDropout in drop.issues)
    }

    private val fourLink = SuspensionSetup.fourLink(
        100.0, 20.0, upperRear = Point2(0.0, 20.0), upperFront = Point2(20.0, 22.0), lowerRear = Point2(0.0, 10.0), lowerFront = Point2(20.0, 14.0),
    )

    @Test fun suspensionGeometry() {
        val g = fourLink.geometry()
        assertEquals(100.0, g.icLength!!, absoluteTolerance = 1e-9)
        assertEquals(30.0, g.icHeight!!, absoluteTolerance = 1e-9)
        assertEquals(150.0, g.antiSquat!!, absoluteTolerance = 1e-9)
        assertEquals(Math.toDegrees(atan(0.1)), g.bars.first { it.name == "upper" }.angle, absoluteTolerance = 1e-9)

        val parallel = fourLink.copy(points = fourLink.points + ("lowerFront" to Point2(20.0, 12.0))).geometry()
        assertNull(parallel.instantCenter)
        assertEquals(listOf(SuspensionGeometry.Warning.parallelLinks), parallel.warnings)

        val ladder = SuspensionSetup.ladderBar(108.0, 18.0, Point2(54.0, 9.0))
        assertEquals((9.0 / 54) / (18.0 / 108) * 100, ladder.geometry().antiSquat!!, absoluteTolerance = 1e-9)
        val arm = SuspensionSetup.torqueArm(100.0, 20.0, Point2(50.0, 25.0), Point2(0.0, 10.0), Point2(20.0, 14.0)).geometry()
        assertEquals(50.0, arm.icLength!!, absoluteTolerance = 1e-9)
        assertEquals(20.0, arm.icHeight!!, absoluteTolerance = 1e-9)
        assertEquals(200.0, arm.antiSquat!!, absoluteTolerance = 1e-9)

        val behind = SuspensionSetup.fourLink(100.0, 20.0, Point2(0.0, 20.0), Point2(20.0, 24.0), Point2(0.0, 10.0), Point2(20.0, 12.0)).geometry()
        assertTrue(SuspensionGeometry.Warning.instantCenterBehindAxle in behind.warnings)
        assertNull(behind.antiSquat)

        val cm = fourLink.converted(LengthUnit.centimeters)
        assertEquals(254.0, cm.geometry().icLength!!, absoluteTolerance = 1e-9)
        assertEquals(150.0, cm.geometry().antiSquat!!, absoluteTolerance = 1e-9)
    }

    @Test fun targetsHolesAndChanges() {
        val target = TargetWindow(ValueRange(60.0, 70.0), ValueRange(10.0, 14.0), ValueRange(90.0, 120.0))
        val g = fourLink.geometry()
        assertEquals(TargetWindow.Status.above, target.status(TargetWindow.Metric.icLength, g))
        val holes = FourLinkHoles(
            listOf(Point2(0.0, 20.0), Point2(0.0, 18.0)), listOf(Point2(30.0, 20.0), Point2(30.0, 22.0)),
            listOf(Point2(0.0, 8.0), Point2(0.0, 10.0)), listOf(Point2(30.0, 10.0), Point2(30.0, 12.0)),
        )
        val ranked = holes.ranked(fourLink, target)
        assertTrue(ranked.isNotEmpty())
        assertEquals(ranked.sortedBy { it.score }, ranked)
        assertTrue(ranked.first().score <= target.score(g)!!)

        val after = fourLink.copy(points = fourLink.points + ("upperFront" to Point2(20.0, 23.0)))
        val change = SetupChange.between(fourLink, after)
        assertEquals(mapOf("upperFront" to Point2(0.0, 1.0)), change.moved)
        assertNotNull(change.antiSquat)
    }

    /** The numbers TorkeKit for iOS wrote: Android must get the same ones. */
    @Test fun phaseTwoFixtureFromIos() {
        fun text(name: String) = javaClass.getResource("/$name")!!.readText()
        val expected = Json.parseToJsonElement(text("phase2-expected.json")).jsonObject
        fun value(key: String) = expected.getValue(key).jsonPrimitive.double

        val launch = TorkeRun.decode(text("launch.torkerun.json"))
        val weather = launch.weather!!
        val setup = launch.suspension!!
        val geometry = setup.geometry()
        assertEquals(value("densityAltitude"), weather.densityAltitude, absoluteTolerance = 1e-6)
        assertEquals(value("correctionFactor"), weather.correctionFactor, absoluteTolerance = 1e-9)
        assertEquals(value("airDensity"), weather.airDensity, absoluteTolerance = 1e-9)
        assertEquals(value("icLength"), geometry.icLength!!, absoluteTolerance = 1e-9)
        assertEquals(value("icHeight"), geometry.icHeight!!, absoluteTolerance = 1e-9)
        assertEquals(value("antiSquat"), geometry.antiSquat!!, absoluteTolerance = 1e-9)
        assertEquals(value("correctedQuarterMile"), launch.analyze()!!.corrected(weather).quarterMile!!, absoluteTolerance = 0.0005)
        assertEquals(launch.result!!.launchG!!, launch.analyze()!!.launchG!!, absoluteTolerance = 1e-6)

        val quality = launch.quality()!!
        assertEquals(expected.getValue("qualityGrade").jsonPrimitive.content, quality.grade.name)
        assertEquals(value("slope"), quality.slope!!, absoluteTolerance = 1e-6)

        val full = TorkeRun(motion = launch.motion, gps = launch.gps).analyze()!!
        assertEquals(value("launchG"), full.launchG!!, absoluteTolerance = 1e-6)
        assertEquals(value("thousandFeet"), full.thousandFeet!!, absoluteTolerance = 0.0005)
        assertEquals(value("peakWheelHp"), PowerEstimate.peakWheelHp(full, 1200.0)!!, absoluteTolerance = 0.05)
        assertEquals(value("hpTrap"), PowerEstimate.fromTrapSpeed(1500.0, 160.0), absoluteTolerance = 1e-6)

        val target = TargetWindow(ValueRange(60.0, 75.0), ValueRange(8.0, 12.0), ValueRange(80.0, 110.0))
        val holes = FourLinkHoles(
            listOf(Point2(4.0, 21.0), Point2(4.0, 19.0), Point2(4.0, 17.0)),
            listOf(Point2(30.0, 23.0), Point2(30.0, 21.0), Point2(30.0, 19.0)),
            listOf(Point2(2.0, 9.0), Point2(2.0, 7.0)),
            listOf(Point2(32.0, 12.0), Point2(32.0, 10.0), Point2(32.0, 8.0)),
        )
        val best = holes.ranked(setup, target).first()
        assertEquals(value("bestScore"), best.score, absoluteTolerance = 1e-9)
        assertEquals(expected.getValue("bestHoles").jsonArray.map { it.jsonPrimitive.int }, best.holes)

        val braking = TorkeRun.decode(text("braking.torkerun.json"))
        assertEquals(braking.interval!!.time, braking.braking()!!.time, absoluteTolerance = 0.0005)
        assertEquals(braking.interval!!.distance, braking.braking()!!.distance, absoluteTolerance = 0.01)
        val rolling = TorkeRun.decode(text("rolling.torkerun.json"))
        assertEquals(value("rollingTime"), rolling.rolling()!!.time, absoluteTolerance = 0.0005)
    }

    /** The simulator gives the same samples as on iOS, so the physics tests compare like for like. */
    @Test fun simulatorMatchesIos() {
        val ios = TorkeRun.decode(javaClass.getResource("/rolling.torkerun.json")!!.readText())
        val mine = Simulator.rolling()
        assertEquals(ios.motion.size, mine.motion.size)
        assertEquals(ios.gps.last().speed, mine.gps.last().speed, absoluteTolerance = 1e-9)
    }
}
