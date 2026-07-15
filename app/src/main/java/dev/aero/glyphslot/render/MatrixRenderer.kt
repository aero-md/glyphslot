package dev.aero.glyphslot.render

import dev.aero.glyphslot.engine.Reels
import dev.aero.glyphslot.engine.SlotEngine
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Masque circulaire pré-calculé du disque 25×25. */
internal object Disc {
    val inside = BooleanArray(Reels.SIZE * Reels.SIZE)
    val dist = FloatArray(Reels.SIZE * Reels.SIZE)
    val cells: IntArray

    init {
        val list = ArrayList<Int>()
        for (y in 0 until Reels.SIZE) {
            for (x in 0 until Reels.SIZE) {
                val i = y * Reels.SIZE + x
                val d = hypot((x - Reels.CENTER).toDouble(), (y - Reels.CENTER).toDouble())
                dist[i] = d.toFloat()
                if (d <= Reels.RADIUS) {
                    inside[i] = true
                    list.add(i)
                }
            }
        }
        cells = list.toIntArray()
    }
}

/**
 * Rend un Snapshot du moteur en frame de luminosités : IntArray(625), valeurs 0..255,
 * 0 hors du disque. Aucune dépendance Android — testable et partagé service/préview.
 */
class MatrixRenderer(private val random: Random = Random.Default) {

    private val effects = Effects(random)
    private val grid = FloatArray(Reels.SIZE * Reels.SIZE)
    private val shifted = FloatArray(Reels.SIZE * Reels.SIZE)
    private val out = IntArray(Reels.SIZE * Reels.SIZE)

    /** À appeler quand le moteur émet SpinFinished — seed des effets aléatoires. */
    fun onResult(type: SlotEngine.ResultType) {
        if (type == SlotEngine.ResultType.JACKPOT) effects.seedJackpot()
    }

    fun render(snap: SlotEngine.Snapshot): IntArray {
        grid.fill(0f)
        val result = snap.resultType
        val te = snap.resultTime

        // modulation des symboles sur la payline pendant win/jackpot
        val payMult =
            if (result != null && result != SlotEngine.ResultType.LOSE) effects.payMult(te)
            else 1.0

        // rouleaux : bande bouclée, symboles hors payline atténués ×0,2
        for (i in 0 until 3) {
            val off = snap.offsets[i].roundToInt()
            for (wy in 1 until Reels.SIZE - 1) {
                val stripRow = Reels.mod(wy - Reels.PAY_TOP - off, Reels.STRIP_LEN)
                val sym = stripRow / Reels.SYM_H
                val r = stripRow % Reels.SYM_H
                if (r >= 7) continue
                val inPay = wy in Reels.PAY_TOP..Reels.PAY_BOT
                val dim = if (inPay) 1.0 else 0.2
                val row = Sprites.STRIP[sym][r]
                for (x in 0 until 7) {
                    val b = row[x]
                    if (b == 0f) continue
                    grid[wy * Reels.SIZE + Reels.COLS[i] + x] =
                        (b * dim * (if (inPay) payMult else 1.0)).toFloat()
                }
            }
        }

        // repères payline sur les bords
        marker(Reels.PAY_TOP, 0)
        marker(Reels.PAY_BOT, 0)
        marker(Reels.PAY_TOP, Reels.SIZE - 1)
        marker(Reels.PAY_BOT, Reels.SIZE - 1)

        // effets de résultat
        when (result) {
            SlotEngine.ResultType.WIN -> effects.applyWin(grid, te)
            SlotEngine.ResultType.JACKPOT -> effects.applyJackpot(grid, te)
            else -> Unit
        }

        // shake : translation entière de la grille (fort = toujours, léger = 1 frame sur 2)
        var src = grid
        if (result == SlotEngine.ResultType.JACKPOT) {
            val amp = effects.shakeAmp(te)
            if (amp == 2 || (amp == 1 && random.nextBoolean())) {
                shift(random.nextInt(3) - 1, random.nextInt(3) - 1)
                src = shifted
            }
        }

        // sortie 0..255, masque circulaire
        for (i in out.indices) {
            out[i] = if (Disc.inside[i]) (min(src[i], 1f) * 255).roundToInt() else 0
        }
        return out
    }

    private fun marker(y: Int, x: Int) {
        val i = y * Reels.SIZE + x
        if (grid[i] < 0.3f) grid[i] = 0.3f
    }

    private fun shift(dx: Int, dy: Int) {
        shifted.fill(0f)
        for (y in 0 until Reels.SIZE) {
            val sy = y - dy
            if (sy < 0 || sy >= Reels.SIZE) continue
            for (x in 0 until Reels.SIZE) {
                val sx = x - dx
                if (sx < 0 || sx >= Reels.SIZE) continue
                shifted[y * Reels.SIZE + x] = grid[sy * Reels.SIZE + sx]
            }
        }
    }
}
