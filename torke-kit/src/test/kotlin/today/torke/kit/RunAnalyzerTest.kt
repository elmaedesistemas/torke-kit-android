package today.torke.kit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import today.torke.kit.engine.*

/** Synthetic runs with known physics: what is measured is compared with the exact time of the simulation. */
class RunAnalyzerTest {
    data class Truth(val quarter: Double?, val sixtyFeet: Double?, val zeroTo100: Double?, val launch: Double)
    data class Sim(val motion: List<MotionSample>, val gps: List<GPSSample>, val truth: Truth)

    private fun simulate(
        still: Double = 2.0,
        duration: Double = 25.0,
        imuHz: Double = 100.0,
        gpsHz: Double = 1.0,
        bias: Double = 0.0,
        imuNoise: Double = 0.0,
        gpsNoise: Double = 0.0,
        seed: Long = 7,
        accel: (Double) -> Double,
    ): Sim {
        val noise = Noise(seed.toULong())
        val motion = mutableListOf<MotionSample>()
        val gps = mutableListOf<GPSSample>()
        val dt = 0.001
        var t = 0.0; var v = 0.0; var d = 0.0
        var nextImu = 0.0; var nextGps = 0.0
        var tRollout: Double? = null; var t60: Double? = null; var tQuarter: Double? = null; var t100: Double? = null
        while (t < still + duration) {
            val a = if (t < still) 0.0 else accel(v)
            if (t >= nextImu) {
                motion.add(MotionSample(t, a + bias + imuNoise * noise.gaussian()))
                nextImu += 1 / imuHz
            }
            if (t >= nextGps) {
                gps.add(GPSSample(t, max(0.0, v + gpsNoise * noise.gaussian()), max(gpsNoise, 0.2)))
                nextGps += 1 / gpsHz
            }
            v += a * dt
            d += v * dt
            t += dt
            if (tRollout == null && d >= DragDistance.ROLLOUT) tRollout = t
            if (t60 == null && d >= DragDistance.SIXTY_FEET) t60 = t
            if (tQuarter == null && d >= DragDistance.QUARTER_MILE) tQuarter = t
            if (t100 == null && v >= DragDistance.SPEED_100) t100 = t
        }
        val truth = Truth(
            quarter = tQuarter?.let { q -> tRollout?.let { q - it } },
            sixtyFeet = t60?.let { s -> tRollout?.let { s - it } },
            zeroTo100 = t100?.let { it - still },
            launch = still,
        )
        return Sim(motion, gps, truth)
    }

    private val streetCar: (Double) -> Double = { v -> 6.5 * (1 - v / 75) }

    @Test fun constantAccelerationMatchesPhysics() {
        val sim = simulate(gpsHz = 1.0) { 5.0 }
        val r = RunAnalyzer.analyze(sim.motion, sim.gps)!!
        assertEquals(sim.truth.quarter!!, r.quarterMile!!, absoluteTolerance = 0.03)
        assertEquals(sim.truth.sixtyFeet!!, r.sixtyFeet!!, absoluteTolerance = 0.02)
        assertEquals(sim.truth.zeroTo100!!, r.zeroTo100!!, absoluteTolerance = 0.03)
        assertEquals(sqrt(2 * 5 * DragDistance.QUARTER_MILE) * 3.6, r.quarterMileKmh!!, absoluteTolerance = 1.0)
    }

    @Test fun detectsLaunchWithinFiftyMilliseconds() {
        val sim = simulate(still = 3.0, imuNoise = 0.08, seed = 11, accel = streetCar)
        val r = RunAnalyzer.analyze(sim.motion, sim.gps)!!
        assertEquals(sim.truth.launch, r.launchTime, absoluteTolerance = 0.05)
    }

    @Test fun biasAndNoiseStayWithinTarget() {
        // Mount with 0.3 m/s² of bias, accelerometer noise and a 1 Hz phone GPS
        val sim = simulate(gpsHz = 1.0, bias = 0.3, imuNoise = 0.15, gpsNoise = 0.4, seed = 3, accel = streetCar)
        val r = RunAnalyzer.analyze(sim.motion, sim.gps)!!
        // Targets: ±0.15 s on the 1/4, ±0.10 s on 60 ft, ±0.20 s on 0–100
        assertEquals(sim.truth.quarter!!, r.quarterMile!!, absoluteTolerance = 0.15)
        assertEquals(sim.truth.sixtyFeet!!, r.sixtyFeet!!, absoluteTolerance = 0.10)
        assertEquals(sim.truth.zeroTo100!!, r.zeroTo100!!, absoluteTolerance = 0.20)
    }

    @Test fun fasterGpsImprovesTheQuarter() {
        val slow = simulate(gpsHz = 1.0, bias = 0.4, imuNoise = 0.15, gpsNoise = 0.4, seed = 5, accel = streetCar)
        val fast = simulate(gpsHz = 10.0, bias = 0.4, imuNoise = 0.15, gpsNoise = 0.4, seed = 5, accel = streetCar)
        val rSlow = RunAnalyzer.analyze(slow.motion, slow.gps)!!
        val rFast = RunAnalyzer.analyze(fast.motion, fast.gps)!!
        assertTrue(abs(rFast.quarterMile!! - fast.truth.quarter!!) <= abs(rSlow.quarterMile!! - slow.truth.quarter!!) + 0.01)
        assertEquals(fast.truth.quarter!!, rFast.quarterMile!!, absoluteTolerance = 0.08)
    }

    @Test fun shortRunHasSixtyFeetButNoQuarter() {
        val sim = simulate(duration = 4.0, accel = streetCar)
        val r = RunAnalyzer.analyze(sim.motion, sim.gps)!!
        assertNotNull(r.sixtyFeet)
        assertNull(r.quarterMile)
    }

    @Test fun noLaunchWhenTheCarNeverStops() {
        val sim = simulate(still = 0.0, duration = 10.0) { 3.0 }
        assertNull(RunAnalyzer.analyze(sim.motion, sim.gps))
    }

    @Test fun noLaunchWhenParked() {
        val motion = (0 until 500).map { MotionSample(it / 100.0, 0.02) }
        assertNull(RunAnalyzer.analyze(motion, emptyList()))
    }

    @Test fun demoRunReachesTheQuarter() {
        val (raw, gps) = DemoRun.generate(seed = 42)
        val r = RunAnalyzer.analyze(AxisCalibration.forwardAccel(raw), gps)!!
        assertNotNull(r.quarterMile)
        assertTrue(r.quarterMile!! in 12.0..20.0)
    }
}
