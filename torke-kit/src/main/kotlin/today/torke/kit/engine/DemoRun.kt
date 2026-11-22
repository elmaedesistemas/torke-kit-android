package today.torke.kit.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Deterministic noise (the same LCG as TorkeKit for iOS). */
public class Noise(private var state: ULong) {
    public fun uniform(): Double {
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return (state shr 11).toDouble() / (1uL shl 53).toDouble()
    }

    /** Standard normal (Box-Muller). */
    public fun gaussian(): Double {
        val u1 = max(uniform(), 1e-12)
        val u2 = uniform()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
}

/**
 * A simulated run to try an app without going to the track: 2 s still, then a street car
 * accelerating past the quarter mile, with the phone tilted in its mount, sensor noise and a
 * 1 Hz GPS. The same seed gives the same run on iOS and Android.
 */
public object DemoRun {
    public fun generate(seed: Long = 42): Pair<List<RawMotionSample>, List<GPSSample>> {
        val noise = Noise(seed.toULong())
        // Phone turned 90° in the mount and tilted back
        val forward = Vec3(0.0, -0.94, -0.34)
        val gravity = Vec3(0.0, -0.34, 0.94) * -1.0

        val motion = mutableListOf<RawMotionSample>()
        val gps = mutableListOf<GPSSample>()
        val dt = 0.001
        val still = 2.0
        var t = 0.0; var v = 0.0; var d = 0.0
        var nextImu = 0.0; var nextGps = 0.3
        while (t < still + 30 && d < DragDistance.QUARTER_MILE + 60) {
            val a = if (t < still) 0.0 else 6.8 * (1 - v / 72)
            if (t >= nextImu) {
                val user = forward * a + Vec3(noise.gaussian(), noise.gaussian(), noise.gaussian()) * 0.08
                motion.add(RawMotionSample(t, user, gravity))
                nextImu += 0.01
            }
            if (t >= nextGps) {
                gps.add(GPSSample(t, max(0.0, v + 0.3 * noise.gaussian()), 0.4))
                nextGps += 1
            }
            v += a * dt
            d += v * dt
            t += dt
        }
        return motion to gps
    }
}
