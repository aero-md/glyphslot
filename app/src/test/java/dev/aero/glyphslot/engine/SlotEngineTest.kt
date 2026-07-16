package dev.aero.glyphslot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlotEngineTest {

    @Test
    fun `spin ignore si deja en cours`() {
        val e = SlotEngine(Random(1))
        assertTrue(e.spin(0.0))
        assertFalse(e.spin(1.0))
        e.update(2.0)
        assertFalse(e.spin(2.0))
    }

    @Test
    fun `jackpot force - sequence complete jusqu au retour IDLE`() {
        val e = SlotEngine(Random(7))
        e.spin(0.0, SlotEngine.ResultType.JACKPOT)
        assertTrue(e.targets.contentEquals(intArrayOf(0, 0, 0)))

        assertEquals(SlotEngine.State.SPINNING, e.update(1.0).state)
        assertEquals(SlotEngine.State.R1_STOP, e.update(2.7).state)
        assertEquals(SlotEngine.State.R2_STOP, e.update(3.9).state)
        assertEquals(SlotEngine.State.R3_STOP, e.update(4.95).state)

        val snap = e.update(4.9 + SlotEngine.SETTLE + 0.01)
        assertEquals(SlotEngine.State.RESULT, snap.state)
        assertEquals(SlotEngine.ResultType.JACKPOT, snap.resultType)
        // symboles finaux alignés sur la payline
        for (i in 0 until 3) {
            assertEquals(Reels.targetOffset(i, 0), snap.offsets[i], 1e-9)
        }

        // fin du jackpot (~7,5 s) → IDLE, symboles finaux affichés
        val after = e.update(4.9 + SlotEngine.SETTLE + SlotEngine.DUR_JACKPOT + 0.1)
        assertEquals(SlotEngine.State.IDLE, after.state)
        for (i in 0 until 3) {
            assertEquals(Reels.targetOffset(i, 0), after.offsets[i], 1e-9)
        }
    }

    @Test
    fun `lose - retour IDLE apres environ 1 seconde`() {
        val e = SlotEngine(Random(3))
        // force un tirage sans triple en re-tirant tant que ce n'est pas un lose
        var done = false
        var t = 0.0
        while (!done) {
            e.spin(t)
            if (!(e.targets[0] == e.targets[1] && e.targets[1] == e.targets[2])) done = true
            else {
                // laisse le spin se terminer et réessaie
                t += 20.0
                e.update(t)
            }
        }
        val tResult = t + Reels.STOPS[2] + SlotEngine.SETTLE + 0.01
        assertEquals(SlotEngine.ResultType.LOSE, e.update(tResult).resultType)
        assertEquals(SlotEngine.State.RESULT, e.update(tResult + 0.9).state)
        assertEquals(SlotEngine.State.IDLE, e.update(tResult + SlotEngine.DUR_LOSE + 0.1).state)
    }

    @Test
    fun `evenements ReelStopped emis une fois chacun dans l ordre`() {
        val e = SlotEngine(Random(11))
        e.spin(0.0, SlotEngine.ResultType.WIN)
        e.update(1.0)
        assertTrue(e.drainEvents().isEmpty())

        e.update(2.7)
        assertEquals(listOf<SlotEngine.Event>(SlotEngine.Event.ReelStopped(0)), e.drainEvents())

        // saut de temps après STOPS[2] + SETTLE : les deux arrêts restants et la
        // fin du spin arrivent dans le même update
        e.update(5.2)
        val events = e.drainEvents()
        assertEquals(
            listOf(SlotEngine.Event.ReelStopped(1), SlotEngine.Event.ReelStopped(2)),
            events.filterIsInstance<SlotEngine.Event.ReelStopped>(),
        )
        val finished = events.filterIsInstance<SlotEngine.Event.SpinFinished>()
        assertEquals(1, finished.size)
        assertEquals(SlotEngine.ResultType.WIN, finished[0].result)
    }

    @Test
    fun `tirage - probabilites approximatives 5pc jackpot 15pc triple`() {
        val e = SlotEngine(Random(42))
        var jackpots = 0
        var triples = 0
        val n = 20_000
        repeat(n) {
            val t = e.drawTargets()
            if (t[0] == t[1] && t[1] == t[2]) {
                if (t[0] == 0) jackpots++ else triples++
            }
        }
        assertEquals(0.05, jackpots.toDouble() / n, 0.01)
        assertEquals(0.15, triples.toDouble() / n, 0.01)
    }

    @Test
    fun `etat initial - jamais 3 symboles identiques`() {
        repeat(200) { seed ->
            val e = SlotEngine(Random(seed))
            val offs = e.update(0.0).offsets
            // offset ≡ -9·slot (mod 45) → slot = -offset/9 (mod 5), symbole = ORDER[i][slot]
            val syms = IntArray(3) { i ->
                val slot = Reels.mod((-offs[i] / Reels.SYM_H).toInt(), Reels.SYMBOL_COUNT)
                Reels.ORDER[i][slot]
            }
            assertFalse(
                "seed=$seed syms=${syms.toList()}",
                syms[0] == syms[1] && syms[1] == syms[2],
            )
        }
    }

    @Test
    fun `win force - trois symboles identiques jamais le 7`() {
        repeat(50) { seed ->
            val e = SlotEngine(Random(seed))
            e.spin(0.0, SlotEngine.ResultType.WIN)
            assertTrue(e.targets[0] == e.targets[1] && e.targets[1] == e.targets[2])
            assertTrue(e.targets[0] in 1 until Reels.SYMBOL_COUNT)
        }
    }
}
