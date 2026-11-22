package today.torke.kit.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's barometer, for [today.torke.kit.analysis.Weather]: it reads the station pressure,
 * the one the SAE correction needs, right where the car is. Most mid and high range phones
 * have one; [isAvailable] says whether this one does.
 */
public class PhoneBarometer(context: Context) {
    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)

    public val isAvailable: Boolean get() = sensor != null

    /** The station pressure in hPa, averaged over [readings] samples; null without a barometer or after 3 s. */
    public suspend fun stationPressureHpa(readings: Int = 10): Double? {
        val barometer = sensor ?: return null
        return withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { cont ->
                val values = mutableListOf<Float>()
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        values += event.values[0]
                        if (values.size >= readings && cont.isActive) {
                            sensors.unregisterListener(this)
                            cont.resume(values.average())
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensors.registerListener(listener, barometer, SensorManager.SENSOR_DELAY_GAME)
                cont.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }
    }
}
