package dev.aero.glyphslot.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Roulette — le cylindre européen et la cinématique d'un lancer. Logique pure,
 * testable JUnit, sans Android.
 *
 * **Le numéro est tiré avant le lancer**, et la trajectoire est recalée pour que
 * la bille tombe dans sa case : l'animation ne découvre pas le résultat, elle y
 * conduit.
 *
 * Port de `src/routes/glyphslot/lib/wheel.ts` (glyph-portal) — mêmes constantes,
 * mêmes courbes. Les deux se corrigent ensemble.
 *
 * Angles : 0 = midi, sens horaire.
 */
object Roulette {

    /** Ordre des cases sur un cylindre européen, depuis le zéro, sens horaire. */
    val ORDER = intArrayOf(
        0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33,
        1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26,
    )

    val POCKETS = ORDER.size
    val POCKET = 2 * PI / POCKETS

    private val REDS = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)

    enum class Color { ZERO, RED, BLACK }

    fun colorOf(n: Int): Color = when {
        n == 0 -> Color.ZERO
        n in REDS -> Color.RED
        else -> Color.BLACK
    }

    fun mod(k: Int, n: Int): Int = ((k % n) + n) % n

    /** Couleur de la case d'indice `k` — sa position sur le cylindre, pas son numéro. */
    fun pocketColor(k: Int): Color = colorOf(ORDER[mod(k, POCKETS)])

    /** Tirage uniforme sur les 37 cases, éventuellement contraint à une couleur. */
    fun draw(random: Random, force: Color? = null): Int {
        val pool = if (force == null) ORDER.toList() else ORDER.filter { colorOf(it) == force }
        return pool[random.nextInt(pool.size)]
    }

    /* --- chronologie d'un lancer, en secondes ---------------------------- */

    /** La bille quitte la piste et descend le cône. */
    const val T_FALL = 3.3

    /** Elle touche la couronne des cases : premier rebond. */
    const val T_HIT = 4.05

    /* Après la pose, toutes relatives à `tLand` : celui-ci dépend du nombre de rebonds. */

    /** La case gagnante clignote… */
    const val BLINK = 0.6

    /** …puis le cylindre s'éteint… */
    const val DIP = 0.35

    /** …et le numéro s'allume. */
    const val REVEAL = 0.35

    /** Fondu de sortie du résultat quand un lancer part d'un résultat affiché. */
    const val PRE = 0.25

    /**
     * Le numéro reste affiché tant de secondes, puis la table se rouvre : il
     * s'éteint et le cylindre revient tourner au ralenti — sur [DIP] puis
     * [REVEAL], la révélation à l'envers. Un résultat qui ne part jamais laisse
     * un toy figé sur un chiffre ; la roulette au repos, elle, invite à relancer.
     */
    const val HOLD = 8.0

    /* --- rebonds -------------------------------------------------------- */

    data class Hop(val d: Double, val h: Double)

    const val HOPS_MIN = 1
    const val HOPS_MAX = 5

    /**
     * Entre 1 et 5 rebonds, au hasard, de hauteur décroissante.
     *
     * **Aucun ne descend sous une cellule.** Le rebond est un écart radial vers la
     * piste ; sous la cellule, l'arrondi le colle à la case et il n'existe plus
     * que dans le code — un lancer à cinq rebonds dont les deux derniers ne se
     * voient pas se lit comme un lancer à trois.
     *
     * La durée suit la racine de la hauteur, comme une chute libre : la série
     * accélère en s'éteignant.
     */
    fun makeHops(random: Random): List<Hop> {
        val n = HOPS_MIN + floor(random.nextDouble() * (HOPS_MAX - HOPS_MIN + 1)).toInt()
        val hops = ArrayList<Hop>(n)
        var h = 2 + random.nextDouble() * 0.6
        repeat(n) {
            hops += Hop(d = 0.28 * sqrt(h), h = h)
            h = max(1.0, h * (0.7 + random.nextDouble() * 0.15))
        }
        return hops
    }

    /* --- cylindre et bille ---------------------------------------------- */

    /** Vitesse du cylindre au lancer (rad/s, horaire) et son amortissement. */
    private const val W0 = 2 * PI * 0.42
    private const val TAU_W = 5.0

    /** Vitesse du cylindre au repos, entre deux lancers. */
    const val W_IDLE = 2 * PI * 0.05

    /** Vitesse absolue de la bille sur la piste, antihoraire : du lâcher à la chute. */
    private const val B0 = 2 * PI * 1.85
    private const val B1 = 2 * PI * 0.7

    /** Pas d'intégration de la trajectoire relative. */
    private const val DT = 1.0 / 480

    class Spin(
        val result: Int,
        val color: Color,
        /** Indice de la case gagnante sur le cylindre. */
        val k: Int,
        val hops: List<Hop>,
        /** Posée : fin du dernier rebond. */
        val tLand: Double,
        /** Le numéro est entièrement affiché : le lancer est terminé. */
        val tShown: Double,
        /** Phase du cylindre à t = 0. */
        val w0: Double,
        /** Angle relatif bille/cylindre à t = 0, recalé pour tomber sur `k`. */
        internal val psi0: Double,
        /** ψ libre (sans recalage), échantillonné à `DT`, de 0 à `tLand`. */
        internal val psiFree: DoubleArray,
    )

    private fun wheelTravel(t: Double): Double = W0 * TAU_W * (1 - exp(-t / TAU_W))

    private fun wheelSpeed(t: Double): Double = W0 * exp(-t / TAU_W)

    /** Vitesse **relative** de la bille par rapport au cylindre, avant recalage. */
    private fun relSpeed(t: Double, tLand: Double): Double {
        if (t < T_FALL) {
            val ball = -(B0 + (B1 - B0) * t / T_FALL)
            return ball - wheelSpeed(t)
        }
        val atFall = -B1 - wheelSpeed(T_FALL)
        val s = min(1.0, (t - T_FALL) / (tLand - T_FALL))
        return atFall * (1 - s) * (1 - s)
    }

    /**
     * Un lancer vers `result`. `w0` est la phase du cylindre au départ — celle du
     * repos, pour qu'il ne saute pas au moment où la bille part.
     */
    fun makeSpin(result: Int, random: Random, w0: Double = 0.0): Spin {
        val k = ORDER.indexOf(result)
        require(k >= 0) { "numéro hors cylindre : $result" }

        val hops = makeHops(random)
        val tLand = T_HIT + hops.sumOf { it.d }

        val n = ceil(tLand / DT).toInt() + 1
        val psiFree = DoubleArray(n)
        for (j in 1 until n) psiFree[j] = psiFree[j - 1] + relSpeed((j - 0.5) * DT, tLand) * DT

        return Spin(
            result = result,
            color = colorOf(result),
            k = k,
            hops = hops,
            tLand = tLand,
            tShown = tLand + BLINK + DIP + REVEAL,
            w0 = w0,
            psi0 = k * POCKET - psiFree[n - 1],
            psiFree = psiFree,
        )
    }

    /** Phase du cylindre. */
    fun wheelAngle(s: Spin, t: Double): Double = s.w0 + wheelTravel(max(0.0, t))

    /** Angle de la bille relatif au cylindre. Constant une fois posée. */
    fun ballRel(s: Spin, t: Double): Double {
        if (t >= s.tLand) return s.k * POCKET
        val f = max(0.0, t) / DT
        val j = min(floor(f).toInt(), s.psiFree.size - 2)
        val u = f - j
        return s.psi0 + s.psiFree[j] * (1 - u) + s.psiFree[j + 1] * u
    }

    /** Angle absolu de la bille. */
    fun ballAngle(s: Spin, t: Double): Double = wheelAngle(s, t) + ballRel(s, t)

    /**
     * Position radiale normalisée : `fall` vaut 0 sur la piste et 1 au fond de la
     * case ; `hop` est le rebond, en cellules, vers l'extérieur ; `n` le numéro
     * du rebond en cours, 0 hors rebond.
     */
    data class Radial(val fall: Double, val hop: Double, val n: Int)

    fun ballRadial(s: Spin, t: Double): Radial {
        if (t < T_FALL) return Radial(0.0, 0.0, 0)
        if (t < T_HIT) {
            val u = (t - T_FALL) / (T_HIT - T_FALL)
            return Radial(u * u, 0.0, 0)
        }
        var u = t - T_HIT
        s.hops.forEachIndexed { i, h ->
            if (u < h.d) return Radial(1.0, h.h * sin(PI * u / h.d), i + 1)
            u -= h.d
        }
        return Radial(1.0, 0.0, 0)
    }

    /** Indice de case sous l'angle `a` pour une phase `w`. */
    fun pocketIndex(a: Double, w: Double): Int = mod(((a - w) / POCKET).roundToInt(), POCKETS)
}
