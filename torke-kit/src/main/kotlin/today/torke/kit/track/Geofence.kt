package today.torke.kit.track

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import today.torke.kit.engine.Coordinate
import today.torke.kit.format.TorkeRun

/**
 * An area on the map: a circle or a polygon, such as a dragstrip or an event's paddock. Use it
 * to decide where a run counts.
 *
 * JSON: `{ "kind": "circle", "center": { "latitude", "longitude" }, "radius": 300 }` or
 * `{ "kind": "polygon", "points": [{ "latitude", "longitude" }, …] }`, with an optional `name`.
 */
@Serializable
public data class Geofence(
    val kind: Kind,
    val name: String? = null,
    val center: Coordinate? = null,
    /** Meters, for a circle. */
    val radius: Double? = null,
    /** The corners, in order, for a polygon. */
    val points: List<Coordinate>? = null,
) {
    @Serializable
    public enum class Kind { circle, polygon }

    public operator fun contains(c: Coordinate): Boolean = when (kind) {
        Kind.circle -> center != null && radius != null && Geo.distance(center, c) <= radius
        Kind.polygon -> {
            val pts = points
            if (pts == null || pts.size < 3) false else {
                // Ray casting on a local flat projection around the point: fine for a track
                val p = pts.map { Geo.local(it, c) }
                var inside = false
                var j = p.size - 1
                for (i in p.indices) {
                    val (xi, yi) = p[i]
                    val (xj, yj) = p[j]
                    if ((yi > 0) != (yj > 0) && 0 < (xj - xi) * (0 - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
                inside
            }
        }
    }

    public companion object {
        public fun circle(center: Coordinate, radius: Double, name: String? = null): Geofence = Geofence(Kind.circle, name, center, radius)
        public fun polygon(points: List<Coordinate>, name: String? = null): Geofence = Geofence(Kind.polygon, name, points = points)
    }
}

/** Where a run was, against a geofence. */
@Serializable
public data class GeofenceCheck(
    /** The run launched inside. */
    val startInside: Boolean,
    /** Share of the GPS fixes with a position (during the run) that were inside, 0 to 1. */
    val shareInside: Double,
) {
    /** Every positioned fix of the run was inside. */
    val allInside: Boolean get() = shareInside >= 1
}

/** Where this run was against [geofence]. Null without a launch position. */
public fun TorkeRun.check(geofence: Geofence): GeofenceCheck? {
    val start = startLocation ?: return null
    val r = result
    val launch = r?.launchTime ?: Double.NEGATIVE_INFINITY
    val end = r?.trace?.lastOrNull()?.let { r.launchTime + it.t } ?: Double.POSITIVE_INFINITY
    val fixes = gps.filter { it.t in launch..end }.mapNotNull { it.coordinate }
    val inside = fixes.count { it in geofence }
    return GeofenceCheck(start in geofence, if (fixes.isEmpty()) 1.0 else inside.toDouble() / fixes.size)
}

/** The first geofence that contains [coordinate], such as the track a run launched at. */
public fun List<Geofence>.firstContaining(coordinate: Coordinate): Geofence? = firstOrNull { coordinate in it }

/** Small geodesy for tracks: distances in meters and a local flat projection. */
public object Geo {
    internal const val EARTH_RADIUS: Double = 6_371_008.8

    /** Great-circle distance in meters (haversine). */
    public fun distance(a: Coordinate, b: Coordinate): Double {
        val lat1 = a.latitude * PI / 180
        val lat2 = b.latitude * PI / 180
        val dLat = lat2 - lat1
        val dLon = (b.longitude - a.longitude) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(h)))
    }

    /** Meters east (first) and north (second) of [origin]. Accurate within a few kilometers. */
    public fun local(c: Coordinate, origin: Coordinate): Pair<Double, Double> {
        val x = (c.longitude - origin.longitude) * PI / 180 * EARTH_RADIUS * cos(origin.latitude * PI / 180)
        val y = (c.latitude - origin.latitude) * PI / 180 * EARTH_RADIUS
        return x to y
    }
}
