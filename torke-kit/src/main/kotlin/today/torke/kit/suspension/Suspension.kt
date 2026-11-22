package today.torke.kit.suspension

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.serialization.Serializable

/**
 * A point in the side view of the car: [x] forward of the rear axle centerline, [y] up from
 * the ground. Any length unit, as long as the whole setup uses the same one.
 */
@Serializable
public data class Point2(val x: Double, val y: Double)

@Serializable
public enum class LengthUnit { inches, centimeters }

/**
 * The rear suspension of a car, as measured in the garage. The math works in whatever unit
 * the points use; [unit] only says which one it is.
 *
 * Points by kind:
 * - [Kind.fourLink]: `upperRear`, `upperFront`, `lowerRear`, `lowerFront`
 *   (rear = bracket on the axle housing, front = bracket on the chassis).
 * - [Kind.ladderBar]: `pivot`, the ladder bar's front mount on the chassis.
 * - [Kind.torqueArm]: `armFront` (the torque arm's front mount) and the lower control arms,
 *   `lowerRear` and `lowerFront`.
 */
@Serializable
public data class SuspensionSetup(
    val kind: Kind,
    val unit: LengthUnit = LengthUnit.inches,
    /** Distance between the front and rear axle centerlines. */
    val wheelbase: Double,
    /** Height of the center of gravity above the ground. */
    val cgHeight: Double,
    val points: Map<String, Point2>,
    /** Free text, such as "upper bars on hole 3". */
    val notes: String? = null,
) {
    @Serializable
    public enum class Kind { fourLink, ladderBar, torqueArm }

    /** The same setup in another unit. */
    public fun converted(target: LengthUnit): SuspensionSetup {
        if (target == unit) return this
        val k = if (target == LengthUnit.centimeters) 2.54 else 1 / 2.54
        return copy(unit = target, wheelbase = wheelbase * k, cgHeight = cgHeight * k, points = points.mapValues { Point2(it.value.x * k, it.value.y * k) })
    }

    /** The instant center, anti-squat and bar angles. */
    public fun geometry(): SuspensionGeometry = SuspensionGeometry.of(this)

    public companion object {
        public fun fourLink(wheelbase: Double, cgHeight: Double, upperRear: Point2, upperFront: Point2, lowerRear: Point2, lowerFront: Point2, unit: LengthUnit = LengthUnit.inches): SuspensionSetup =
            SuspensionSetup(Kind.fourLink, unit, wheelbase, cgHeight, mapOf("upperRear" to upperRear, "upperFront" to upperFront, "lowerRear" to lowerRear, "lowerFront" to lowerFront))

        public fun ladderBar(wheelbase: Double, cgHeight: Double, pivot: Point2, unit: LengthUnit = LengthUnit.inches): SuspensionSetup =
            SuspensionSetup(Kind.ladderBar, unit, wheelbase, cgHeight, mapOf("pivot" to pivot))

        public fun torqueArm(wheelbase: Double, cgHeight: Double, armFront: Point2, lowerRear: Point2, lowerFront: Point2, unit: LengthUnit = LengthUnit.inches): SuspensionSetup =
            SuspensionSetup(Kind.torqueArm, unit, wheelbase, cgHeight, mapOf("armFront" to armFront, "lowerRear" to lowerRear, "lowerFront" to lowerFront))
    }
}

/** One bar seen from the side. */
public data class Bar(val name: String, val rear: Point2, val front: Point2) {
    /** Degrees from horizontal; positive when the bar rises toward the front. */
    val angle: Double get() = Math.toDegrees(atan2(front.y - rear.y, front.x - rear.x))
    val length: Double get() = hypot(front.x - rear.x, front.y - rear.y)
}

/**
 * What a suspension setup does under acceleration, from textbook side-view geometry.
 *
 * The instant center (IC) is where the axle's links meet, seen from the side. The line from the
 * rear tire's contact patch through the IC sets how much the launch pushes the car up
 * (anti-squat): 100 % holds the rear level, more lifts it, less lets it squat.
 *
 * `antiSquat = (icHeight / icLength) / (cgHeight / wheelbase) × 100`
 */
