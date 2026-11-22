package today.torke.kit

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import today.torke.kit.engine.*

class AxisCalibrationTest {
    /** Rotates a vector by Euler angles (radians). */
    private fun rotate(v: Vec3, yaw: Double, pitch: Double, roll: Double): Vec3 {
        var r = v
        r = Vec3(r.x * cos(yaw) - r.y * sin(yaw), r.x * sin(yaw) + r.y * cos(yaw), r.z)
        r = Vec3(r.x * cos(pitch) + r.z * sin(pitch), r.y, -r.x * sin(pitch) + r.z * cos(pitch))
        r = Vec3(r.x, r.y * cos(roll) - r.z * sin(roll), r.y * sin(roll) + r.z * cos(roll))
        return r
    }

    @Test fun recoversForwardAccelerationFromATiltedPhone() {
        // In the car: forward = x, gravity = -z. The phone is turned and tilted.
        val (yaw, pitch, roll) = Triple(1.1, -0.6, 0.35)
        val profile = { t: Double -> if (t < 1) 0.0 else 5 * exp(-(t - 1) / 8) }
        val times = (0 until 600).map { it / 100.0 }
        val raw = times.map { t ->
            RawMotionSample(
                t,
                rotate(Vec3(profile(t), 0.2 * sin(t * 3), 0.0), yaw, pitch, roll),
                rotate(Vec3(0.0, 0.0, -1.0), yaw, pitch, roll),
            )
        }
        AxisCalibration.forwardAccel(raw).zip(times).filter { it.second > 1.2 }.forEach { (s, t) ->
            assertEquals(profile(t), s.accel, absoluteTolerance = 0.25)
        }
    }

    @Test fun withoutALaunchEverythingIsZero() {
        val raw = (0 until 100).map { RawMotionSample(it / 100.0, Vec3(0.05, 0.0, 0.0), Vec3(0.0, 0.0, -1.0)) }
        assertTrue(AxisCalibration.forwardAccel(raw).all { it.accel == 0.0 })
    }
}
