package today.torke.kit.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.Vec3
import today.torke.kit.input.RunDataProvider
import today.torke.kit.input.SensorSample

/**
 * The phone's own sensors: linear acceleration and gravity at 100 Hz, and the GPS at its top
 * rate. Both use the `elapsedRealtime` clock.
 *
 * The app asks for `ACCESS_FINE_LOCATION` before collecting. To keep recording with the
 * screen off, run the collection inside a foreground service of type `location`.
 */
public class PhoneSensorProvider(context: Context) : RunDataProvider {
    private val app = context.applicationContext
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val location = app.getSystemService(LocationManager::class.java)

    public val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    public val hasMotionSensors: Boolean
        get() = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null && sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) != null

    @SuppressLint("MissingPermission")
    override fun samples(): Flow<SensorSample> = callbackFlow {
        var gravity: Vec3? = null
        val motionListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                if (event.sensor.type == Sensor.TYPE_GRAVITY) { gravity = v; return }
                val g = gravity ?: return
                trySend(SensorSample.Motion(RawMotionSample(event.timestamp / 1e9, v, g)))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val gpsListener = LocationListener { fix: Location ->
            val speedAccuracy = when {
                !fix.hasSpeed() -> GPSSample.NO_SPEED
                fix.hasSpeedAccuracy() -> fix.speedAccuracyMetersPerSecond.toDouble()
                else -> 1.0
            }
            trySendBlocking(SensorSample.Gps(GPSSample(
                t = fix.elapsedRealtimeNanos / 1e9,
                speed = if (fix.hasSpeed()) fix.speed.toDouble() else 0.0,
                speedAccuracy = speedAccuracy,
                latitude = fix.latitude,
                longitude = fix.longitude,
                horizontalAccuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
            )))
        }

        val period = 10_000 // µs: 100 Hz
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(motionListener, it, period) }
        sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensors.registerListener(motionListener, it, period) }
        if (hasLocationPermission) {
            runCatching { location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) }
        }

        awaitClose {
            sensors.unregisterListener(motionListener)
            location.removeUpdates(gpsListener)
        }
    }
}
