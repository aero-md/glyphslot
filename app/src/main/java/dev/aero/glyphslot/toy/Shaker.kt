package dev.aero.glyphslot.toy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * La secousse — ce qui lance la machine à sous comme la roulette.
 *
 * Un passe-haut sur la norme de l'accélération, un seuil, un temps mort. La
 * gravité est soustraite par une moyenne glissante : sans ça un téléphone posé
 * mesure déjà 9,81 et n'importe quel seuil utile serait franchi en permanence.
 *
 * Repris du dé de Haloglyph (`toy/dice/.../Shaker.kt`), mêmes réglages — un
 * geste qui jette le dé lance aussi la bille.
 *
 * Le capteur **réveillant** est demandé d'abord : un Glyph Toy s'utilise
 * téléphone retourné, écran éteint, et un capteur ordinaire peut livrer ses
 * mesures en paquet au réveil suivant. Le Phone (3) n'en a pas (relevé au
 * `dumpsys sensorservice`) et c'est le repli qui tourne — ce qui marche parce
 * que Glyph Interface tient le service lié, donc le processus éveillé, tant que
 * le toy est affiché.
 */
class Shaker(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)

    private val sensor: Sensor? = sensors?.let {
        it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    /** Accélération nette lissée, m/s². */
    private var level = 0f

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var settled = false

    private var lastShake = 0L
    private var listening = false

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        if (listening) return
        level = 0f
        settled = false
        listening = sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rx = event.values[0]
        val ry = event.values[1]
        val rz = event.values[2]

        if (!settled) {
            // Première mesure : la gravité, c'est elle. Partir de zéro ferait
            // passer le repos initial pour une secousse de 9,81 m/s².
            gravityX = rx
            gravityY = ry
            gravityZ = rz
            settled = true
            return
        }

        gravityX += (rx - gravityX) * GRAVITY_SMOOTH
        gravityY += (ry - gravityY) * GRAVITY_SMOOTH
        gravityZ += (rz - gravityZ) * GRAVITY_SMOOTH

        val ax = rx - gravityX
        val ay = ry - gravityY
        val az = rz - gravityZ
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        level += (magnitude - level) * SMOOTH

        if (level <= THRESHOLD) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShake < REFRACTORY_MS) return
        lastShake = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Seuil, en m/s² d'accélération nette. Un geste franc, pas une marche. */
        const val THRESHOLD = 14f

        /** Temps mort après une secousse retenue : un geste, pas une rafale. */
        const val REFRACTORY_MS = 550L

        const val SMOOTH = 0.4f
        const val GRAVITY_SMOOTH = 0.06f
    }
}
