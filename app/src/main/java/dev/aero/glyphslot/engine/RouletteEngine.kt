package dev.aero.glyphslot.engine

import kotlin.random.Random

/**
 * La table de roulette — le mode alternatif du toy. Machine à états pure, temps
 * injecté (secondes, monotone), comme [SlotEngine].
 *
 * ```
 * REPOS ──secousse──▶ LANCER ──(bille posée)──▶ RÉSULTAT ──secousse──▶ LANCER …
 *   ▲                                              │
 *   └──────────────(HOLD = 8 s, puis RETOUR)───────┘
 * ```
 *
 * Au repos le cylindre tourne doucement, sans bille. Un résultat reste affiché
 * [Roulette.HOLD] secondes, puis le numéro s'éteint et le cylindre revient
 * ([View.Returning]) — sauf secousse avant.
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

        /**
         * Le retour au repos, une fois le résultat resté [Roulette.HOLD] : `t`
         * part de 0 et court jusqu'à `DIP + REVEAL` — le numéro s'éteint, puis
         * le cylindre revient en phase `w`.
         */
        data class Returning(val shown: Shown, val w: Double, val t: Double) : View
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
    /** Instant où le résultat a fini de s'allumer : c'est de là que court [Roulette.HOLD]. */
    private var shownAt = 0.0
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
        var lead = if (fromShown != null) Roulette.PRE else 0.0
        val s = spin
        val r = shown
        if (s != null) {
            // Relancé pendant la révélation : si le cylindre est encore à
            // l'écran, il continue d'où il en est ; sinon c'est le numéro qui
            // s'éteint avant qu'il revienne.
            val t = now - t0
            if (t < s.tLand + Roulette.BLINK + Roulette.DIP) {
                w0 = Roulette.wheelAngle(s, t)
                fromShown = null
                lead = 0.0
            } else {
                fromShown = Shown(s.result, s.color)
                lead = Roulette.PRE
            }
        } else if (r != null) {
            // Relancé pendant le retour au repos : numéro à demi éteint, son
            // fondu reprend là où il en est — `ease` étant symétrique, avancer
            // l'origine de PRE · (1 − tb / DIP) retombe exactement sur la
            // luminosité affichée ; cylindre déjà revenu, il continue de tourner.
            val tb = now - shownAt - Roulette.HOLD
            if (tb >= Roulette.DIP) {
                fromShown = null
                lead = 0.0
            } else if (tb > 0) {
                lead = Roulette.PRE * (1 - tb / Roulette.DIP)
            }
        }

        val result = Roulette.draw(random, force)
        spin = Roulette.makeSpin(result, random, w0)
        from = fromShown
        shown = null
        bounces = 0
        landed = false
        t0 = now + lead
        return true
    }

    /**
     * Pose la table au repos — ce qu'il faut avant de changer de jeu. Un lancer
     * dont la bille est posée est conclu : son numéro s'affiche, et [Roulette.HOLD]
     * court à partir de maintenant.
     */
    fun rest(now: Double) {
        update(now)
        val s = spin ?: return
        if (now - t0 >= s.tLand) {
            wIdle = Roulette.wheelAngle(s, now - t0)
            shown = Shown(s.result, s.color)
            shownAt = now
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
            if (t < s.tShown) return View.Spinning(s, t, from)

            shown = Shown(s.result, s.color)
            shownAt = t0 + s.tShown
            wIdle = Roulette.wheelAngle(s, s.tShown)
            spin = null
            from = null
        }

        // le cylindre tourne aussi derrière le numéro : il revient où il en est
        wIdle += Roulette.W_IDLE * dt
        shown?.let { r ->
            val tb = now - shownAt - Roulette.HOLD
            if (tb < 0) return View.Result(r)
            if (tb < Roulette.DIP + Roulette.REVEAL) return View.Returning(r, wIdle, tb)
            shown = null
        }
        // au repos le cylindre tourne doucement — une table ouverte, pas éteinte
        return View.Idle(wIdle)
    }

    /** Récupère et vide les événements accumulés depuis le dernier appel. */
    fun drainEvents(): List<Event> {
        val out = events.toList()
        events.clear()
        return out
    }
}
