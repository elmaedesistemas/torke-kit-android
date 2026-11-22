package today.torke.kit.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * @param fullTracking On Android 12 and later, keeps the GNSS chip tracking every satellite
 *   without power-saving gaps while samples are collected. Steadier fixes, a bit more battery.
 */
public class PhoneSensorProvider(context: Context, private val fullTracking: Boolean = true) : RunDataProvider {
    private val app = context.applicationContext
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val location = app.getSystemService(LocationManager::class.java)

    public val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val _gnss = MutableStateFlow<GnssInfo?>(null)

    /** Satellites and signal strength while samples are collected; null before the first report. */
    public val gnss: StateFlow<GnssInfo?> = _gnss.asStateFlow()

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
                altitude = if (fix.hasAltitude()) fix.altitude else null,
            )))
        }

        val period = 10_000 // µs: 100 Hz
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(motionListener, it, period) }
        sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensors.registerListener(motionListener, it, period) }
        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                _gnss.value = GnssInfo.of(status)
            }
        }
        // Full tracking needs a measurements listener; the measurements themselves are not used
        val measurements = if (Build.VERSION.SDK_INT >= 31) object : GnssMeasurementsEvent.Callback() {} else null
        if (hasLocationPermission) {
            // minTime 0: every fix the chip produces, at its top rate
            runCatching { location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper()) }
            runCatching { location.registerGnssStatusCallback(statusCallback, Handler(Looper.getMainLooper())) }
            if (fullTracking && Build.VERSION.SDK_INT >= 31 && measurements != null) {
                runCatching {
                    location.registerGnssMeasurementsCallback(
                        GnssMeasurementRequest.Builder().setFullTracking(true).build(),
                        ContextCompat.getMainExecutor(app),
                        measurements,
                    )
                }
            }
        }

        awaitClose {
            sensors.unregisterListener(motionListener)
            location.removeUpdates(gpsListener)
            location.unregisterGnssStatusCallback(statusCallback)
            if (measurements != null && Build.VERSION.SDK_INT >= 31) location.unregisterGnssMeasurementsCallback(measurements)
        }
    }
}

/**
 * What the GNSS chip sees: satellites used in the fix, satellites in view and the mean signal
 * strength (C/N0, dB-Hz) of the ones used. Under open sky a phone uses 15 or more satellites at
 * 30–40 dB-Hz; few satellites or weak signal mean a less accurate speed.
 */
public data class GnssInfo(val satellitesInFix: Int, val satellitesVisible: Int, val meanCn0: Double?) {
    public companion object {
        internal fun of(status: GnssStatus): GnssInfo {
            val used = (0 until status.satelliteCount).filter { status.usedInFix(it) }
            return GnssInfo(
                satellitesInFix = used.size,
                satellitesVisible = status.satelliteCount,
                meanCn0 = if (used.isEmpty()) null else used.map { status.getCn0DbHz(it).toDouble() }.average(),
            )
        }
    }
}