public data class SuspensionGeometry(
    val instantCenter: Point2?,
    /** Percent. */
    val antiSquat: Double?,
    val bars: List<Bar>,
    val warnings: List<Warning>,
) {
    @Serializable
    public enum class Warning { incomplete, parallelLinks, instantCenterBehindAxle, instantCenterBelowGround }

    /** IC length: how far forward of the rear axle centerline. */
    val icLength: Double? get() = instantCenter?.x
    /** IC height above the ground. */
    val icHeight: Double? get() = instantCenter?.y

    public companion object {
        internal fun of(setup: SuspensionSetup): SuspensionGeometry {
            val p = setup.points
            var ic: Point2? = null
            var bars = emptyList<Bar>()
            val warnings = mutableListOf<Warning>()
            when (setup.kind) {
                SuspensionSetup.Kind.fourLink -> {
                    val ur = p["upperRear"]; val uf = p["upperFront"]; val lr = p["lowerRear"]; val lf = p["lowerFront"]
                    if (ur != null && uf != null && lr != null && lf != null) {
                        bars = listOf(Bar("upper", ur, uf), Bar("lower", lr, lf))
                        ic = intersect(ur, uf, lr, lf)
                        if (ic == null) warnings.add(Warning.parallelLinks)
                    } else warnings.add(Warning.incomplete)
                }
                SuspensionSetup.Kind.ladderBar -> {
                    ic = p["pivot"]
                    if (ic == null) warnings.add(Warning.incomplete)
                }
                SuspensionSetup.Kind.torqueArm -> {
                    // The arm's front mount only pushes up or down: its line of action is vertical.
                    val arm = p["armFront"]; val lr = p["lowerRear"]; val lf = p["lowerFront"]
                    if (arm != null && lr != null && lf != null) {
                        bars = listOf(Bar("lower", lr, lf))
                        ic = intersect(lr, lf, arm, Point2(arm.x, arm.y + 1))
                        if (ic == null) warnings.add(Warning.parallelLinks)
                    } else warnings.add(Warning.incomplete)
                }
            }
            var antiSquat: Double? = null
            val center = ic
            if (center != null) {
                if (center.x <= 0) warnings.add(Warning.instantCenterBehindAxle)
                if (center.y < 0) warnings.add(Warning.instantCenterBelowGround)
                if (center.x > 0 && setup.cgHeight > 0 && setup.wheelbase > 0) {
                    antiSquat = (center.y / center.x) / (setup.cgHeight / setup.wheelbase) * 100
                }
            }
            return SuspensionGeometry(center, antiSquat, bars, warnings)
        }

        /** Where the line through a1–a2 meets the line through b1–b2. Null when parallel. */
        internal fun intersect(a1: Point2, a2: Point2, b1: Point2, b2: Point2): Point2? {
            val d1x = a2.x - a1.x; val d1y = a2.y - a1.y
            val d2x = b2.x - b1.x; val d2y = b2.y - b1.y
            val cross = d1x * d2y - d1y * d2x
            if (abs(cross) <= 1e-9) return null
            val t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / cross
            return Point2(a1.x + t * d1x, a1.y + t * d1y)
        }
    }
}

/** A range with both ends included. */
@Serializable
public data class ValueRange(val min: Double, val max: Double) {
    public operator fun contains(v: Double): Boolean = v in min..max

    /** How far outside the range, as a share of its width (0 inside). */
    public fun miss(v: Double): Double {
        val width = maxOf(max - min, 1.0)
        return when {
            v < min -> (min - v) / width
            v > max -> (v - max) / width
            else -> 0.0
        }
    }

    public companion object {
        public fun of(a: Double, b: Double): ValueRange = ValueRange(minOf(a, b), maxOf(a, b))
    }
}

/**
 * Where you want the geometry to land. TorkeKit ships no values: they depend on the car, the
 * tires and the track, and each builder or racer brings their own.
 */
