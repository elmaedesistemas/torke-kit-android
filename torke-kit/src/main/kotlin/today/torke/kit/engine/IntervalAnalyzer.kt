package today.torke.kit.engine

import kotlinx.serialization.Serializable

/** A speed interval measured without a standstill: a rolling start (60–130 km/h) or a braking (100–0 km/h). */
@Serializable
public data class IntervalResult(
    val kind: Kind,
    val fromKmh: Double,
    val toKmh: Double,
    /** Seconds from [fromKmh] to [toKmh]. */
    val time: Double,
    /** Meters covered from [fromKmh] to [toKmh]. */
    val distance: Double,
    /** Highest acceleration (rolling) or deceleration (braking) in the interval, in g. */
    val peakG: Double,
    /** When the interval started, on the samples' clock. */
    val startTime: Double,
) {
    @Serializable
    public enum class Kind { rolling, braking }
}

/**
 * Measures speed intervals while the car is already moving.
 *
 * The speed comes from the same filter as [RunAnalyzer], started at the first GPS speed.
 * "Forward" is learned from the strongest push and its sign is checked against the GPS, so a
 * braking (where the first strong push is backwards) works too.
 */
public object IntervalAnalyzer {
    /** 60–130 km/h, a common rolling test. */
    public fun rolling(motion: List<RawMotionSample>, gps: List<GPSSample>, fromKmh: Double = 60.0, toKmh: Double = 130.0): IntervalResult? {
        if (toKmh <= fromKmh) return null
        val points = trace(motion, gps) ?: return null
        val a = crossing(points, fromKmh, rising = true, start = 0) ?: return null
        val b = crossing(points, toKmh, rising = true, start = a.index) ?: return null
        return result(IntervalResult.Kind.rolling, points, a, b, fromKmh, toKmh, sign = 1.0)
    }

    /** 100–0 km/h braking. The car must come to a stop. */
    public fun braking(motion: List<RawMotionSample>, gps: List<GPSSample>, fromKmh: Double = 100.0): IntervalResult? {
        val points = trace(motion, gps) ?: return null
        val a = crossing(points, fromKmh, rising = false, start = 0) ?: return null
        val b = crossing(points, 1.8, rising = false, start = a.index) ?: return null
        return result(IntervalResult.Kind.braking, points, a, b, fromKmh, 0.0, sign = -1.0)
    }

    internal class Point(val t: Double, val speed: Double, val distance: Double, val accel: Double)
    internal class Crossing(val index: Int, val t: Double, val distance: Double)

    internal fun trace(raw: List<RawMotionSample>, gps: List<GPSSample>): List<Point>? {
        val fixes = gps.filter { it.hasSpeed }.sortedBy { it.t }
        val first = fixes.firstOrNull() ?: return null
        val motion = AxisCalibration.forwardAccel(raw, fixes).filter { it.t >= first.t }
        if (motion.size <= 10) return null

        val filter = SpeedFilter(speed = first.speed, bias = 0.0)
        val queue = ArrayDeque(fixes.drop(1))
        val points = ArrayList<Point>(motion.size)
        var distance = 0.0
        var smooth = 0.0
        var lastT = motion[0].t
        var lastSpeed = first.speed
        for (sample in motion) {
            filter.predict(sample)
            while (queue.isNotEmpty() && queue.first().t <= sample.t) filter.correct(queue.removeFirst())
            val dt = sample.t - lastT
            val speed = maxOf(filter.speed, 0.0)
            distance += (speed + lastSpeed) / 2 * dt
            if (dt > 0 && dt < 0.5) smooth += (sample.accel - filter.bias - smooth) * minOf(1.0, dt / 0.1)
            points.add(Point(sample.t, speed, distance, smooth))
            lastT = sample.t
            lastSpeed = speed
        }
        return points
    }

    internal fun crossing(points: List<Point>, kmh: Double, rising: Boolean, start: Int): Crossing? {
        val v = kmh / 3.6
        if (points.size <= start + 1) return null
        for (i in start + 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val crossed = if (rising) a.speed < v && b.speed >= v else a.speed > v && b.speed <= v
            if (crossed) {
                val f = if (b.speed == a.speed) 0.0 else (v - a.speed) / (b.speed - a.speed)
                return Crossing(i, a.t + (b.t - a.t) * f, a.distance + (b.distance - a.distance) * f)
            }
        }
        return null
    }

    private fun result(kind: IntervalResult.Kind, points: List<Point>, a: Crossing, b: Crossing, from: Double, to: Double, sign: Double): IntervalResult {
        val peak = (a.index..b.index).maxOf { points[it].accel * sign }
        return IntervalResult(kind, from, to, b.t - a.t, b.distance - a.distance, peak / DragDistance.G, a.t)
    }
}
