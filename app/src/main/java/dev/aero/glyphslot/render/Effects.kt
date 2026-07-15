package dev.aero.glyphslot.render

import dev.aero.glyphslot.engine.Reels
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Effets de résultat écrits dans la grille 25×25 (luminosités 0..1).
 * win : pulse des symboles + anneau périphérique (~2,8 s).
 * jackpot : chorégraphie ~7,5 s en 5 phases (voir SPECS.md).
 */
class Effects(private val random: Random = Random.Default) {

    class Particle(val ang: Double, val v: Double, val life: Double)
    class Burst(val t0: Double, val cx: Double, val cy: Double, val parts: List<Particle>)
    class Twinkle(val x: Int, val y: Int, val ph: Double, val sp: Double)

    private var bursts: List<Burst> = emptyList()
    private var twinkles: List<Twinkle> = emptyList()

    /** À appeler à l'entrée en RESULT jackpot : tire feux d'artifice et scintillements. */
    fun seedJackpot() {
        bursts = List(7) { i ->
            Burst(
                t0 = 3.9 + i * 0.22 + random.nextDouble() * 0.12,
                cx = Reels.CENTER + (random.nextDouble() * 2 - 1) * 6,
                cy = Reels.CENTER + (random.nextDouble() * 2 - 1) * 6,
                parts = List(12) {
                    Particle(
                        ang = random.nextDouble() * 2 * PI,
                        v = 4 + random.nextDouble() * 5,
                        life = 0.7 + random.nextDouble() * 0.4,
                    )
                },
            )
        }
        twinkles = List(36) {
            val i = Disc.cells[random.nextInt(Disc.cells.size)]
            Twinkle(
                x = i % Reels.SIZE,
                y = i / Reels.SIZE,
                ph = random.nextDouble() * 2 * PI,
                sp = 5 + random.nextDouble() * 4,
            )
        }
    }

    /** Modulation de luminosité des symboles sur la payline pendant win/jackpot. */
    fun payMult(te: Double): Double = 0.3 + 0.7 * (0.5 + 0.5 * sin(te * PI * 6))

    /** win ×3 : anneau périphérique pulsé (d > 11,3). */
    fun applyWin(grid: FloatArray, te: Double) {
        val ring = (0.5 * (0.5 + 0.5 * sin(te * PI * 6))).toFloat()
        for (i in Disc.cells) {
            if (Disc.dist[i] > 11.3f) grid[i] = max(grid[i], ring)
        }
    }

    /** jackpot 777 : chorégraphie complète pilotée par te (s depuis l'entrée en RESULT). */
    fun applyJackpot(grid: FloatArray, te: Double) {
        // Phase 1 (0–0,6 s) : triple strobe plein disque, flashs décroissants
        for (t0 in STROBES) {
            val age = te - t0
            if (age >= 0 && age < 0.14) {
                val f = (0.95 * (1 - age / 0.14)).toFloat()
                for (i in Disc.cells) grid[i] = max(grid[i], f)
            }
        }

        // Phase 2 (0,25–1,3 s) : 3 ondes de choc concentriques (r = 14·t, largeur 1 px)
        for (tw in WAVES) {
            val age = te - tw
            if (age <= 0 || age > 0.9) continue
            val r = (age * 14).toFloat()
            val fade = (1 - age / 0.9).toFloat()
            for (i in Disc.cells) {
                val band = abs(Disc.dist[i] - r)
                if (band < 1f) grid[i] = max(grid[i], (1 - band) * fade)
            }
        }

        // Phase 3 (1,3–4,1 s) : bandeau JACKPOT sur la payline, défilement droite → gauche
        if (te > 1.3 && te < 4.1) {
            grid.fill(0f)
            val speed = (Sprites.BANNER_W + Reels.SIZE) / 2.8
            val scroll = (te - 1.3) * speed
            for (v in 0 until 7) {
                val y = Reels.PAY_TOP + v
                for (x in 0 until Reels.SIZE) {
                    val i = y * Reels.SIZE + x
                    if (!Disc.inside[i]) continue
                    val bc = (x - Reels.SIZE + scroll).toInt()
                    if (bc < 0 || bc >= Sprites.BANNER_W) continue
                    if (Sprites.BANNER[v][bc]) grid[i] = 1f
                }
            }
        }

        // Phase 4 (3,9–5,4 s) : feux d'artifice, 12 particules avec gravité (2,5 cell/s²)
        for (bu in bursts) {
            val age = te - bu.t0
            if (age <= 0) continue
            if (age < 0.12) {
                val px = bu.cx.roundToInt()
                val py = bu.cy.roundToInt()
                if (px in 0 until Reels.SIZE && py in 0 until Reels.SIZE) {
                    grid[py * Reels.SIZE + px] = 1f
                }
            }
            for (p in bu.parts) {
                if (age > p.life) continue
                val px = (bu.cx + cos(p.ang) * p.v * age).roundToInt()
                val py = (bu.cy + sin(p.ang) * p.v * age + 2.5 * age * age).roundToInt()
                if (px !in 0 until Reels.SIZE || py !in 0 until Reels.SIZE) continue
                val i = py * Reels.SIZE + px
                if (!Disc.inside[i]) continue
                grid[i] = max(grid[i], (1 - age / p.life).toFloat())
            }
        }

        // Phase 5 (5,4–7,3 s) : SEPT GÉANT — zoom 1 → 2,7 (ease-out 0,45 s), pulse, fade-out
        if (te > 5.4) {
            grid.fill(0f)
            val e = min((te - 5.4) / 0.45, 1.0)
            val s = 1 + 1.7 * (1 - (1 - e).pow(3))
            val fade = if (te > 7.0) max(0.0, 1 - (te - 7.0) / 0.3) else 1.0
            val pulse = 0.6 + 0.4 * sin((te - 5.4) * 12)
            for (i in Disc.cells) {
                val x = i % Reels.SIZE
                val y = i / Reels.SIZE
                val u = ((x - Reels.CENTER) / s + 3).roundToInt()
                val v = ((y - Reels.CENTER) / s + 3).roundToInt()
                if (u !in 0..6 || v !in 0..6) continue
                val b = Sprites.STRIP[Sprites.SEVEN][v][u]
                if (b > 0f) grid[i] = (b * pulse * fade).toFloat()
            }
        }

        // Scintillement continu (1,0–7,2 s) : 36 LEDs aléatoires
        if (te > 1.0 && te < 7.2) {
            for (tw in twinkles) {
                val b = (max(0.0, sin(te * tw.sp + tw.ph)).pow(3) * 0.35).toFloat()
                val i = tw.y * Reels.SIZE + tw.x
                grid[i] = max(grid[i], b)
            }
        }
    }

    /** Amplitude de shake : 2 = fort (strobe), 1 = léger (bursts), 0 = aucun. */
    fun shakeAmp(te: Double): Int = when {
        te < 0.6 -> 2
        bursts.any { te - it.t0 > 0 && te - it.t0 < 0.22 } -> 1
        else -> 0
    }

    private companion object {
        val STROBES = doubleArrayOf(0.0, 0.2, 0.4)
        val WAVES = doubleArrayOf(0.25, 0.55, 0.85)
    }
}
