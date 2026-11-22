package today.torke.kit.analysis

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.RunResult
import today.torke.kit.engine.elapsed
import today.torke.kit.engine.thousandFeet
import today.torke.kit.format.TorkeRun

// Validation: how far TorkeKit's times are from a dragstrip's timeslip. Each run is analyzed
// again from its raw samples and compared mark by mark; many runs give the bias, the typical
// error and the worst case for each mark.

/** Speeds on a timeslip: km/h or mph, whatever the track prints. */
@Serializable
public enum class SpeedUnit {
    kmh, mph;

    internal fun toKmh(v: Double): Double = if (this == kmh) v else v * 1.609_344
}

/**
 * The times a dragstrip printed for a run. Times in seconds from the moment the car left the
 * staging beam (reaction time is not part of them). Fill in what the slip has.
 */
@Serializable
public data class Timeslip(
    val sixtyFeet: Double? = null,
    val threeThirtyFeet: Double? = null,
    val eighthMile: Double? = null,
    val eighthMileSpeed: Double? = null,
    val thousandFeet: Double? = null,
    val quarterMile: Double? = null,
    val quarterMileSpeed: Double? = null,
    /** The unit of [eighthMileSpeed] and [quarterMileSpeed]. */
    val speedUnit: SpeedUnit = SpeedUnit.kmh,
    /** Informative only: it is not compared. */
    val reactionTime: Double? = null,
    val notes: String? = null,
) {
    /** The reference value for a mark, speeds in km/h. */
    public fun value(mark: ValidationMark): Double? = when (mark) {
        ValidationMark.sixtyFeet -> sixtyFeet
        ValidationMark.threeThirtyFeet -> threeThirtyFeet
        ValidationMark.eighthMile -> eighthMile
        ValidationMark.eighthMileSpeed -> eighthMileSpeed?.let(speedUnit::toKmh)
        ValidationMark.thousandFeet -> thousandFeet
        ValidationMark.quarterMile -> quarterMile
        ValidationMark.quarterMileSpeed -> quarterMileSpeed?.let(speedUnit::toKmh)
    }
}

/** The marks a timeslip can have. Times in seconds, speeds in km/h. */
@Serializable
public enum class ValidationMark {
    sixtyFeet, threeThirtyFeet, eighthMile, eighthMileSpeed, thousandFeet, quarterMile, quarterMileSpeed;

    public val isSpeed: Boolean get() = this == eighthMileSpeed || this == quarterMileSpeed

    /** The same mark read from TorkeKit's result. */
    public fun value(result: RunResult): Double? = when (this) {
        sixtyFeet -> result.sixtyFeet
        threeThirtyFeet -> result.elapsed(DragDistance.THREE_THIRTY_FEET)
        eighthMile -> result.eighthMile
        eighthMileSpeed -> result.eighthMileKmh
        thousandFeet -> result.thousandFeet
        quarterMile -> result.quarterMile
        quarterMileSpeed -> result.quarterMileKmh
    }
}

/** How much error each mark may have. The defaults are TorkeKit's accuracy targets. */
@Serializable
public data class ValidationTargets(
    val sixtyFeet: Double = 0.10,
    val threeThirtyFeet: Double = 0.12,
    val eighthMile: Double = 0.12,
    val thousandFeet: Double = 0.15,
    val quarterMile: Double = 0.15,
    /** For both trap speeds, km/h. */
    val speedKmh: Double = 3.0,
) {
    public fun limit(mark: ValidationMark): Double = when (mark) {
        ValidationMark.sixtyFeet -> sixtyFeet
        ValidationMark.threeThirtyFeet -> threeThirtyFeet
        ValidationMark.eighthMile -> eighthMile
        ValidationMark.thousandFeet -> thousandFeet
        ValidationMark.quarterMile -> quarterMile
        ValidationMark.eighthMileSpeed, ValidationMark.quarterMileSpeed -> speedKmh
    }
}

/** One mark of one run: what TorkeKit measured against the timeslip. */
@Serializable
public data class MarkComparison(val mark: ValidationMark, val measured: Double, val reference: Double) {
    /** Measured minus reference, in seconds or km/h. For times, positive means TorkeKit read slower than the track; for speeds, faster. */
    val error: Double get() = measured - reference
}

/** One run against its timeslip. */
@Serializable
public data class RunComparison(val label: String? = null, val marks: List<MarkComparison>) {
    public companion object {
        public fun of(result: RunResult, timeslip: Timeslip, label: String? = null): RunComparison =
            RunComparison(label, ValidationMark.entries.mapNotNull { mark ->
                val reference = timeslip.value(mark) ?: return@mapNotNull null
                val measured = mark.value(result) ?: return@mapNotNull null
                MarkComparison(mark, measured, reference)
            })
    }
}

