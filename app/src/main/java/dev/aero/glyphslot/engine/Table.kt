package dev.aero.glyphslot.engine

import kotlin.random.Random

/** Les deux jeux du toy. */
enum class Game { SLOT, ROULETTE }

/**
 * La table : les deux jeux, et celui qui est à l'écran. Partagée par le toy et
 * la préview Compose, pour que les deux tiennent les mêmes règles.
 *
 * - **secousse** → lance le jeu courant ;
 * - **appui long** → change de jeu.
 *
 * Les deux gestes sont refusés tant que le jeu courant tourne : changer de jeu
 * en plein tirage ferait disparaître un résultat déjà tiré. Ils redeviennent
 * possibles dès que le dernier rouleau est arrêté, ou que la bille est posée.
 */
class Table(random: Random = Random.Default, initial: Game = Game.SLOT) {

    val slot = SlotEngine(random)
    val roulette = RouletteEngine(random)

    var game: Game = initial
        private set

    /** Le jeu courant accepte-t-il un geste à l'instant `now` ? */
    fun ready(now: Double): Boolean = when (game) {
        Game.SLOT -> slot.canSpin(now)
        Game.ROULETTE -> roulette.canLaunch(now)
    }

    /** Secousse : lance le jeu courant. False si refusé. */
    fun launch(now: Double): Boolean = when (game) {
        Game.SLOT -> slot.spin(now)
        Game.ROULETTE -> roulette.launch(now)
    }

    /**
     * Appui long : passe à l'autre jeu. Le jeu quitté est d'abord posé au repos
     * — tirage conclu, effets coupés — pour qu'on le retrouve tel qu'on l'a
     * laissé, et pas au milieu d'un jackpot. False si refusé.
     */
    fun switch(now: Double): Boolean {
        if (!ready(now)) return false
        // Les événements du jeu quitté sont jetés : ils ne sont relus qu'à son
        // retour, et un jackpot conclu par `rest` vibrerait alors des minutes
        // plus tard, au milieu de l'autre jeu.
        when (game) {
            Game.SLOT -> slot.rest(now).also { slot.drainEvents() }
            Game.ROULETTE -> roulette.rest(now).also { roulette.drainEvents() }
        }
        game = if (game == Game.SLOT) Game.ROULETTE else Game.SLOT
        return true
    }
}
