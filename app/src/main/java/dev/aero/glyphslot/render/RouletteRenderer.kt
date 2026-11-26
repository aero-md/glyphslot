package dev.aero.glyphslot.render

import dev.aero.glyphslot.engine.Reels
import dev.aero.glyphslot.engine.Roulette
import dev.aero.glyphslot.engine.RouletteEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * La roulette vue du dessus : la piste sur le contour, la couronne des 37 cases,
 * le croisillon au centre. La bille est lancée sur la piste en sens inverse,
 * décroche, rebondit dans les cases et se pose ; la case gagnante clignote, le
 * cylindre s'éteint et le numéro s'allume, avec sa couleur dessous.
 *
 * **Rouge / noir n'est jamais écrit** : la matrice est monochrome. Rouge = plein,
 * noir = creux — une case rouge allumée, une case noire presque éteinte ; sous le
 * numéro, une barre pleine ou une barre en contour. Le zéro n'a pas de barre.
 *
 * Port de `src/routes/glyphslot/lib/roulette.ts` (glyph-portal) : même rendu, au
 * point près. Sortie : IntArray(625), 0..255, 0 hors du disque — même contrat
 * que [MatrixRenderer].
 */
class RouletteRenderer {

    private val size = Reels.SIZE
    private val cx = Reels.CENTER.toDouble()
    private val cy = Reels.CENTER.toDouble()
    private val radius = Reels.RADIUS

    private val g = FloatArray(size * size)
    private val spokes = FloatArray(size * size)
    private val out = IntArray(size * size)

    /** Rayon et angle de chaque sous-échantillon (quatre par cellule). */
    private val sr = DoubleArray(size * size * 4)
    private val sa = DoubleArray(size * size * 4)

    /** Le contour du disque, et l'angle de chacune de ses cellules. */
    private val edge: IntArray
    private val edgeAngle: DoubleArray

    init {
        for (i in 0 until size * size) {
            val x = i % size
            val y = i / size
            for (s in 0 until 4) {
                val dx = x + SUB[s * 2] - cx
                val dy = y + SUB[s * 2 + 1] - cy
                sr[i * 4 + s] = hypot(dx, dy)
                val a = atan2(dx, -dy)
                sa[i * 4 + s] = if (a < 0) a + 2 * PI else a
            }
        }
        fun solid(x: Int, y: Int) = x in 0 until size && y in 0 until size && Disc.inside[y * size + x]
        edge = Disc.cells.filter { i ->
            val x = i % size
            val y = i / size
            !solid(x - 1, y) || !solid(x + 1, y) || !solid(x, y - 1) || !solid(x, y + 1)
        }.toIntArray()
        edgeAngle = DoubleArray(edge.size) { k -> angleOf(edge[k]) }
    }

    fun render(view: RouletteEngine.View): IntArray {
        g.fill(0f)
        when (view) {
            is RouletteEngine.View.Idle -> wheel(view.w, -1)
            is RouletteEngine.View.Result -> result(view.shown.result, view.shown.color, 1f)
            is RouletteEngine.View.Spinning -> spin(view.spin, view.t, view.from)
        }
        for (i in out.indices) {
            out[i] = if (Disc.inside[i]) (min(g[i], 1f) * 255).roundToInt() else 0
        }
        return out
    }

    private fun spin(s: Roulette.Spin, t: Double, from: RouletteEngine.Shown?) {
        if (t < 0) {
            if (from != null) result(from.result, from.color, ease(-t / Roulette.PRE))
            return
        }
        val dip0 = s.tLand + Roulette.BLINK
        val reveal0 = dip0 + Roulette.DIP

        if (t < reveal0) {
            // Le cylindre revient en fondu s'il succède à un résultat ; au repos
            // il était déjà là, il n'a qu'à continuer de tourner.
            val fadeIn = if (from != null) ease(t / Roulette.PRE) else 1f
            val fadeOut = if (t < dip0) 1f else 1f - ease((t - dip0) / Roulette.DIP)
            val blink = t > s.tLand + 0.1 && floor((t - s.tLand) / BLINK_HALF).toInt() % 2 == 0
            wheel(Roulette.wheelAngle(s, t), if (blink) s.k else -1)
            ball(s, t)
            val f = fadeIn * fadeOut
            if (f < 1f) for (i in Disc.cells) g[i] *= f
            return
        }

        // Creux au noir et non fondu enchaîné : deux trames superposées à
        // mi-chemin ne se lisent ni comme l'une ni comme l'autre.
        result(s.result, s.color, ease((t - reveal0) / Roulette.REVEAL))
    }

