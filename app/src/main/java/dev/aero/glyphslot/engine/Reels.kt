package dev.aero.glyphslot.engine

import kotlin.math.PI
import kotlin.math.sin

/**
 * Cinématique pure des rouleaux — aucune dépendance Android / SDK.
 * Unité : « lignes » de bande (bande bouclée de 45 lignes, défilement haut → bas).
 */
object Reels {

    /** Matrice 25×25. */
    const val SIZE = 25

    /** Centre du disque (12, 12). */
    const val CENTER = 12

    /** Masque circulaire (~489 LEDs). */
    const val RADIUS = 12.5

    /** x de départ des 3 rouleaux (7 px de large chacun). */
    val COLS = intArrayOf(1, 9, 17)

    /** Fenêtre payline : 7 lignes au centre. */
    const val PAY_TOP = 9
    const val PAY_BOT = 15

    /** 7 px de symbole + 2 px de blanc. */
    const val SYM_H = 9

    /** 5 symboles × 9 lignes, bande bouclée. */
    const val STRIP_LEN = 45
    const val SYMBOL_COUNT = 5

    /** Arrêts en cascade des rouleaux (s) — total ≈ 5 s. */
    val STOPS = doubleArrayOf(2.6, 3.8, 4.9)

    /** Durée de décélération par rouleau (s). */
    const val DECEL = 1.3

    /** Vitesse plein régime (lignes/s). */
    const val V = 34.0

    /** Armement du ressort : recul lent en sens inverse. */
    const val T_PULL = 0.45

    /** Fin de la détente → vitesse V atteinte. */
    const val T_LAUNCH = 0.75

    /** Amplitude du recul (lignes). */
    const val PULL = 5.0

    fun mod(a: Double, n: Double): Double = ((a % n) + n) % n
    fun mod(a: Int, n: Int): Int = ((a % n) + n) % n

    /** Offset (mod 45) qui aligne le symbole k sur la payline : offset ≡ -9k (mod 45). */
    fun targetOffset(k: Int): Double = mod(-(SYM_H * k).toDouble(), STRIP_LEN.toDouble())

    /** Hermite cubique avec tangentes contrôlées aux deux extrémités. */
    fun hermite(u: Double, p0: Double, p1: Double, m0: Double, m1: Double = 0.0): Double {
        val u2 = u * u
        val u3 = u2 * u
        return p0 * (2 * u3 - 3 * u2 + 1) +
            m0 * (u3 - 2 * u2 + u) +
            p1 * (-2 * u3 + 3 * u2) +
            m1 * (u3 - u2)
    }

    /** Trajectoire pré-calculée d'un rouleau pour un spin. */
    data class Plan(
        val off0: Double,
        val t1: Double,
        val tStop: Double,
        val o1: Double,
        val oF: Double,
    )

    /**
     * Pré-calcule la trajectoire pour que la décélération atterrisse pile sur le
     * symbole k à tStop. Distance de décélération d ∈ [30, 75).
     */
    fun makePlan(off0: Double, k: Int, tStop: Double): Plan {
        val t1 = tStop - DECEL
        val o1 = off0 + V * t1
        val d = 30 + mod(targetOffset(k) - (o1 + 30), STRIP_LEN.toDouble())
        return Plan(off0 = off0, t1 = t1, tStop = tStop, o1 = o1, oF = o1 + d)
    }

    /** Offset du rouleau à l'instant t (s) depuis l'appui long. */
    fun offsetAt(p: Plan, t: Double): Double = when {
        t <= 0 -> p.off0
        // armement du ressort : les symboles remontent doucement
        t < T_PULL -> p.off0 - PULL * sin((t / T_PULL) * PI * 0.5)
        // détente : rejoint la trajectoire linéaire à T_LAUNCH, tangente finale = V
        t < T_LAUNCH -> {
            val u = (t - T_PULL) / (T_LAUNCH - T_PULL)
            hermite(u, p.off0 - PULL, p.off0 + V * T_LAUNCH, 0.0, V * (T_LAUNCH - T_PULL))
        }
        t < p.t1 -> p.off0 + V * t
        // décélération : continuité de vitesse à l'entrée, arrêt net avec léger settle
        t < p.tStop -> hermite((t - p.t1) / DECEL, p.o1, p.oF, V * DECEL)
        else -> p.oF
    }
}
