package today.torke.kit.track

import kotlin.math.abs
import kotlinx.serialization.Serializable
import today.torke.kit.engine.Coordinate
import today.torke.kit.engine.GPSSample

/**
 * A timing line across the track, from one edge to the other: the start/finish line or a
 * sector split. Make it a bit wider than the track so every lap crosses it.
 */
@Serializable
public data class TimingLine(val a: Coordinate, val b: Coordinate, val name: String? = null)

/** One completed lap. */
@Serializable
public data class Lap(
    /** 1 for the first full lap. */
    val number: Int,
    /** When it started, on the samples' clock. */
    val start: Double,
    /** Seconds. */
    val time: Double,
    /** Time of each sector, in order; the last ends at the start/finish line. Empty without sector lines, or if one was missed. */
    val sectors: List<Double>,
    /** Meters driven, from the GPS. */
    val distance: Double,
)

/** What happened on a fix. */
public sealed interface LapEvent {
    /** The first crossing of the start/finish line: timing begins. */
    public data class Started(val time: Double) : LapEvent
    /** A sector line: its index (0 for the first) and the sector's time. */
    public data class Sector(val index: Int, val time: Double) : LapEvent
    public data class Completed(val lap: Lap) : LapEvent
}

/**
 * Lap timing for road courses with the phone's GPS: start/finish line, sectors, the live delta
 * against the best lap and the predicted lap. Feed it every GPS fix with a position.
 *
 * Crossings are interpolated between fixes, so even a 1 Hz GPS times a lap to about a tenth;
 * a faster GPS does better. [today.torke.kit.session.RunSnapshot.gpsRate] tells you what the
 * phone gives.
 */
public class LapTimer(public val start: TimingLine, public val sectorLines: List<TimingLine> = emptyList()) {
    private val _laps = mutableListOf<Lap>()
    public val laps: List<Lap> get() = _laps
    /** The fastest lap so far. */
    public val bestLap: Lap? get() = _laps.minByOrNull { it.time }

    /** A line crossed again this soon is ignored (GPS jitter on the line). */
    public var minimumLapTime: Double = 10.0

    private var lastT: Double? = null
    private var lastC: Coordinate? = null
    private var lapStart: Double? = null
    private var lastSplit: Double? = null
    private var sectors = mutableListOf<Double>()
    private var nextSector = 0
    private var distance = 0.0
    /** (meters, seconds) since the start of the current lap, and of the best lap. */
    private var profile = mutableListOf<Pair<Double, Double>>()
    private var bestProfile = listOf<Pair<Double, Double>>()

    /** Feeds one fix. Fixes without a position are ignored. */
    public fun process(fix: GPSSample): List<LapEvent> {
        val c = fix.coordinate ?: return emptyList()
        val prevT = lastT
        val prevC = lastC
        lastT = fix.t
        lastC = c
        if (prevT == null || prevC == null || fix.t <= prevT) return emptyList()
        val events = mutableListOf<LapEvent>()
        val step = Geo.distance(prevC, c)

        // Sector lines, in order, only during a lap
        val split = lastSplit
        if (lapStart != null && nextSector < sectorLines.size && split != null) {
            crossing(prevC, c, sectorLines[nextSector])?.let { f ->
                val t = prevT + f * (fix.t - prevT)
                sectors += t - split
                events += LapEvent.Sector(nextSector, t - split)
                lastSplit = t
                nextSector += 1
            }
        }

        val f = crossing(prevC, c, start)
        if (f != null) {
            val t = prevT + f * (fix.t - prevT)
            val begun = lapStart
            if (begun != null) {
                if (t - begun < minimumLapTime) return advance(events, fix.t, step)
                val lapDistance = distance + step * f
                val complete = nextSector == sectorLines.size
                val lastSector = lastSplit?.let { t - it }
                val lap = Lap(
                    number = _laps.size + 1, start = begun, time = t - begun,
                    sectors = if (complete && sectorLines.isNotEmpty()) sectors + (lastSector ?: 0.0) else emptyList(),
                    distance = lapDistance,
                )
                profile += lapDistance to (t - begun)
                if (bestLap?.let { lap.time < it.time } != false) bestProfile = profile.toList()
                _laps += lap
                events += LapEvent.Completed(lap)
            } else {
                events += LapEvent.Started(t)
            }
            // The new lap starts at the crossing; the rest of this step belongs to it
            beginLap(t)
            distance = step * (1 - f)
            profile += distance to (fix.t - t)
            return events
        }
        return advance(events, fix.t, step)
    }

    private fun advance(events: List<LapEvent>, t: Double, step: Double): List<LapEvent> {
        lapStart?.let { begun ->
            distance += step
            profile += distance to (t - begun)
        }
        return events
    }

    private fun beginLap(t: Double) {
        lapStart = t
        lastSplit = t
        sectors = mutableListOf()
        nextSector = 0
        profile = mutableListOf(0.0 to 0.0)
    }

    /** Seconds into the current lap at [time]; null before the first crossing. */
    public fun elapsed(time: Double): Double? = lapStart?.let { time - it }

    /** The current lap against the best one at the same distance: negative is ahead. Null until there is a best lap. */
    public val delta: Double?
        get() {
            val now = profile.lastOrNull() ?: return null
            if (bestProfile.size < 2) return null
            val bestT = timeAtDistance(now.first, bestProfile) ?: return null
            return now.second - bestT
        }

    /** The best lap plus the current delta: where this lap is heading. */
    public val predictedLap: Double?
        get() {
            val best = bestLap ?: return null
            val d = delta ?: return null
            return best.time + d
        }

    internal companion object {
        /** Where the segment p→q crosses [line], as a fraction of p→q; null if it doesn't. */
        fun crossing(p: Coordinate, q: Coordinate, line: TimingLine): Double? {
            val (qx, qy) = Geo.local(q, p)
            val (ax, ay) = Geo.local(line.a, p)
            val (bx, by) = Geo.local(line.b, p)
            val rx = qx
            val ry = qy
            val sx = bx - ax
            val sy = by - ay
            val denom = rx * sy - ry * sx
            if (abs(denom) <= 1e-12) return null
            val t = (ax * sy - ay * sx) / denom
            val u = (ax * ry - ay * rx) / denom
            return if (t > 0 && t <= 1 && u >= 0 && u <= 1) t else null
        }

        fun timeAtDistance(d: Double, profile: List<Pair<Double, Double>>): Double? {
            val first = profile.firstOrNull() ?: return null
            val last = profile.last()
            if (d < first.first) return null
            if (d >= last.first) return last.second
            for (i in 1 until profile.size) {
                if (profile[i].first >= d) {
                    val a = profile[i - 1]
                    val b = profile[i]
                    val span = b.first - a.first
                    return if (span > 0) a.second + (d - a.first) / span * (b.second - a.second) else b.second
                }
            }
            return null
        }
    }
}
