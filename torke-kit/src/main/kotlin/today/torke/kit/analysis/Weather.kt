package today.torke.kit.analysis

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import today.torke.kit.engine.RunResult

/**
 * Weather at the strip. Times and speeds can be corrected to standard air (SAE J1349: 25 °C,
 * 990 hPa of dry air) so runs on different days compare fairly.
 */
@Serializable
public data class Weather(
    val temperatureC: Double,
    /** Relative humidity, 0 to 100. */
    val humidity: Double,
    /** Station pressure (not sea-level), hPa. */
    val pressureHpa: Double,
) {
    /** Water vapor pressure, hPa (Magnus formula). */
    val vaporPressureHpa: Double
        get() = 6.1094 * exp(17.625 * temperatureC / (temperatureC + 243.04)) * humidity.coerceIn(0.0, 100.0) / 100

    /** Pressure of the dry air alone, hPa. */
    val dryPressureHpa: Double get() = pressureHpa - vaporPressureHpa

    /** Air density, kg/m³. */
    val airDensity: Double
        get() {
            val t = temperatureC + 273.15
            return dryPressureHpa * 100 / (287.058 * t) + vaporPressureHpa * 100 / (461.495 * t)
        }

    /** The altitude of the standard atmosphere with the same air density, in meters. Higher means thinner air and slower runs. */
    val densityAltitude: Double get() = 44_330.8 * (1 - (airDensity / 1.225).pow(0.234_969))

    /** SAE J1349 power correction: above 1, the air cost power and the corrected times are quicker than the measured ones. */
    val correctionFactor: Double get() = 1.18 * (990 / dryPressureHpa) * sqrt((temperatureC + 273.15) / 298.15) - 0.18

    /** A time corrected to standard air. Elapsed times go with the cube root of power. */
    public fun correctedTime(time: Double): Double = time * correctionFactor.pow(-1.0 / 3)

    /** A speed corrected to standard air. */
    public fun correctedSpeed(speed: Double): Double = speed * correctionFactor.pow(1.0 / 3)
}

/** The times and speeds corrected to standard air. The trace stays as measured. */
public fun RunResult.corrected(weather: Weather): RunResult = copy(
    zeroTo100 = zeroTo100?.let(weather::correctedTime),
    sixtyFeet = sixtyFeet?.let(weather::correctedTime),
    eighthMile = eighthMile?.let(weather::correctedTime),
    quarterMile = quarterMile?.let(weather::correctedTime),
    eighthMileKmh = eighthMileKmh?.let(weather::correctedSpeed),
    quarterMileKmh = quarterMileKmh?.let(weather::correctedSpeed),
    topSpeedKmh = weather.correctedSpeed(topSpeedKmh),
)
