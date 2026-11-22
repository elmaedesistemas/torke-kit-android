package today.torke.validate

import java.io.File
import java.util.Locale
import kotlin.system.exitProcess
import today.torke.kit.analysis.SpeedUnit
import today.torke.kit.analysis.Timeslip
import today.torke.kit.analysis.ValidationInput
import today.torke.kit.analysis.ValidationMark
import today.torke.kit.analysis.ValidationReport
import today.torke.kit.analysis.ValidationSet
import today.torke.kit.analysis.ValidationTargets
import today.torke.kit.format.TorkeRun

private const val USAGE = """torke-validate: TorkeKit's times against dragstrip timeslips.

Usage:
  torke-validate SET.json [--json] [--stored] [--runs]
  torke-validate RUN.torkerun.json [timeslip options] [--json] [--stored]

A set is a torke.validation JSON with many runs and their timeslips. For a single run, give the
timeslip on the command line:
  --60ft S  --330ft S  --1/8 S  --1/8-speed V  --1000ft S  --1/4 S  --1/4-speed V  --mph

Options:
  --json     Print the report as JSON.
  --stored   Use the result saved in each run instead of analyzing it again.
  --runs     Also print every run, mark by mark.

Exit status: 0 when every mark is within its target, 1 when not, 2 on bad input."""

private val markFlags = mapOf(
    "--60ft" to ValidationMark.sixtyFeet,
    "--330ft" to ValidationMark.threeThirtyFeet,
    "--1/8" to ValidationMark.eighthMile,
    "--1/8-speed" to ValidationMark.eighthMileSpeed,
    "--1000ft" to ValidationMark.thousandFeet,
    "--1/4" to ValidationMark.quarterMile,
    "--1/4-speed" to ValidationMark.quarterMileSpeed,
)

private val markNames = mapOf(
    ValidationMark.sixtyFeet to "60 ft",
    ValidationMark.threeThirtyFeet to "330 ft",
    ValidationMark.eighthMile to "1/8 mile",
    ValidationMark.eighthMileSpeed to "1/8 speed",
    ValidationMark.thousandFeet to "1000 ft",
    ValidationMark.quarterMile to "1/4 mile",
    ValidationMark.quarterMileSpeed to "1/4 speed",
)

public fun main(args: Array<String>) {
    exitProcess(run(args.toList(), ::println))
}

/** The whole command, with output to [out]; returns the exit status. */
public fun run(args: List<String>, out: (String) -> Unit): Int {
    if (args.isEmpty() || "-h" in args || "--help" in args) {
        out(USAGE)
        return if (args.isEmpty()) 2 else 0
    }
    val json = "--json" in args
    val stored = "--stored" in args
    val showRuns = "--runs" in args
    val path = args.first()
    val file = File(path)
    if (!file.isFile) return fail(out, "No such file: $path")

    val report = try {
        val text = file.readText()
        if (text.contains("\"torke.validation\"")) {
            ValidationSet.decode(text).report(file.absoluteFile.parentFile, stored)
        } else {
            val slip = timeslipFrom(args.drop(1)) ?: return fail(out, "Give at least one timeslip mark, such as --1/4 12.85. See --help.")
            val run = TorkeRun.decode(text)
            val result = if (stored) run.result ?: run.analyze() else run.analyze()
            ValidationReport.of(listOf(ValidationInput(file.name, result, slip)), ValidationTargets())
        }
    } catch (e: NumberFormatException) {
        return fail(out, "Not a number: ${e.message}")
    } catch (e: Exception) {
        return fail(out, e.message ?: e.toString())
    }

    if (json) out(ValidationSet.encodeReport(report)) else print(report, showRuns, out)
    return if (report.passed) 0 else 1
}

private fun fail(out: (String) -> Unit, message: String): Int {
    out("torke-validate: $message")
    return 2
}

private fun timeslipFrom(args: List<String>): Timeslip? {
    val values = mutableMapOf<ValidationMark, Double>()
    var i = 0
    while (i < args.size) {
        val mark = markFlags[args[i]]
        if (mark != null) {
            val v = args.getOrNull(i + 1) ?: throw NumberFormatException("${args[i]} needs a value")
            values[mark] = v.toDouble()
            i += 2
        } else i += 1
    }
    if (values.isEmpty()) return null
    return Timeslip(
        sixtyFeet = values[ValidationMark.sixtyFeet],
        threeThirtyFeet = values[ValidationMark.threeThirtyFeet],
        eighthMile = values[ValidationMark.eighthMile],
        eighthMileSpeed = values[ValidationMark.eighthMileSpeed],
        thousandFeet = values[ValidationMark.thousandFeet],
        quarterMile = values[ValidationMark.quarterMile],
        quarterMileSpeed = values[ValidationMark.quarterMileSpeed],
        speedUnit = if ("--mph" in args) SpeedUnit.mph else SpeedUnit.kmh,
    )
}

private fun f(v: Double, digits: Int = 3, sign: Boolean = true) =
    String.format(Locale.US, if (sign) "%+.${digits}f" else "%.${digits}f", v)

private fun print(report: ValidationReport, showRuns: Boolean, out: (String) -> Unit) {
    val n = report.runs.size
    out("TorkeKit validation · $n run${if (n == 1) "" else "s"}" + if (report.skipped.isEmpty()) "" else " · skipped (no launch): ${report.skipped.joinToString()}")
    out("")
    out(String.format(Locale.US, "%-10s %5s %9s %9s %9s %9s %9s", "Mark", "Runs", "Bias", "Mean |e|", "RMS", "Worst", "Target"))
    for (s in report.summaries) {
        val d = if (s.mark.isSpeed) 2 else 3
        out(String.format(Locale.US, "%-10s %5d %9s %9s %9s %9s %9s  %s", markNames[s.mark], s.count,
            f(s.bias, d), f(s.meanAbsolute, d, false), f(s.rms, d, false), f(s.worst, d), "±" + f(s.limit, d, false), if (s.passed) "ok" else "OVER"))
    }
    out("")
    out("Times in seconds, speeds in km/h. Error = TorkeKit minus timeslip; the RMS is checked against the target.")
    if (showRuns) {
        for (r in report.runs) {
            out("")
            out(r.label ?: "run")
            for (m in r.marks) {
                val d = if (m.mark.isSpeed) 2 else 3
                out(String.format(Locale.US, "  %-10s %10s %10s %9s", markNames[m.mark], f(m.measured, d, false), f(m.reference, d, false), f(m.error, d)))
            }
        }
    }
    out("")
    out(if (report.passed) "PASS: every mark is within its target." else if (report.summaries.isEmpty()) "No marks to compare." else "FAIL: some marks are over their target.")
}