@Serializable
public data class TargetWindow(
    val icLength: ValueRange? = null,
    val icHeight: ValueRange? = null,
    val antiSquat: ValueRange? = null,
    /** A miss up to this share of a range's width counts as "near". */
    val nearShare: Double = 0.25,
) {
    @Serializable
    public enum class Status { inside, near, below, above, unknown }

    @Serializable
    public enum class Metric { icLength, icHeight, antiSquat }

    public fun status(metric: Metric, geometry: SuspensionGeometry): Status {
        val (range, value) = when (metric) {
            Metric.icLength -> icLength to geometry.icLength
            Metric.icHeight -> icHeight to geometry.icHeight
            Metric.antiSquat -> antiSquat to geometry.antiSquat
        }
        if (range == null || value == null) return Status.unknown
        if (value in range) return Status.inside
        if (range.miss(value) <= nearShare) return Status.near
        return if (value < range.min) Status.below else Status.above
    }

    /** 0 when every set range is met; it grows with the misses. Null when a needed value is missing. */
    public fun score(geometry: SuspensionGeometry): Double? {
        var total = 0.0
        for ((range, value) in listOf(icLength to geometry.icLength, icHeight to geometry.icHeight, antiSquat to geometry.antiSquat)) {
            if (range == null) continue
            if (value == null) return null
            total += range.miss(value)
        }
        return total
    }
}

/** The holes each 4-link bracket offers, to try every combination. */
@Serializable
public data class FourLinkHoles(
    val upperRear: List<Point2>,
    val upperFront: List<Point2>,
    val lowerRear: List<Point2>,
    val lowerFront: List<Point2>,
) {
    /** Every combination of holes, best first by the window's score. Combinations without an instant center forward of the axle are left out. */
    public fun ranked(base: SuspensionSetup, target: TargetWindow, limit: Int = 20): List<HoleOption> {
        val out = mutableListOf<HoleOption>()
        upperRear.forEachIndexed { a, ur ->
            upperFront.forEachIndexed { b, uf ->
                lowerRear.forEachIndexed { c, lr ->
                    lowerFront.forEachIndexed { d, lf ->
                        val setup = base.copy(kind = SuspensionSetup.Kind.fourLink, points = mapOf("upperRear" to ur, "upperFront" to uf, "lowerRear" to lr, "lowerFront" to lf))
                        val geometry = setup.geometry()
                        val score = target.score(geometry)
                        if (score != null && (geometry.icLength ?: 0.0) > 0) out.add(HoleOption(listOf(a, b, c, d), setup, geometry, score))
                    }
                }
            }
        }
        return out.sortedBy { it.score }.take(limit)
    }
}

/** One combination of holes: index on each bracket (upper rear, upper front, lower rear, lower front). */
public data class HoleOption(val holes: List<Int>, val setup: SuspensionSetup, val geometry: SuspensionGeometry, val score: Double)

/** What changed between two setups, to read next to the change in 60-foot time. */
public data class SetupChange(
    val icLength: Double?,
    val icHeight: Double?,
    val antiSquat: Double?,
    /** Points that moved, with how much (after minus before). */
    val moved: Map<String, Point2>,
) {
    public companion object {
        public fun between(before: SuspensionSetup, after: SuspensionSetup): SetupChange {
            val old = before.converted(after.unit)
            val a = old.geometry()
            val b = after.geometry()
            fun delta(x: Double?, y: Double?) = if (x != null && y != null) y - x else null
            val moved = after.points.mapNotNull { (name, p) ->
                val q = old.points[name] ?: return@mapNotNull null
                val d = Point2(p.x - q.x, p.y - q.y)
                if (abs(d.x) > 1e-9 || abs(d.y) > 1e-9) name to d else null
            }.toMap()
            return SetupChange(delta(a.icLength, b.icLength), delta(a.icHeight, b.icHeight), delta(a.antiSquat, b.antiSquat), moved)
        }
    }
}
