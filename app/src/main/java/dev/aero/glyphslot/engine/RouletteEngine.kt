package dev.aero.glyphslot.engine

import kotlin.random.Random

/**
 * La table de roulette — le mode alternatif du toy. Machine à états pure, temps
 * injecté (secondes, monotone), comme [SlotEngine].
 *
 * ```
 * REPOS ──secousse──▶ LANCER ──(bille posée)──▶ RÉSULTAT ──secousse──▶ LANCER …
 * ```
 *
 * Au repos le cylindre tourne doucement, sans bille. Un résultat reste affiché
 * jusqu'à la secousse suivante.
 *
 * **On relance dès que la bille est posée**, sans attendre que le numéro soit
 * affiché — même règle que la machine à sous, qui relance dès l'arrêt du
 * dernier rouleau. Le clignotement et le passage au numéro sont un
 * accompagnement, pas un temps mort.
 */
class RouletteEngine(private val random: Random = Random.Default) {

    /** Un résultat posé : numéro et couleur. */
    data class Shown(val result: Int, val color: Roulette.Color)

    /** Ce qu'il y a à dessiner — voir `RouletteRenderer`. */
    sealed interface View {
        data class Idle(val w: Double) : View
        data class Result(val shown: Shown) : View
        class Spinning(val spin: Roulette.Spin, val t: Double, val from: Shown?) : View
    }

    sealed interface Event {
        /** La bille touche la couronne : rebond `n` (1..5) — tick haptique. */
        data class Bounce(val n: Int) : Event

        /** Posée dans sa case. */
        data class Landed(val result: Int, val color: Roulette.Color, val hops: Int) : Event
    }

    private var spin: Roulette.Spin? = null
    private var from: Shown? = null
    private var shown: Shown? = null
    private var t0 = 0.0
    private var wIdle = 0.0
    private var lastNow = Double.NaN
    private var bounces = 0
    private var landed = false
    private val events = mutableListOf<Event>()

    /** Le dernier lancer en cours, ou null — pour la préview. */
    val current: Roulette.Spin? get() = spin

    /** Un lancer est-il possible à l'instant `now` ? Faux tant que la bille roule. */
    fun canLaunch(now: Double): Boolean {
        val s = spin ?: return true
        return now - t0 >= s.tLand
    }

    /**
     * Lance la bille (secousse). Numéro pré-tiré, trajectoire recalée dessus.
     * Ignoré tant que la bille n'est pas posée — retourne false.
     */
    fun launch(now: Double, force: Roulette.Color? = null): Boolean {
        if (!canLaunch(now)) return false
        update(now)

        var w0 = wIdle
        var fromShown: Shown? = shown
        spin?.let { s ->
            // Relancé pendant la révélation : si le cylindre est encore à
            // l'écran, il continue d'où il en est ; sinon c'est le numéro qui
            // s'éteint avant qu'il revienne.
            val t = now - t0
            if (t < s.tLand + Roulette.BLINK + Roulette.DIP) {
                w0 = Roulette.wheelAngle(s, t)
                fromShown = null
            } else {
                fromShown = Shown(s.result, s.color)
            }
        }

        val result = Roulette.draw(random, force)
        spin = Roulette.makeSpin(result, random, w0)
        from = fromShown
        shown = null
        bounces = 0
        landed = false
        t0 = now + if (fromShown != null) Roulette.PRE else 0.0
        return true
    }

    /**
     * Pose la table au repos — ce qu'il faut avant de changer de jeu. Un lancer
     * dont la bille est posée est conclu : son numéro reste affiché.
     */
    fun rest(now: Double) {
        update(now)
        val s = spin ?: return
        if (now - t0 >= s.tLand) {
            shown = Shown(s.result, s.color)
            spin = null
            from = null
        }
    }

    fun update(now: Double): View {
        val dt = if (lastNow.isNaN()) 0.0 else (now - lastNow).coerceIn(0.0, 0.1)
        lastNow = now

        val s = spin
        if (s != null) {
            val t = now - t0
            val n = Roulette.ballRadial(s, t).n
            if (n > bounces) {
                bounces = n
                events += Event.Bounce(n)
            }
            if (t >= s.tLand && !landed) {
                landed = true
                events += Event.Landed(s.result, s.color, s.hops.size)
            }
            if (t >= s.tShown) {
                val r = Shown(s.result, s.color)
                shown = r
                spin = null
                from = null
                return View.Result(r)
            }
            return View.Spinning(s, t, from)
        }

        shown?.let { return View.Result(it) }
        // au repos le cylindre tourne doucement — une table ouverte, pas éteinte
        wIdle += Roulette.W_IDLE * dt
        return View.Idle(wIdle)
    }

    /** Récupère et vide les événements accumulés depuis le dernier appel. */
    fun drainEvents(): List<Event> {
        val out = events.toList()
        events.clear()
        return out
    }
}
