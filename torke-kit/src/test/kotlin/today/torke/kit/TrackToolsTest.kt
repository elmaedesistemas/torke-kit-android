package today.torke.kit

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import today.torke.kit.engine.AxisCalibration
import today.torke.kit.engine.Coordinate
import today.torke.kit.engine.DemoRun
import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.RunAnalyzer
import today.torke.kit.format.TorkeRun
import today.torke.kit.track.ChristmasTree
import today.torke.kit.track.Geo
import today.torke.kit.track.Geofence
import today.torke.kit.track.LapEvent
import today.torke.kit.track.LapTimer
import today.torke.kit.track.TimingLine
import today.torke.kit.track.TreeLight
import today.torke.kit.track.TreeType
import today.torke.kit.track.check
import today.torke.kit.track.firstContaining
import today.torke.kit.track.leftStageAt
import today.torke.kit.track.packageTime
import today.torke.kit.track.reaction

/** A round circuit: 200 m radius around a point in Costa Rica, counterclockwise. */
object Circuit {
    val center = Coordinate(9.9412, -84.2005)
    const val RADIUS = 200.0

    fun coordinate(angle: Double, r: Double = RADIUS): Coordinate {
        val x = r * cos(angle)
        val y = r * sin(angle)
        val lat = center.latitude + y / 6_371_008.8 * 180 / PI
        val lon = center.longitude + x / (6_371_008.8 * cos(center.latitude * PI / 180)) * 180 / PI
        return Coordinate(lat, lon)
    }

    fun line(angle: Double) = TimingLine(coordinate(angle, RADIUS - 20), coordinate(angle, RADIUS + 20))

    fun fixes(hz: Double, speeds: List<Double>): List<GPSSample> {
        val out = mutableListOf<GPSSample>()
        val start = -10 * PI / 180
        var t = 0.0
        var angle = start
        val end = start + speeds.size * 2 * PI + 0.3
        val dt = 1 / hz
        while (angle < end) {
            val c = coordinate(angle)
            out += GPSSample(t, 0.0, 0.3, c.latitude, c.longitude, 2.0)
            val lap = min((max(angle, 0.0) / (2 * PI)).toInt(), speeds.size - 1)
            angle += speeds[lap] / RADIUS * dt
            t += dt
        }
        return out
    }

    fun lapTime(v: Double) = 2 * PI * RADIUS / v
}

class LapTimerTest {
    private fun timer(sectors: Boolean = true) =
        LapTimer(Circuit.line(0.0), if (sectors) listOf(Circuit.line(2 * PI / 3), Circuit.line(4 * PI / 3)) else emptyList())

    @Test fun lapTimesAt10And1Hz() {
        for ((hz, tolerance) in listOf(10.0 to 0.02, 1.0 to 0.1)) {
            val lt = timer()
            val events = Circuit.fixes(hz, listOf(30.0, 30.0, 30.0)).flatMap { lt.process(it) }
            assertEquals(3, lt.laps.size, "$hz Hz")
            lt.laps.forEach { assertEquals(Circuit.lapTime(30.0), it.time, tolerance, "$hz Hz") }
            assertEquals(2 * PI * Circuit.RADIUS, lt.laps[0].distance, 2 * PI * Circuit.RADIUS * 0.01)
            assertTrue(events.first() is LapEvent.Started)
        }
    }

    @Test fun sectorsAddUpToTheLap() {
        val lt = timer()
        Circuit.fixes(10.0, listOf(30.0, 30.0)).forEach { lt.process(it) }
        val lap = lt.laps.first()
        assertEquals(3, lap.sectors.size)
        lap.sectors.forEach { assertEquals(Circuit.lapTime(30.0) / 3, it, 0.03) }
        assertEquals(lap.time, lap.sectors.sum(), 1e-9)
    }

    @Test fun deltaAndPredictedLap() {
        val lt = timer(sectors = false)
        var midLapDelta: Double? = null
        for (f in Circuit.fixes(10.0, listOf(30.0, 32.0, 32.0))) {
            lt.process(f)
            val e = lt.elapsed(f.t)
            if (lt.laps.size == 1 && e != null && e > Circuit.lapTime(32.0) / 2 && midLapDelta == null) {
                midLapDelta = lt.delta
                assertEquals(lt.laps[0].time + midLapDelta!!, lt.predictedLap!!, 1e-9)
            }
        }
        assertEquals(Circuit.lapTime(32.0) / 2 - Circuit.lapTime(30.0) / 2, midLapDelta!!, 0.15)
        assertNotEquals(1, lt.bestLap?.number)
    }

    @Test fun ignoresJitterOnTheLine() {
        val lt = timer(sectors = false)
        val a = Circuit.coordinate(-0.01)
        val b = Circuit.coordinate(0.01)
        listOf(a, b, a, b).forEachIndexed { i, c -> lt.process(GPSSample(i * 0.3, 0.0, 1.0, c.latitude, c.longitude)) }
        assertTrue(lt.laps.isEmpty())
    }
}

