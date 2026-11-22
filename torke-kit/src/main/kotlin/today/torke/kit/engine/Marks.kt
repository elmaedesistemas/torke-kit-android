package today.torke.kit.engine

/** Time to cover [meters] from the start line, discounting the 1-foot rollout like a dragstrip. Null when the trace does not reach it. */
public fun RunResult.elapsed(toDistance: Double): Double? {
    val rollout = RunAnalyzer.timeAtDistance(DragDistance.ROLLOUT, trace) ?: return null
    val t = RunAnalyzer.timeAtDistance(toDistance, trace) ?: return null
    return t - rollout
}

/** Speed (km/h) when the run passed [meters]. */
public fun RunResult.speedKmh(atDistance: Double): Double? = RunAnalyzer.speedAtDistance(atDistance, trace)?.let { it * 3.6 }

/** Time from the first movement to [kmh]. */
public fun RunResult.time(toKmh: Double): Double? = RunAnalyzer.timeAtSpeed(toKmh / 3.6, trace)

/** Time from [fromKmh] to [toKmh] during the launch, such as 100–200 km/h. */
public fun RunResult.time(fromKmh: Double, toKmh: Double): Double? {
    if (toKmh <= fromKmh) return null
    val a = time(fromKmh) ?: return null
    val b = time(toKmh) ?: return null
    return b - a
}

/** 0 to 60 mph, from the first movement. */
public val RunResult.zeroTo60mph: Double? get() = RunAnalyzer.timeAtSpeed(DragDistance.SPEED_60_MPH, trace)

/** 1000 feet. */
public val RunResult.thousandFeet: Double? get() = elapsed(DragDistance.THOUSAND_FEET)

/** 1/2 mile. Analyze with a `stopDistance` past it to get one. */
public val RunResult.halfMile: Double? get() = elapsed(DragDistance.HALF_MILE)
