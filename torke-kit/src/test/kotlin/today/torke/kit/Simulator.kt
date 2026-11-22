package today.torke.kit

import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.Noise
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.Vec3

/** Raw-sensor runs with exact physics, the same as the iOS tests' simulator. */
object Simulator {
    data class Run(val motion: List<RawMotionSample>, val gps: List<GPSSample>)

    fun run(
        speed0: Double, duration: Double, gpsHz: Double = 1.0, noise: Double = 0.05, seed: Long = 1,
        altitudeSlope: Double? = null, tiltAfter: Double? = null, accel: (Double, Double) -> Double,
    ): Run {
        val rng = Noise(seed.toULong())
        val forward = Vec3(0.0, -0.94, -0.34)
        val gravity = Vec3(0.0, 0.34, -0.94)
        val tilted = Vec3(0.3, 0.30, -0.90)
        val motion = mutableListOf<RawMotionSample>()
        val gps = mutableListOf<GPSSample>()
        val dt = 0.001
        var t = 0.0; var v = speed0; var d = 0.0; var nextImu = 0.0; var nextGps = 0.0
        while (t < duration) {
            val a = if (v <= 0 && accel(t, v) < 0) 0.0 else accel(t, v)
            if (t >= nextImu) {
                val g = if (tiltAfter != null && t > tiltAfter) tilted else gravity
                motion.add(RawMotionSample(t, forward * a + Vec3(rng.gaussian(), rng.gaussian(), rng.gaussian()) * noise, g))
                nextImu += 0.01
            }
            if (t >= nextGps) {
                gps.add(GPSSample(t, maxOf(0.0, v + 0.2 * rng.gaussian()), 0.3, 9.9, -84.2, 3.0, altitudeSlope?.let { 1000 + it * d }))
                nextGps += 1 / gpsHz
            }
            v = maxOf(0.0, v + a * dt)
            d += v * dt
            t += dt
        }
        return Run(motion, gps)
    }

    fun constantLaunch(duration: Double = 25.0, gpsHz: Double = 1.0, altitudeSlope: Double? = null, tiltAfter: Double? = null) =
        run(0.0, duration, gpsHz, altitudeSlope = altitudeSlope, tiltAfter = tiltAfter) { t, _ -> if (t < 2) 0.0 else 5.0 }

    fun rolling() = run(50 / 3.6, 12.0, 5.0) { t, _ -> if (t < 1) 0.0 else 3.0 }

    fun braking() = run(110 / 3.6, 9.0, 5.0) { t, _ -> if (t < 3) 0.0 else -8.0 }
}
