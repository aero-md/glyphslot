package dev.aero.glyphslot.engine

import kotlin.random.Random

/**
 * Machine à états de la machine à sous — logique pure, testable JUnit.
 *
 * IDLE ──appui long──▶ SPINNING ──▶ R1_STOP ──▶ R2_STOP ──▶ R3_STOP ──▶ RESULT ──▶ IDLE
 *
 * Le temps est injecté (secondes, monotone) : aucun accès horloge/Android ici.
 */
class SlotEngine(private val random: Random = Random.Default) {

    enum class State { IDLE, SPINNING, R1_STOP, R2_STOP, R3_STOP, RESULT }

    enum class ResultType { LOSE, WIN, JACKPOT }

    sealed interface Event {
        /** Un rouleau vient de s'arrêter (0..2) — tick haptique. */
        data class ReelStopped(val index: Int) : Event

        /** Les 3 rouleaux sont posés, le résultat démarre. */
        data class SpinFinished(val result: ResultType) : Event

        /** Fin des effets de résultat, retour IDLE. */
        data object ResultEnded : Event
    }

    data class Snapshot(
        val state: State,
        /** Offsets courants des 3 rouleaux (lignes de bande). */
        val offsets: DoubleArray,
        /** Temps écoulé depuis l'appui long (spin en cours uniquement). */
        val spinTime: Double,
        /** Type de résultat, non-nul en RESULT seulement. */
        val resultType: ResultType?,
        /** Temps écoulé depuis l'entrée en RESULT. */
        val resultTime: Double,
    )

    companion object {
        /** Marge après l'arrêt du rouleau 3 avant d'annoncer le résultat. */
        const val SETTLE = 0.15

        const val DUR_LOSE = 1.0
        const val DUR_WIN = 2.8
        const val DUR_JACKPOT = 7.5

        /** 5 % triple 7, 15 % triple autre symbole. */
        const val P_JACKPOT = 0.05
        const val P_TRIPLE = 0.20
    }

    var state: State = State.IDLE
        private set

    var targets: IntArray = intArrayOf(0, 0, 0)
        private set

    var resultType: ResultType? = null
        private set

    /** Offsets au repos — symboles alignés sur la payline. */
    private var offsets: DoubleArray

    private var plans: List<Reels.Plan> = emptyList()
    private var t0 = 0.0
    private var resultStart = 0.0
    private var announcedStops = 0
    private val events = mutableListOf<Event>()

    init {
        // état initial : tirage aléatoire, jamais 3 identiques
        offsets = DoubleArray(3).also { arr ->
            drawNoTriple().forEachIndexed { i, k -> arr[i] = Reels.targetOffset(i, k) }
        }
    }

    private fun drawNoTriple(): IntArray {
        var t: IntArray
        do {
            t = IntArray(3) { random.nextInt(Reels.SYMBOL_COUNT) }
        } while (t[0] == t[1] && t[1] == t[2])
        return t
    }

    /** Tirage du résultat : 5 % triple 7, 15 % triple autre, sinon sans triple. */
    fun drawTargets(): IntArray {
        val r = random.nextDouble()
        return when {
            r < P_JACKPOT -> intArrayOf(0, 0, 0)
            r < P_TRIPLE -> {
                val k = 1 + random.nextInt(Reels.SYMBOL_COUNT - 1)
                intArrayOf(k, k, k)
            }
            else -> drawNoTriple()
        }
    }

    /**
     * Lance le spin (appui long). Résultat pré-tiré, plans pré-calculés.
     * Ignoré si un spin ou un résultat est en cours — retourne false.
     */
    fun spin(now: Double, force: ResultType? = null): Boolean {
        if (state != State.IDLE) return false
        targets = when (force) {
            ResultType.JACKPOT -> intArrayOf(0, 0, 0)
            ResultType.WIN -> {
                val k = 1 + random.nextInt(Reels.SYMBOL_COUNT - 1)
                intArrayOf(k, k, k)
            }
            else -> drawTargets()
        }
        plans = List(3) { i -> Reels.makePlan(i, offsets[i], targets[i], Reels.STOPS[i]) }
        t0 = now
        announcedStops = 0
        resultType = null
        state = State.SPINNING
        return true
    }

    fun update(now: Double): Snapshot {
        when (state) {
            State.SPINNING, State.R1_STOP, State.R2_STOP, State.R3_STOP -> {
                val t = now - t0
                val stopped = Reels.STOPS.count { t >= it }
                while (announcedStops < stopped) {
                    events += Event.ReelStopped(announcedStops)
                    announcedStops++
                }
                state = when (stopped) {
                    0 -> State.SPINNING
                    1 -> State.R1_STOP
                    2 -> State.R2_STOP
                    else -> State.R3_STOP
                }
                if (t >= Reels.STOPS[2] + SETTLE) {
                    state = State.RESULT
                    resultStart = now
                    resultType = when {
                        targets[0] == targets[1] && targets[1] == targets[2] ->
                            if (targets[0] == 0) ResultType.JACKPOT else ResultType.WIN
                        else -> ResultType.LOSE
                    }
                    offsets = DoubleArray(3) { Reels.targetOffset(it, targets[it]) }
                    events += Event.SpinFinished(resultType!!)
                }
            }

            State.RESULT -> {
                val dur = when (resultType) {
                    ResultType.JACKPOT -> DUR_JACKPOT
                    ResultType.WIN -> DUR_WIN
                    else -> DUR_LOSE
                }
                if (now - resultStart > dur) {
                    state = State.IDLE
                    resultType = null
                    events += Event.ResultEnded
                }
            }

            State.IDLE -> Unit
        }

        val spinning = state != State.IDLE && state != State.RESULT
        val offs =
            if (spinning) DoubleArray(3) { Reels.offsetAt(plans[it], now - t0) }
            else offsets.copyOf()

        return Snapshot(
            state = state,
            offsets = offs,
            spinTime = if (spinning) now - t0 else 0.0,
            resultType = if (state == State.RESULT) resultType else null,
            resultTime = if (state == State.RESULT) now - resultStart else 0.0,
        )
    }

    /** Récupère et vide les événements accumulés depuis le dernier appel. */
    fun drainEvents(): List<Event> {
        val out = events.toList()
        events.clear()
        return out
    }
}
