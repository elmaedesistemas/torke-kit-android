package today.torke.kit.engine

/**
 * Turns the phone's readings into the vehicle's forward acceleration, however the phone sits
 * in its mount.
 *
 * It keeps the horizontal part (perpendicular to gravity) and takes as "forward" the
 * direction of the first strong acceleration: the launch.
 */
public object AxisCalibration {
    private const val STRONG_ACCEL = 1.5
    private const val LEARN_WINDOW = 0.5

    /** Forward acceleration for every sample. All zeros if there is no launch to learn from. */
    public fun forwardAccel(raw: List<RawMotionSample>): List<MotionSample> {
        val horizontal = raw.map { it.t to horizontalPart(it) }
        val forward = forwardAxis(horizontal) ?: return raw.map { MotionSample(it.t, 0.0) }
        return horizontal.map { (t, h) -> MotionSample(t, h dot forward) }
    }

    /** Horizontal acceleration in any direction (m/s²): detects movement before "forward" is known. */
    public fun horizontalMagnitude(s: RawMotionSample): Double = horizontalPart(s).length

    internal fun horizontalPart(s: RawMotionSample): Vec3 {
        val g = s.gravity.normalized()
        return s.user - g * (s.user dot g)
    }

    internal fun forwardAxis(horizontal: List<Pair<Double, Vec3>>): Vec3? {
        val start = horizontal.firstOrNull { it.second.length > STRONG_ACCEL }?.first ?: return null
        val sum = horizontal
            .filter { it.first >= start && it.first < start + LEARN_WINDOW && it.second.length > STRONG_ACCEL * 0.5 }
            .fold(Vec3.ZERO) { acc, p -> acc + p.second }
        return if (sum.length > 0) sum.normalized() else null
    }
}