/** The errors of one mark over every run. */
@Serializable
public data class MarkSummary(
    val mark: ValidationMark,
    val count: Int,
    /** The mean error: a systematic offset, if any. */
    val bias: Double,
    val meanAbsolute: Double,
    /** Root mean square: the typical error, the one checked against the target. */
    val rms: Double,
    /** The largest error, with its sign. */
    val worst: Double,
    val limit: Double,
) {
    val passed: Boolean get() = rms <= limit
}

/** One run to validate: its result (null if it had no launch), its timeslip and a label. */
public data class ValidationInput(val label: String, val result: RunResult?, val timeslip: Timeslip)

/** Many runs against their timeslips. */
@Serializable
public data class ValidationReport(
    val runs: List<RunComparison>,
    /** Runs that could not be analyzed (no launch found), by label. */
    val skipped: List<String>,
    val summaries: List<MarkSummary>,
    val targets: ValidationTargets,
) {
    /** Every mark with data is within its target. */
    val passed: Boolean get() = summaries.isNotEmpty() && summaries.all { it.passed }

    public companion object {
        public fun of(inputs: List<ValidationInput>, targets: ValidationTargets = ValidationTargets()): ValidationReport {
            val runs = inputs.mapNotNull { i -> i.result?.let { RunComparison.of(it, i.timeslip, i.label) } }
            val skipped = inputs.filter { it.result == null }.map { it.label }
            val summaries = ValidationMark.entries.mapNotNull { mark ->
                val errors = runs.flatMap { r -> r.marks.filter { it.mark == mark }.map { it.error } }
                if (errors.isEmpty()) return@mapNotNull null
                val n = errors.size.toDouble()
                MarkSummary(
                    mark = mark, count = errors.size,
                    bias = errors.sum() / n,
                    meanAbsolute = errors.sumOf { abs(it) } / n,
                    rms = sqrt(errors.sumOf { it * it } / n),
                    worst = errors.maxBy { abs(it) },
                    limit = targets.limit(mark),
                )
            }
            return ValidationReport(runs, skipped, summaries, targets)
        }
    }
}

/** This run, analyzed again with the current engine, against its timeslip. */
public fun TorkeRun.compare(timeslip: Timeslip, label: String? = null): RunComparison? =
    analyze()?.let { RunComparison.of(it, timeslip, label) }

/**
 * A set of runs with their timeslips, as JSON (`torke.validation`), for `torke-validate`.
 *
 * ```json
 * { "format": "torke.validation", "version": 1,
 *   "runs": [{ "run": "runs/first.torkerun.json", "label": "Run 1",
 *              "timeslip": { "sixtyFeet": 2.12, "quarterMile": 12.91, "quarterMileSpeed": 114.2, "speedUnit": "mph" } }] }
 * ```
 */
@Serializable
public data class ValidationSet(
    val format: String = FORMAT_NAME,
    val version: Int = 1,
    val targets: ValidationTargets? = null,
    val runs: List<Entry>,
) {
    @Serializable
    public data class Entry(
        /** Path to the `.torkerun.json`, relative to the set's file. */
        val run: String,
        val label: String? = null,
        val timeslip: Timeslip,
    )

    /** Reads every run (paths relative to [directory]) and builds the report. */
    public fun report(directory: File, useStoredResults: Boolean = false): ValidationReport {
        val inputs = runs.mapIndexed { i, entry ->
            val file = File(entry.run).let { if (it.isAbsolute) it else File(directory, entry.run) }
            val run = TorkeRun.decode(file.readText())
            val result = if (useStoredResults) run.result ?: run.analyze() else run.analyze()
            ValidationInput(entry.label ?: "${i + 1}", result, entry.timeslip)
        }
        return ValidationReport.of(inputs, targets ?: ValidationTargets())
    }

    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        public const val FORMAT_NAME: String = "torke.validation"

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
        }

        /** Reads a `torke.validation` JSON. Throws [TorkeRun.FormatException] for other formats or newer versions. */
        public fun decode(text: String): ValidationSet {
            val set = json.decodeFromString(serializer(), text)
            if (set.format != FORMAT_NAME) throw TorkeRun.FormatException("Not a torke.validation file: ${set.format}")
            if (set.version > 1) throw TorkeRun.FormatException("Written by a newer TorkeKit (version ${set.version}): update the SDK")
            return set
        }

        /** The report as JSON, for scripts and CI. */
        public fun encodeReport(report: ValidationReport): String = json.encodeToString(ValidationReport.serializer(), report)
    }
}
