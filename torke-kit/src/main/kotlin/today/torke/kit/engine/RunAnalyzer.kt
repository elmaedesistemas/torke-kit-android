package today.torke.kit.engine

import kotlin.math.max
import kotlin.math.sqrt

/** Computes the official times of a recorded run from all its samples. */
public object RunAnalyzer {
    /** Acceleration (m/s²) that marks the launch if held for [LAUNCH_HOLD] seconds. */
    private const val LAUNCH_ACCEL = 1.5
    private const val LAUNCH_HOLD = 0.15
    /** Below this the car is considered not moving yet. */
    private const val REST_ACCEL = 0.35
    private const val REST_SPREAD = 0.3
    private const val STILL_WINDOW = 1.0

    /** Analyzes a recorded run. Returns null when there is no launch from a standstill. */
    public fun analyze(
        motion: List<MotionSample>,
        gps: List<GPSSample>,
        /** The trace stops this far past the launch: raise it to read marks past the 1/4, such as [DragDistance.HALF_MILE]. */
        stopDistance: Double = DragDistance.QUARTER_MILE + 20,
    ): RunResult? {
        val sorted = motion.sortedBy { it.t }
        val (launchIndex, bias) = findLaunch(sorted) ?: return null
        val launchTime = sorted[launchIndex].t

        val filter = SpeedFilter(speed = 0.0, bias = bias)
        val gpsQueue = ArrayDeque(gps.filter { it.t > launchTime && it.hasSpeed }.sortedBy { it.t })
        val trace = mutableListOf(TracePoint(0.0, 0.0, 0.0))
        var distance = 0.0
        var lastSpeed = 0.0
        var lastT = launchTime
        filter.predict(sorted[launchIndex])
        val g = GForce()

        for (i in launchIndex + 1 until sorted.size) {
            val sample = sorted[i]
            filter.predict(sample)
            while (gpsQueue.isNotEmpty() && gpsQueue.first().t <= sample.t) filter.correct(gpsQueue.removeFirst())
            val speed = max(filter.speed, 0.0)
            distance += (speed + lastSpeed) / 2 * (sample.t - lastT)
            g.add(sample.accel - filter.bias, sample.t - lastT, sample.t - launchTime)
            lastSpeed = speed
            lastT = sample.t
            trace.add(TracePoint(sample.t - launchTime, speed, distance))
            if (distance > stopDistance) break
        }

        val rolloutT = timeAtDistance(DragDistance.ROLLOUT, trace)
        fun elapsed(d: Double): Double? {
            val t = timeAtDistance(d, trace) ?: return null
            return rolloutT?.let { t - it }
        }
        return RunResult(
            launchTime = launchTime,
            zeroTo100 = timeAtSpeed(DragDistance.SPEED_100, trace),
            sixtyFeet = elapsed(DragDistance.SIXTY_FEET),
            eighthMile = elapsed(DragDistance.EIGHTH_MILE),
            eighthMileKmh = speedAtDistance(DragDistance.EIGHTH_MILE, trace)?.let { it * 3.6 },
            quarterMile = elapsed(DragDistance.QUARTER_MILE),
            quarterMileKmh = speedAtDistance(DragDistance.QUARTER_MILE, trace)?.let { it * 3.6 },
            topSpeedKmh = (trace.maxOfOrNull { it.speed } ?: 0.0) * 3.6,
            accelBias = filter.bias,
            trace = trace,
            launchG = g.launch,
            peakG = g.peak,
        )
    }

    /** Forward acceleration averaged over 0.1 s, in g: the peak in the first second and overall. */
    internal class GForce {
        private var smooth = 0.0
        var launch: Double? = null
        var peak: Double? = null

        fun add(accel: Double, dt: Double, elapsed: Double) {
            if (dt <= 0 || dt >= 0.5) return
            smooth += (accel - smooth) * minOf(1.0, dt / 0.1)
            val g = smooth / DragDistance.G
            if (elapsed <= 1) launch = maxOf(launch ?: 0.0, g)
            peak = maxOf(peak ?: 0.0, g)
        }
    }

    /**
     * Finds the launch: one second still, then sustained acceleration.
     * Returns the index of the first movement and the bias measured at rest.
     */
    internal fun findLaunch(motion: List<MotionSample>): Pair<Int, Double>? {
        if (motion.size <= 10) return null
        // The rest window ends `rampGap` before the evaluated point, so the rise of the
        // acceleration at launch does not count as earlier movement.
        val rampGap = 0.3
        var lo = 0
        var hi = 0
        for (i in motion.indices) {
            val t = motion[i].t
            while (lo < i && motion[lo].t < t - rampGap - STILL_WINDOW) lo++
            while (hi < i && motion[hi].t < t - rampGap) hi++
            val end = max(lo, hi)
            if (end <= lo) continue
            val first = motion[lo]
            val last = motion[end - 1]
            if (last.t - first.t < STILL_WINDOW * 0.9) continue
            val n = (end - lo).toDouble()
            var sum = 0.0
            for (k in lo until end) sum += motion[k].accel
            val bias = sum / n
            var sq = 0.0
            for (k in lo until end) { val d = motion[k].accel - bias; sq += d * d }
            val spread = sqrt(sq / n)
            // Still = little spread (an idling engine vibrates, but does not accelerate)
            if (spread >= REST_SPREAD || motion[i].accel - bias <= LAUNCH_ACCEL) continue
            val moving = max(REST_ACCEL, 3 * spread)
            // Is it held?
            var j = i
            var held = true
            while (j < motion.size && motion[j].t - t < LAUNCH_HOLD) {
                if (motion[j].accel - bias < LAUNCH_ACCEL * 0.6) { held = false; break }
                j++
            }
            if (!held || j >= motion.size) continue
            // Walk back to the first movement
            var start = i
            while (start > 0 && motion[start - 1].accel - bias > moving && motion[start - 1].t > last.t) start--
            return start to bias
        }
        return null
    }

    internal fun timeAtDistance(d: Double, trace: List<TracePoint>) =
        crossing(trace, d) { it.distance }?.let { (a, b, f) -> lerp(trace[a].t, trace[b].t, f) }

    internal fun speedAtDistance(d: Double, trace: List<TracePoint>) =
        crossing(trace, d) { it.distance }?.let { (a, b, f) -> lerp(trace[a].speed, trace[b].speed, f) }

    internal fun timeAtSpeed(v: Double, trace: List<TracePoint>) =
        crossing(trace, v) { it.speed }?.let { (a, b, f) -> lerp(trace[a].t, trace[b].t, f) }

    /** First pair of points where [key] crosses [value], with the fraction between them. */
    private fun crossing(trace: List<TracePoint>, value: Double, key: (TracePoint) -> Double): Triple<Int, Int, Double>? {
        if (trace.size < 2) return null
        for (i in 1 until trace.size) {
            val a = key(trace[i - 1])
            val b = key(trace[i])
            if (a < value && b >= value) return Triple(i - 1, i, if (b == a) 0.0 else (value - a) / (b - a))
        }
        return null
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
}
