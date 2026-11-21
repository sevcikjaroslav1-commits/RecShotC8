package sk.vespal.recshot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Zavolá [onShake] po dvoch silných zatraseniach v priebehu ~1 sekundy. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var firstShakeAt = 0L
    private var lastFiredAt = 0L

    fun start() {
        val sm = sensorManager ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gX = event.values[0] / SensorManager.GRAVITY_EARTH
        val gY = event.values[1] / SensorManager.GRAVITY_EARTH
        val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
        if (gForce < 2.6f) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastFiredAt < 2000) return
        if (firstShakeAt != 0L && now - firstShakeAt in 150..1000) {
            firstShakeAt = 0L
            lastFiredAt = now
            onShake()
        } else if (firstShakeAt == 0L || now - firstShakeAt > 1000) {
            firstShakeAt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
