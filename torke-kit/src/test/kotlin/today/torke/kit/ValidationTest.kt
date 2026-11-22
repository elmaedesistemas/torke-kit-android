package today.torke.kit

import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import today.torke.kit.analysis.RunComparison
import today.torke.kit.analysis.SpeedUnit
import today.torke.kit.analysis.Timeslip
import today.torke.kit.analysis.ValidationInput
import today.torke.kit.analysis.ValidationMark
import today.torke.kit.analysis.ValidationReport
import today.torke.kit.analysis.ValidationSet
import today.torke.kit.analysis.ValidationTargets
import today.torke.kit.engine.RunResult
import today.torke.kit.format.TorkeRun

class ValidationTest {
    private fun resourceDir() = File(javaClass.getResource("/validation.json")!!.toURI()).parentFile

    @Test fun comparesOnlyTheMarksBothHave() {
        val result = RunResult(launchTime = 0.0, zeroTo100 = 5.0, sixtyFeet = 2.05, eighthMile = 8.4, eighthMileKmh = 146.0,
            quarterMile = 12.9, quarterMileKmh = 180.0, topSpeedKmh = 182.0, accelBias = 0.0)
        val c = RunComparison.of(result, Timeslip(sixtyFeet = 2.0, quarterMile = 12.8, quarterMileSpeed = 112.0, speedUnit = SpeedUnit.mph))
        assertEquals(listOf(ValidationMark.sixtyFeet, ValidationMark.quarterMile, ValidationMark.quarterMileSpeed), c.marks.map { it.mark })
        assertEquals(0.05, c.marks[0].error, 1e-9)
        assertEquals(0.1, c.marks[1].error, 1e-9)
        assertEquals(112 * 1.609344, c.marks[2].reference, 1e-9)
        assertEquals(180 - 112 * 1.609344, c.marks[2].error, 1e-9)
    }

    @Test fun summaryStatistics() {
        fun result(sixty: Double) = RunResult(launchTime = 0.0, sixtyFeet = sixty, topSpeedKmh = 0.0, accelBias = 0.0)
        val slip = Timeslip(sixtyFeet = 2.0)
        val report = ValidationReport.of(listOf(
            ValidationInput("a", result(2.1), slip), ValidationInput("b", result(1.95), slip),
            ValidationInput("c", null, slip), ValidationInput("d", result(2.05), slip),
        ))
        assertEquals(listOf("c"), report.skipped)
        assertEquals(1, report.summaries.size)
        val s = report.summaries.first()
        assertEquals(3, s.count)
        assertEquals((0.1 - 0.05 + 0.05) / 3, s.bias, 1e-9)
        assertEquals(0.2 / 3, s.meanAbsolute, 1e-9)
        assertEquals(sqrt((0.01 + 0.0025 + 0.0025) / 3), s.rms, 1e-9)
        assertEquals(0.1, s.worst, 1e-9)
        assertTrue(report.passed)
        assertFalse(ValidationReport.of(listOf(ValidationInput("a", result(2.1), slip)), ValidationTargets(sixtyFeet = 0.05)).passed)
    }

    @Test fun emptyReportDoesNotPass() {
        assertFalse(ValidationReport.of(emptyList()).passed)
    }

    @Test fun demoRunAgainstPerfectTimeslip() {
        val dir = resourceDir()
        val report = ValidationSet.decode(File(dir, "validation.json").readText()).report(dir)
        assertTrue(report.skipped.isEmpty())
        assertEquals(ValidationMark.entries.size, report.summaries.size)
        report.summaries.forEach { assertTrue(it.passed, "${it.mark}: ${it.rms}") }
        assertTrue(report.passed)
    }

    /** The same errors as TorkeKit for iOS, from `validation-expected.json`. */
    @Test fun validationFixture() {
        val dir = resourceDir()
        val expected = Json.parseToJsonElement(File(dir, "validation-expected.json").readText()).jsonObject
        val errors = expected["errors"]!!.jsonObject
        val report = ValidationSet.decode(File(dir, "validation.json").readText()).report(dir)
        report.runs[0].marks.forEach { assertEquals(errors[it.mark.name]!!.jsonPrimitive.double, it.error, 1e-6, it.mark.name) }
        assertEquals(expected["passed"]!!.jsonPrimitive.boolean, report.passed)
    }

    @Test fun timeslipDefaultsToKmh() {
        val set = ValidationSet.decode("""{"format":"torke.validation","version":1,"runs":[{"run":"x","timeslip":{"quarterMile":13.1,"quarterMileSpeed":170}}]}""")
        val slip = set.runs[0].timeslip
        assertEquals(SpeedUnit.kmh, slip.speedUnit)
        assertEquals(170.0, slip.value(ValidationMark.quarterMileSpeed))
        assertNull(slip.value(ValidationMark.sixtyFeet))
    }

    @Test fun rejectsOtherFormats() {
        assertFailsWith<TorkeRun.FormatException> { ValidationSet.decode("""{"format":"torke.run","version":1,"runs":[]}""") }
        assertFailsWith<TorkeRun.FormatException> { ValidationSet.decode("""{"format":"torke.validation","version":2,"runs":[]}""") }
    }
}
