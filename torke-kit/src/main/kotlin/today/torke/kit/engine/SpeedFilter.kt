package today.torke.kit.engine

import kotlin.math.max

/**
 * Two-state Kalman filter: speed and accelerometer bias.
 *
 * The accelerometer predicts the speed at 100 Hz; the GPS (1 to 25 Hz) corrects it and, over
 * time, estimates the bias a slightly tilted mount leaves.
 */
public class SpeedFilter(
    speed: Double = 0.0,
    bias: Double = 0.0,
    private val accelNoise: Double = 0.35,
    private val biasDrift: Double = 0.02,
) {
    public var speed: Double = speed; private set
    public var bias: Double = bias; private set
    private var p00 = 0.01
    private var p01 = 0.0
    private var p11 = 0.05
    private var lastT: Double? = null

    /** Moves the filter forward with one accelerometer sample. */
    public fun predict(sample: MotionSample) {
        val last = lastT
        lastT = sample.t
        if (last == null) return
        val dt = sample.t - last
        if (dt <= 0 || dt >= 0.5) return
        speed += (sample.accel - bias) * dt
        // P = F P Fᵀ + Q, with F = [[1, -dt], [0, 1]]
        val n00 = p00 - 2 * dt * p01 + dt * dt * p11 + accelNoise * accelNoise * dt
        val n01 = p01 - dt * p11
        val n11 = p11 + biasDrift * biasDrift * dt
        p00 = n00; p01 = n01; p11 = n11
    }

    /** Corrects with a GPS speed. */
    public fun correct(gps: GPSSample) {
        val r = max(gps.speedAccuracy, 0.15)
        val s = p00 + r * r
        val k0 = p00 / s
        val k1 = p01 / s
        val innovation = gps.speed - speed
        speed += k0 * innovation
        bias += k1 * innovation
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
        speed = max(speed, 0.0)
    }
}