class ChristmasTreeTest {
    @Test fun lightSchedules() {
        val pro = ChristmasTree(TreeType.pro, 10.0)
        assertEquals(listOf(10.0, 10.0, 10.0, 10.4), pro.lights.map { it.time })
        val sportsman = ChristmasTree(TreeType.sportsman, 10.0)
        assertEquals(listOf(10.0, 10.5, 11.0, 11.5), sportsman.lights.map { it.time })
        assertEquals(listOf(TreeLight.Kind.amber1, TreeLight.Kind.amber2), sportsman.lit(10.7))
        assertEquals(listOf(TreeLight.Kind.green), sportsman.lit(11.6))
        assertTrue(sportsman.lit(9.0).isEmpty())
    }

    @Test fun reactionFromTheDemoRun() {
        val demo = DemoRun.generate()
        val r = RunAnalyzer.analyze(AxisCalibration.forwardAccel(demo.first), demo.second)!!
        val left = r.leftStageAt!!
        assertTrue(left > r.launchTime)
        assertTrue(r.reaction(ChristmasTree(TreeType.pro, left - 0.4))!!.isPerfect)
        val late = ChristmasTree(TreeType.pro, left - 0.4 - 0.237)
        assertEquals(0.237, r.reaction(late)!!.time, 1e-9)
        assertTrue(r.reaction(ChristmasTree(TreeType.sportsman, left - 1.5 + 0.02))!!.isRedLight)
        assertEquals(0.237 + r.quarterMile!!, r.packageTime(late)!!, 1e-9)
    }
}

class GeofenceTest {
    @Test fun circleAndPolygon() {
        val circle = Geofence.circle(Circuit.center, 250.0)
        assertTrue(Circuit.coordinate(1.0) in circle)
        assertFalse(Circuit.coordinate(1.0, 300.0) in circle)
        val square = Geofence.polygon(listOf(0.25, 0.75, 1.25, 1.75).map { Circuit.coordinate(it * PI, 300.0) })
        assertTrue(Circuit.center in square)
        assertFalse(Circuit.coordinate(0.0, 290.0) in square)
        assertEquals(Geofence.Kind.polygon, listOf(square, circle).firstContaining(Circuit.center)?.kind)
        assertNull(listOf(circle).firstContaining(Circuit.coordinate(0.0, 400.0)))
        assertEquals(200.0, Geo.distance(Circuit.center, Circuit.coordinate(0.3)), 0.01)
    }

    @Test fun runCheck() {
        val launch = TorkeRun.decode(javaClass.getResource("/launch.torkerun.json")!!.readText())
        val start = launch.startLocation!!
        val check = launch.check(Geofence.circle(start, 2000.0))!!
        assertTrue(check.startInside && check.allInside)
        val elsewhere = launch.check(Geofence.circle(Coordinate(start.latitude + 0.05, start.longitude), 300.0))!!
        assertFalse(elsewhere.startInside)
        assertEquals(0.0, elsewhere.shareInside)
    }
}

/** The same numbers as TorkeKit for iOS, from `phase4-expected.json`. */
class PhaseFourFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun resource(name: String) = File(javaClass.getResource("/$name")!!.toURI()).readText()

    @Test fun phaseFourFixture() {
        val expected = Json.parseToJsonElement(resource("phase4-expected.json")).jsonObject
        val start = json.decodeFromJsonElement(TimingLine.serializer(), expected["start"]!!)
        val sectors = json.decodeFromJsonElement(ListSerializer(TimingLine.serializer()), expected["sectors"]!!)
        val circuit = TorkeRun.decode(resource("circuit.torkerun.json"))
        val timer = LapTimer(start, sectors)
        circuit.gps.forEach { timer.process(it) }
        val lapTimes = expected["lapTimes"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(lapTimes.size, timer.laps.size)
        timer.laps.zip(lapTimes).forEach { (lap, t) -> assertEquals(t, lap.time, 1e-6) }
        val lapSectors = expected["lapSectors"]!!.jsonArray.map { l -> l.jsonArray.map { it.jsonPrimitive.double } }
        timer.laps.zip(lapSectors).forEach { (lap, s) -> lap.sectors.zip(s).forEach { (a, b) -> assertEquals(b, a, 1e-6) } }

        val fences = json.decodeFromJsonElement(ListSerializer(Geofence.serializer()), expected["geofences"]!!)
        val probes = json.decodeFromJsonElement(ListSerializer(Coordinate.serializer()), expected["probes"]!!)
        val contains = (expected["contains"] as JsonArray).map { row -> row.jsonArray.map { it.jsonPrimitive.boolean } }
        assertEquals(contains, fences.map { f -> probes.map { it in f } })

        assertEquals(expected["seededActivatedAt"]!!.jsonPrimitive.double, ChristmasTree.start(TreeType.pro, 5.0, seed = 42).activatedAt, 1e-12)
        val demo = TorkeRun.decode(resource("demo-42.torkerun.json")).analyze()!!
        assertEquals(expected["demoLeftStageAt"]!!.jsonPrimitive.double, demo.leftStageAt!!, 1e-6)
        assertEquals(expected["demoReactionSportsmanAt1"]!!.jsonPrimitive.double, demo.reaction(ChristmasTree(TreeType.sportsman, 1.0))!!.time, 1e-6)
        assertNotNull(timer.bestLap)
    }
}
