package today.torke.kit.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import today.torke.kit.analysis.RunQuality
import today.torke.kit.analysis.Weather
import today.torke.kit.engine.AxisCalibration
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.IntervalAnalyzer
import today.torke.kit.engine.IntervalResult
import today.torke.kit.suspension.SuspensionSetup
import today.torke.kit.engine.Coordinate
import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.RunAnalyzer
import today.torke.kit.engine.RunResult

/**
 * A recorded run in the open `torke.run` format: every raw sample plus the result. The same
 * JSON is read and written by TorkeKit for iOS and for Android.
 */
@Serializable
public data class TorkeRun(
    val format: String = FORMAT_NAME,
    val version: Int = CURRENT_VERSION,
    /** ISO 8601, such as "2026-11-21T16:54:06Z". */
    val recordedAt: String? = null,
    /** Free text, such as "Google Pixel 9". */
    val device: String? = null,
    /** The app that recorded it, such as "Torke 1.0". */
    val app: String? = null,
    val motion: List<RawMotionSample> = emptyList(),
    val gps: List<GPSSample> = emptyList(),
    val result: RunResult? = null,
    /** The result of a rolling or braking run. */
    val interval: IntervalResult? = null,
    /** Weather at the strip, for corrected times. */
    val weather: Weather? = null,
    /** The vehicle's suspension when the run was made, to compare setups. */
    val suspension: SuspensionSetup? = null,
) {
    /** Computes the times again from the raw samples. */
    public fun analyze(stopDistance: Double = DragDistance.QUARTER_MILE + 20): RunResult? =
        RunAnalyzer.analyze(AxisCalibration.forwardAccel(motion), gps, stopDistance)

    /** A rolling interval, such as 60–130 km/h, from the raw samples. */
    public fun rolling(fromKmh: Double = 60.0, toKmh: Double = 130.0): IntervalResult? = IntervalAnalyzer.rolling(motion, gps, fromKmh, toKmh)

    /** A braking, such as 100–0 km/h, from the raw samples. */
    public fun braking(fromKmh: Double = 100.0): IntervalResult? = IntervalAnalyzer.braking(motion, gps, fromKmh)

    /** How much the run can be trusted. See [RunQuality]. */
    public fun quality(): RunQuality? = RunQuality.assess(this)

    /** Where the run launched, from the GPS fix closest before the launch. */
    val startLocation: Coordinate?
        get() {
            val launch = result?.launchTime ?: return null
            return gps.lastOrNull { it.t <= launch && it.coordinate != null }?.coordinate
        }

    public fun encode(): String = json.encodeToString(serializer(), this)

    public class FormatException(message: String) : SerializationException(message)

    public companion object {
        public const val FORMAT_NAME: String = "torke.run"
        public const val CURRENT_VERSION: Int = 1

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** Reads a `torke.run` JSON. Throws [FormatException] for other formats or newer versions. */
        public fun decode(text: String): TorkeRun {
            val run = json.decodeFromString(serializer(), text)
            if (run.format != FORMAT_NAME) throw FormatException("Not a torke.run file: ${run.format}")
            if (run.version > CURRENT_VERSION) throw FormatException("Written by a newer TorkeKit (version ${run.version}): update the SDK")
            return run
        }
    }
}
