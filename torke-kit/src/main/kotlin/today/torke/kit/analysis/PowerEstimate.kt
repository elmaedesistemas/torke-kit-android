package today.torke.kit.analysis

import kotlin.math.pow
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.RunResult
import today.torke.kit.engine.TracePoint

/**
 * Power estimates from a run. `massKg` is the vehicle with the driver. These are
 * approximations (about ±15 %): good to compare changes on the same car.
 */
public object PowerEstimate {
    /** Watts in a mechanical horsepower. */
    public const val WATTS_PER_HP: Double = 745.7

    /** Flywheel horsepower from the 1/4 mile trap speed (Hale's formula). */
    public fun fromTrapSpeed(massKg: Double, quarterMileKmh: Double): Double =
        massKg * 2.204_62 * (quarterMileKmh / 1.609_344 / 234).pow(3)

    /** Flywheel horsepower from the 1/4 mile elapsed time (Hale's formula). Reads low on cars that spin at the launch. */
    public fun fromElapsedTime(massKg: Double, quarterMile: Double): Double =
        massKg * 2.204_62 * (5.825 / quarterMile).pow(3)

    /**
     * Power at the wheels along the run: mass × acceleration × speed, plus the air drag and the
     * rolling resistance it had to beat. [dragArea] is drag coefficient × frontal area (m²),
     * about 0.6 for a sedan; [rollingResistance] about 0.012 on drag radials, 0.015 on street tires.
     */
    public fun wheelPower(
        result: RunResult,
        massKg: Double,
        dragArea: Double = 0.7,
        rollingResistance: Double = 0.015,
        airDensity: Double = 1.2,
    ): List<PowerPoint> {
        // Speed every 0.05 s, acceleration over ±0.25 s: the GPS corrections make small steps
        // in the speed that a point-to-point derivative would turn into spikes.
        val grid = resample(result.trace, 0.05)
        if (grid.size <= 10) return emptyList()
        val half = 5
        return (half until grid.size - half).map { i ->
            val a = (grid[i + half].speed - grid[i - half].speed) / (grid[i + half].t - grid[i - half].t)
            val v = grid[i].speed
            val force = massKg * a + 0.5 * airDensity * dragArea * v * v + rollingResistance * massKg * DragDistance.G
            PowerPoint(grid[i].t, v * 3.6, maxOf(0.0, force * v))
        }
    }

    /** The peak of [wheelPower] in hp. */
    public fun peakWheelHp(result: RunResult, massKg: Double, dragArea: Double = 0.7, rollingResistance: Double = 0.015, airDensity: Double = 1.2): Double? =
        wheelPower(result, massKg, dragArea, rollingResistance, airDensity).maxOfOrNull { it.hp }

    internal fun resample(trace: List<TracePoint>, step: Double): List<TracePoint> {
        val end = trace.lastOrNull()?.t ?: return emptyList()
        if (trace.size < 2) return emptyList()
        val out = ArrayList<TracePoint>()
        var j = 1
        var k = 0
        while (true) {
            val t = k * step
            if (t > end) break
            while (j < trace.size - 1 && trace[j].t < t) j++
            val a = trace[j - 1]
            val b = trace[j]
            val f = if (b.t == a.t) 0.0 else ((t - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0)
            out.add(TracePoint(t, a.speed + (b.speed - a.speed) * f, a.distance + (b.distance - a.distance) * f))
            k++
        }
        return out
    }
}

/** Power at one point of the run. */
public data class PowerPoint(val t: Double, val speedKmh: Double, val watts: Double) {
    val hp: Double get() = watts / PowerEstimate.WATTS_PER_HP
}