    /** Piste, couronne des cases et croisillon. `win` : case allumée, ou −1. */
    private fun wheel(w: Double, win: Int) {
        val rIn = radius - POCKET_IN
        val rOut = radius - POCKET_OUT

        // la piste : un lavis sur le contour, fixe — c'est la cuvette
        for (i in edge) g[i] = TRACK

        for (i in Disc.cells) {
            var acc = 0f
            for (s in 0 until 4) {
                val r = sr[i * 4 + s]
                if (r < rIn || r >= rOut) continue
                val k = Roulette.pocketIndex(sa[i * 4 + s], w)
                acc += if (k == win) WIN else when (Roulette.pocketColor(k)) {
                    Roulette.Color.ZERO -> ZERO
                    Roulette.Color.RED -> RED
                    Roulette.Color.BLACK -> BLACK
                }
            }
            val v = acc / 4
            if (v > g[i]) g[i] = v
        }

        // le croisillon, solidaire du cylindre : c'est lui qui montre qu'il tourne
        spokes.fill(0f)
        for (m in 0 until 4) {
            val a = w + m * PI / 2
            line(cx, cy, cx + SPOKE_LEN * sin(a), cy - SPOKE_LEN * cos(a), SPOKE)
        }
        set(spokes, cx.roundToInt(), cy.roundToInt(), HUB)
        for (i in Disc.cells) if (spokes[i] > g[i]) g[i] = spokes[i]
    }

    /**
     * La bille, et une courte traînée : sur la piste elle parcourt deux à trois
     * cellules d'une image à l'autre, et une LED seule qui saute se lit comme un
     * clignotement. Sur la piste elle suit le **contour** du disque : un rayon
     * fixe la ferait entrer et sortir de la piste selon l'angle.
     */
    private fun ball(s: Roulette.Spin, t: Double) {
        val rPocket = radius - (POCKET_IN + POCKET_OUT) / 2
        val n = (TRAIL / TRAIL_DT).roundToInt()
        for (j in n downTo 0) {
            val tj = t - j * TRAIL_DT
            if (tj < 0) continue
            val a = Roulette.ballAngle(s, tj)
            val rad = Roulette.ballRadial(s, tj)
            val r = edgeDist(a) * (1 - rad.fall) + rPocket * rad.fall + rad.hop
            val b = if (j == 0) 1f else (0.5 * (1 - j.toDouble() / (n + 1))).toFloat()
            put(cx + r * sin(a), cy - r * cos(a), b)
        }
    }

    /** Le numéro, et sous lui la barre de couleur. Centré dans le hublot. */
    private fun result(n: Int, color: Roulette.Color, b: Float) {
        if (b <= 0f) return
        val text = n.toString()
        val h = DIGIT_H + if (color == Roulette.Color.ZERO) 0 else GAP + BAR_RED.size
        val y0 = (cy - (h - 1) / 2.0).roundToInt()

        val w = text.length * (DIGIT_W + 1) - 1
        var x = ((size - w) / 2.0).roundToInt()
        for (c in text) {
            val rows = Sprites.DIGITS.getValue(c)
            for (r in 0 until DIGIT_H) for (k in 0 until DIGIT_W) {
                if (rows[r][k] == '1') put((x + k).toDouble(), (y0 + r).toDouble(), b)
            }
            x += DIGIT_W + 1
        }

        val bar = when (color) {
            Roulette.Color.RED -> BAR_RED
            Roulette.Color.BLACK -> BAR_BLACK
            Roulette.Color.ZERO -> return
        }
        val bx = (cx - (bar[0].length - 1) / 2.0).roundToInt()
        val by = y0 + DIGIT_H + GAP
        for (r in bar.indices) for (c in bar[r].indices) {
            if (bar[r][c] == '1') put((bx + c).toDouble(), (by + r).toDouble(), b)
        }
    }

