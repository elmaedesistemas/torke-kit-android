package today.torke.kit.analysis

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.TracePoint
import today.torke.kit.engine.Vec3
import today.torke.kit.format.TorkeRun

/**
 * How much a run can be trusted: the GPS, the strip's slope and the mount.
 *
 * It reads the raw samples, so it works on any [TorkeRun]. A run graded [Grade.poor] should
 * not go on a leaderboard; [Grade.fair] is fine for practice.
 */
@Serializable
public data class RunQuality(
    val grade: Grade,
    val issues: List<Issue>,
    /** GPS fixes per second during the run. */
    val gpsRate: Double,
    /** Median GPS speed accuracy, m/s. */
    val speedAccuracy: Double? = null,
    /** Longest stretch without a GPS fix, seconds. */
    val longestGpsGap: Double,
    /** Slope of the strip, rise over run (positive is uphill). Null without altitudes. */
    val slope: Double? = null,
    /** How much the phone's angle changed, degrees. */
    val tiltChange: Double? = null,
    /** RMS difference between the filtered speed and the GPS speed, m/s. */
    val disagreement: Double? = null,
) {
    @Serializable
    public enum class Grade { good, fair, poor }

    @Serializable
    public enum class Issue {
        /** The GPS gave fewer fixes than one per second. */
        slowGPS,
        /** The GPS speed accuracy was poor (open sky helps). */
        inaccurateGPS,
        /** The GPS went silent for a while during the run. */
        gpsDropout,
        /** The strip goes up or down more than the limit. */
        slope,
        /** The accelerometer ended with a large bias: the mount is loose or tilted a lot. */
        looseMount,
        /** The phone changed its angle during the run. */
        phoneMoved,
        /** The accelerometer and the GPS tell different speeds. */
        sensorsDisagree,
    }

    /** Limits for each issue. [maxSlope] is rise over run: 0.01 is 1 %. */
    @Serializable
    public data class Limits(
        val minGpsRate: Double = 0.8,
        val maxSpeedAccuracy: Double = 1.0,
        val maxGpsGap: Double = 2.5,
        val maxSlope: Double = 0.01,
        val maxBias: Double = 0.5,
        val maxTiltChange: Double = 8.0,
        val maxDisagreement: Double = 1.5,
    )

    public companion object {
        /** Grades a run. Null when it has no standing-start result to grade. A stored result without its trace is computed again from the samples. */
        public fun assess(run: TorkeRun, limits: Limits = Limits()): RunQuality? {
            val stored = run.result?.takeIf { it.trace.isNotEmpty() }
            val result = stored ?: run.analyze() ?: return null
            val end = result.trace.lastOrNull() ?: return null
            val start = result.launchTime
            val finish = start + end.t
            val fixes = run.gps.filter { it.hasSpeed && it.t >= start && it.t <= finish }.sortedBy { it.t }

            val duration = maxOf(end.t, 0.001)
            val rate = fixes.size / duration
            val accuracy = median(fixes.map { it.speedAccuracy })
            val times = listOf(start) + fixes.map { it.t } + listOf(finish)
            val gap = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: duration

            // Slope: least squares of altitude against distance
            val withAltitude = fixes.mapNotNull { fix ->
                val altitude = fix.altitude ?: return@mapNotNull null
                val d = interpolate(fix.t - start, result.trace) { it.distance } ?: return@mapNotNull null
                d to altitude
            }
            var slope: Double? = null
            if (withAltitude.size >= 3 && (withAltitude.maxOf { it.first }) > 50) {
                val n = withAltitude.size.toDouble()
                val mx = withAltitude.sumOf { it.first } / n
                val my = withAltitude.sumOf { it.second } / n
                val sxx = withAltitude.sumOf { (it.first - mx) * (it.first - mx) }
                val sxy = withAltitude.sumOf { (it.first - mx) * (it.second - my) }
                if (sxx > 0) slope = sxy / sxx
            }

            // Tilt: gravity direction over the first and last half second
            val inRun = run.motion.filter { it.t >= start && it.t <= finish }
            var tilt: Double? = null
            if (inRun.size >= 2 && inRun.last().t - inRun.first().t > 1.5) {
                val first = inRun.first().t
                val last = inRun.last().t
                val a = meanDirection(inRun.filter { it.t < first + 0.5 })
                val b = meanDirection(inRun.filter { it.t > last - 0.5 })
                tilt = Math.toDegrees(acos((a dot b).coerceIn(-1.0, 1.0)))
            }

            // Sensors against GPS
            val residuals = fixes.mapNotNull { fix -> interpolate(fix.t - start, result.trace) { it.speed }?.let { it - fix.speed } }
            val disagreement = if (residuals.isEmpty()) null else sqrt(residuals.sumOf { it * it } / residuals.size)

            val issues = buildList {
                if (rate < limits.minGpsRate) add(Issue.slowGPS)
                if (accuracy != null && accuracy > limits.maxSpeedAccuracy) add(Issue.inaccurateGPS)
                if (gap > limits.maxGpsGap) add(Issue.gpsDropout)
                if (slope != null && abs(slope) > limits.maxSlope) add(Issue.slope)
                if (abs(result.accelBias) > limits.maxBias) add(Issue.looseMount)
                if (tilt != null && tilt > limits.maxTiltChange) add(Issue.phoneMoved)
                if (disagreement != null && disagreement > limits.maxDisagreement) add(Issue.sensorsDisagree)
            }
            val serious = setOf(Issue.gpsDropout, Issue.phoneMoved, Issue.sensorsDisagree)
            val grade = when {
                issues.any { it in serious } || issues.size >= 3 -> Grade.poor
                issues.isEmpty() -> Grade.good
                else -> Grade.fair
            }
            return RunQuality(grade, issues, rate, accuracy, gap, slope, tilt, disagreement)
        }

        internal fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        private fun meanDirection(samples: List<RawMotionSample>): Vec3 =
            samples.fold(Vec3.ZERO) { acc, s -> acc + s.gravity.normalized() }.normalized()

        private fun interpolate(t: Double, trace: List<TracePoint>, key: (TracePoint) -> Double): Double? {
            val i = trace.indexOfFirst { it.t >= t }
            if (i < 0) return null
            if (i == 0) return key(trace[0])
            val a = trace[i - 1]
            val b = trace[i]
            val f = if (b.t == a.t) 0.0 else (t - a.t) / (b.t - a.t)
            return key(a) + (key(b) - key(a)) * f
        }
    }
}
