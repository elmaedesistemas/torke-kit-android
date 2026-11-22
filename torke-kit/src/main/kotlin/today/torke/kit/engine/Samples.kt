package today.torke.kit.engine

import kotlin.math.sqrt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A 3-component vector. In JSON it is an array, `[x, y, z]`, as in TorkeKit for iOS. */
@Serializable(with = Vec3Serializer::class)
public data class Vec3(val x: Double, val y: Double, val z: Double) {
    public operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    public operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    public operator fun times(k: Double): Vec3 = Vec3(x * k, y * k, z * k)
    public infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    public val length: Double get() = sqrt(this dot this)
    public fun normalized(): Vec3 = length.let { if (it > 0) this * (1 / it) else this }

    public companion object {
        public val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
    }
}

internal object Vec3Serializer : KSerializer<Vec3> {
    private val delegate = DoubleArraySerializer()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("today.torke.kit.Vec3", delegate.descriptor)
    override fun serialize(encoder: Encoder, value: Vec3): Unit = encoder.encodeSerializableValue(delegate, doubleArrayOf(value.x, value.y, value.z))
    override fun deserialize(decoder: Decoder): Vec3 = decoder.decodeSerializableValue(delegate).let { Vec3(it[0], it[1], it[2]) }
}

/**
 * One raw reading of the motion sensor, in the phone's frame.
 *
 * [user] is the acceleration without gravity (m/s²); [gravity] is the direction of gravity
 * (any length, it is normalized). Timestamps are seconds on a monotonic clock shared with
 * [GPSSample.t].
 */
@Serializable
public data class RawMotionSample(val t: Double, val user: Vec3, val gravity: Vec3)

/** Longitudinal acceleration of the vehicle (m/s², positive forward), projected by [AxisCalibration]. */
@Serializable
public data class MotionSample(val t: Double, val accel: Double)

/** One GPS fix: speed (m/s) with its accuracy (m/s, one standard deviation) and, when known, the position. */
@Serializable
public data class GPSSample(
    val t: Double,
    val speed: Double,
    val speedAccuracy: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Horizontal accuracy of the position, in meters. */
    val horizontalAccuracy: Double? = null,
) {
    /** Fixes with [speedAccuracy] of [NO_SPEED] or more carry a position but no speed. */
    val hasSpeed: Boolean get() = speedAccuracy < NO_SPEED

    val coordinate: Coordinate?
        get() = if (latitude != null && longitude != null) Coordinate(latitude, longitude) else null

    public companion object {
        public const val NO_SPEED: Double = 50.0
    }
}

/** A point on Earth, in degrees (WGS 84). */
@Serializable
public data class Coordinate(val latitude: Double, val longitude: Double)

/** Official drag racing distances and speeds. */
public object DragDistance {
    /** 1 foot: the rollout tracks discount before the timers start. */
    public const val ROLLOUT: Double = 0.3048
    public const val SIXTY_FEET: Double = 18.288
    public const val EIGHTH_MILE: Double = 201.168
    public const val QUARTER_MILE: Double = 402.336
    /** 100 km/h in m/s. */
    public const val SPEED_100: Double = 100.0 / 3.6
}

/** The marks a drag run is timed at. */
@Serializable
public enum class DragMark { sixtyFeet, zeroTo100, eighthMile, quarterMile }

/** A point of the run's curve, measured from the launch. */
@Serializable
public data class TracePoint(val t: Double, val speed: Double, val distance: Double)

/**
 * The times of a run. Distance times discount the 1-foot rollout, like a dragstrip; 0 to 100
 * counts from the first movement. Speeds in km/h, times in seconds.
 */
@Serializable
public data class RunResult(
    /** When the car started moving, on the samples' clock. */
    val launchTime: Double,
    val zeroTo100: Double? = null,
    val sixtyFeet: Double? = null,
    val eighthMile: Double? = null,
    val eighthMileKmh: Double? = null,
    val quarterMile: Double? = null,
    val quarterMileKmh: Double? = null,
    val topSpeedKmh: Double,
    /** Accelerometer bias the filter ended with (m/s²): large values mean a loose mount. */
    val accelBias: Double,
    val trace: List<TracePoint> = emptyList(),
) {
    /** The time shown big: the longest distance the run covered. */
    val headline: Pair<DragMark, Double>?
        get() = quarterMile?.let { DragMark.quarterMile to it }
            ?: eighthMile?.let { DragMark.eighthMile to it }
            ?: zeroTo100?.let { DragMark.zeroTo100 to it }
            ?: sixtyFeet?.let { DragMark.sixtyFeet to it }
}