    /** Angle d'une cellule vue du centre, 0 = midi, horaire. */
    private fun angleOf(i: Int): Double = atan2((i % size) - cx, -((i / size) - cy))

    /**
     * Rayon disponible dans la direction `a` : la distance de la cellule du
     * contour la plus proche en angle. Un balayage radial tomberait entre deux
     * cellules dans les diagonales, et la bille décrocherait du bord.
     */
    private fun edgeDist(a: Double): Double {
        // `a` cumule les tours de la bille : ramené dans ]−π, π], comme `edgeAngle`
        var an = a % (2 * PI)
        if (an > PI) an -= 2 * PI
        if (an <= -PI) an += 2 * PI
        var best = 0.0
        var bestGap = Double.MAX_VALUE
        for (k in edge.indices) {
            var gap = abs(edgeAngle[k] - an)
            if (gap > PI) gap = 2 * PI - gap
            if (gap < bestGap) {
                bestGap = gap
                best = Disc.dist[edge[k]].toDouble()
            }
        }
        return best
    }

    /** Segment : cœur d'une cellule, épaule en demi-teinte du côté où il passe entre deux. */
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, b: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val n = max(1, (max(abs(dx), abs(dy)) * 2).roundToInt())
        val flat = abs(dx) >= abs(dy)
        for (k in 0..n) {
            val t = k.toDouble() / n
            val x = x0 + dx * t
            val y = y0 + dy * t
            val xi = x.roundToInt()
            val yi = y.roundToInt()
            set(spokes, xi, yi, b)
            val f = if (flat) y - yi else x - xi
            val s = 2 * abs(f) - SHOULDER
            if (s > 0) {
                val step = if (f > 0) 1 else -1
                if (flat) set(spokes, xi, yi + step, (b * s).toFloat())
                else set(spokes, xi + step, yi, (b * s).toFloat())
            }
        }
    }

    private fun set(buf: FloatArray, x: Int, y: Int, b: Float) {
        if (x < 0 || y < 0 || x >= size || y >= size) return
        val i = y * size + x
        if (Disc.inside[i] && b > buf[i]) buf[i] = b
    }

    private fun put(x: Double, y: Double, b: Float) = set(g, x.roundToInt(), y.roundToInt(), b)

    private fun ease(x: Double): Float {
        val u = x.coerceIn(0.0, 1.0)
        return (u * u * (3 - 2 * u)).toFloat()
    }

    private companion object {
        /* niveaux : la bille et le numéro sont pleins, la carcasse à mi-hauteur
           perçue, la piste et les cases noires un lavis */
        const val RED = 0.22f
        const val BLACK = 0.03f
        /** Le zéro, plus clair que tout : c'est le repère qui fait voir le cylindre tourner. */
        const val ZERO = 0.6f
        const val WIN = 0.75f
        const val HUB = 0.45f
        const val SPOKE = 0.18f
        const val TRACK = 0.03f

        /** Couronne des cases, en cellules depuis le bord nominal. */
        const val POCKET_IN = 3.9
        const val POCKET_OUT = 1.6
        const val SPOKE_LEN = 3.6

        const val TRAIL = 0.05
        const val TRAIL_DT = 0.004
        const val BLINK_HALF = 0.14
        const val SHOULDER = 0.5

        /** Sous-échantillons, en paires (dx, dy). */
        val SUB = doubleArrayOf(-0.25, -0.25, 0.25, -0.25, -0.25, 0.25, 0.25, 0.25)

        const val DIGIT_W = 5
        const val DIGIT_H = 7
        const val GAP = 2

        /** La barre de couleur : pleine = rouge, contour = noir. */
        val BAR_RED = Array(4) { "11111111111" }
        val BAR_BLACK = arrayOf("11111111111", "10000000001", "10000000001", "11111111111")
    }
}
