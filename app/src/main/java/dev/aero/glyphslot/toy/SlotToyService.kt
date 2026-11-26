package dev.aero.glyphslot.toy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nothing.ketchum.GlyphMatrixManager
import dev.aero.glyphslot.engine.Game
import dev.aero.glyphslot.engine.Reels
import dev.aero.glyphslot.engine.RouletteEngine
import dev.aero.glyphslot.engine.SlotEngine
import dev.aero.glyphslot.engine.Table
import dev.aero.glyphslot.render.MatrixRenderer
import dev.aero.glyphslot.render.RouletteRenderer

/**
 * Glyph Toy « machine à sous », et sa roulette — seul module dépendant du
 * GlyphMatrixSDK. Boucle de rendu Handler ~30 fps.
 *
 * - **secousse** du téléphone → lance le jeu à l'écran ;
 * - **appui long** sur le Glyph Button (event « change ») → change de jeu.
 *
 * Le jeu choisi est retenu d'une session à l'autre : on retrouve la roulette si
 * on l'avait laissée là.
 */
class SlotToyService : GlyphMatrixService("GlyphSlot") {

    private lateinit var table: Table
    private val slotRenderer = MatrixRenderer()
    private val rouletteRenderer = RouletteRenderer()
    private val frameHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var shaker: Shaker? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

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
        val saved = prefs.getString(KEY_GAME, null)
        table = Table(initial = Game.entries.firstOrNull { it.name == saved } ?: Game.SLOT)
        shaker = Shaker(this) { onShake() }.also { it.start() }
        running = true
        frameHandler.post(tick)
    }

    override fun performOnServiceDisconnected(context: Context) {
        // stop boucle + capteur + extinction matrice (le wrapper appelle turnOff après)
        running = false
        frameHandler.removeCallbacksAndMessages(null)
        shaker?.stop()
        shaker = null
    }

    private fun onShake() {
        if (!running) return
        table.launch(now())
    }

    override fun onTouchPointLongPress() {
        if (!running) return
        if (table.switch(now())) {
            prefs.edit().putString(KEY_GAME, table.game.name).apply()
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            // refusé : le jeu tourne encore — un double tick plutôt que rien
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }

    private fun now(): Double = System.nanoTime() / 1e9

    private fun renderFrame() {
        val now = now()
        val frame = when (table.game) {
            Game.SLOT -> {
                val snap = table.slot.update(now)
                table.slot.drainEvents().forEach { handleSlot(it) }
                slotRenderer.render(snap)
            }

            Game.ROULETTE -> {
                val view = table.roulette.update(now)
                table.roulette.drainEvents().forEach { handleRoulette(it) }
                rouletteRenderer.render(view)
            }
        }
        push(frame)
    }

    private fun handleSlot(event: SlotEngine.Event) {
        when (event) {
            is SlotEngine.Event.ReelStopped ->
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

            is SlotEngine.Event.SpinFinished -> {
                slotRenderer.onResult(event.result)
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

    private fun handleRoulette(event: RouletteEngine.Event) {
        when (event) {
            // un tick par rebond : on sent la bille sauter de case en case
            is RouletteEngine.Event.Bounce ->
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

            is RouletteEngine.Event.Landed ->
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        }
    }

    private fun push(brightness: IntArray) {
        // setMatrixFrame attend des luminosités 0..4095 : les renderers sortent du
        // 0..255, on applique le même ×16 que GlyphMatrixUtils (BRIGHTNESS_MULTIPLIER)
        for (i in frameBuf.indices) frameBuf[i] = brightness[i] * BRIGHTNESS_MULTIPLIER
        matrix?.setMatrixFrame(frameBuf)
    }

    private val frameBuf = IntArray(Reels.SIZE * Reels.SIZE)

    private companion object {
        const val FRAME_MS = 33L // ~30 fps
        const val BRIGHTNESS_MULTIPLIER = 16
        const val PREFS = "glyphslot"
        const val KEY_GAME = "game"
    }
}
