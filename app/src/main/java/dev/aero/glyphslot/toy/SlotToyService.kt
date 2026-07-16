package dev.aero.glyphslot.toy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nothing.ketchum.GlyphMatrixManager
import dev.aero.glyphslot.engine.Reels
import dev.aero.glyphslot.engine.SlotEngine
import dev.aero.glyphslot.render.MatrixRenderer

/**
 * Glyph Toy « machine à sous » — seul module dépendant du GlyphMatrixSDK.
 * Boucle de rendu Handler ~30 fps ; appui long (event « change ») = spin.
 */
class SlotToyService : GlyphMatrixService("GlyphSlot") {

    private val engine = SlotEngine()
    private val renderer = MatrixRenderer()
    private val frameHandler = Handler(Looper.getMainLooper())
    private var running = false

    private val vibrator: Vibrator by lazy {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            renderFrame()
            frameHandler.postDelayed(this, FRAME_MS)
        }
    }

    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager,
    ) {
        running = true
        frameHandler.post(tick)
    }

    override fun performOnServiceDisconnected(context: Context) {
        // stop boucle + extinction matrice (le wrapper appelle turnOff après)
        running = false
        frameHandler.removeCallbacksAndMessages(null)
    }

    override fun onTouchPointLongPress() {
        engine.spin(now())
    }

    private fun now(): Double = System.nanoTime() / 1e9

    private fun renderFrame() {
        val snap = engine.update(now())
        engine.drainEvents().forEach { handleEvent(it) }
        push(renderer.render(snap))
    }

    private fun handleEvent(event: SlotEngine.Event) {
        when (event) {
            is SlotEngine.Event.ReelStopped ->
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

            is SlotEngine.Event.SpinFinished -> {
                renderer.onResult(event.result)
                when (event.result) {
                    SlotEngine.ResultType.JACKPOT -> vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 90, 60, 90, 60, 220, 80, 350), -1
                        )
                    )

                    SlotEngine.ResultType.WIN -> vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 60, 50, 120), -1)
                    )

                    SlotEngine.ResultType.LOSE -> Unit
                }
            }

            SlotEngine.Event.ResultEnded -> Unit
        }
    }

    private fun push(brightness: IntArray) {
        // setMatrixFrame attend des luminosités 0..4095 : le renderer sort du 0..255,
        // on applique le même ×16 que GlyphMatrixUtils (BRIGHTNESS_MULTIPLIER)
        for (i in frameBuf.indices) frameBuf[i] = brightness[i] * BRIGHTNESS_MULTIPLIER
        matrix?.setMatrixFrame(frameBuf)
    }

    private val frameBuf = IntArray(Reels.SIZE * Reels.SIZE)

    private companion object {
        const val FRAME_MS = 33L // ~30 fps
        const val BRIGHTNESS_MULTIPLIER = 16
    }
}
