package dev.aero.glyphslot.engine

import dev.aero.glyphslot.render.RouletteRenderer
import dev.aero.glyphslot.render.Sprites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class RouletteTest {

    /** Écart angulaire le plus court, en radians. */
    private fun gap(a: Double, b: Double): Double {
        val d = ((a - b) % (2 * PI) + 2 * PI) % (2 * PI)
        return minOf(d, 2 * PI - d)
    }

    @Test
    fun `la bille se pose dans la case tiree, sans saut a la pose`() {
        val random = Random(3)
        for (n in Roulette.ORDER) {
            val s = Roulette.makeSpin(n, random, w0 = random.nextDouble() * 6)
            val k = Roulette.ORDER.indexOf(n)
            // posée : exactement sur la case k
            assertEquals(0.0, gap(Roulette.ballRel(s, s.tLand), k * Roulette.POCKET), 1e-9)
            // juste avant : déjà dessus — le recalage n'a pas de marche
            assertTrue(gap(Roulette.ballRel(s, s.tLand - 1e-3), k * Roulette.POCKET) < 1e-3)
        }
    }

    @Test
    fun `entre 1 et 5 rebonds, tous visibles, tous les comptes tires`() {
        val random = Random(11)
        val seen = mutableSetOf<Int>()
        repeat(2_000) {
            val hops = Roulette.makeHops(random)
            assertTrue(hops.size in Roulette.HOPS_MIN..Roulette.HOPS_MAX)
            // jamais sous une cellule : un rebond plus bas ne se verrait pas
            assertTrue(hops.all { it.h >= 1.0 })
            seen += hops.size
        }
        assertEquals((1..5).toSet(), seen)
    }

    @Test
    fun `la pose suit le nombre de rebonds`() {
        val s = Roulette.makeSpin(17, Random(4))
        assertEquals(Roulette.T_HIT + s.hops.sumOf { it.d }, s.tLand, 1e-12)
        assertEquals(s.tLand + Roulette.BLINK + Roulette.DIP + Roulette.REVEAL, s.tShown, 1e-12)
    }

    @Test
    fun `tirage force - la couleur demandee`() {
        val random = Random(8)
        repeat(200) {
            assertEquals(Roulette.Color.RED, Roulette.colorOf(Roulette.draw(random, Roulette.Color.RED)))
            assertEquals(Roulette.Color.BLACK, Roulette.colorOf(Roulette.draw(random, Roulette.Color.BLACK)))
        }
        assertEquals(0, Roulette.draw(random, Roulette.Color.ZERO))
    }

    @Test
    fun `relance refusee tant que la bille roule, permise des qu elle est posee`() {
        val e = RouletteEngine(Random(2))
        assertTrue(e.launch(0.0))
        val s = e.current!!
        assertFalse(e.launch(s.tLand - 0.01))
        assertTrue(e.launch(s.tLand + 0.01))
    }

    @Test
    fun `un tick par rebond, une pose`() {
        val e = RouletteEngine(Random(6))
        e.launch(0.0)
        val s = e.current!!
        var t = 0.0
        while (t < s.tShown + 0.1) {
            e.update(t)
            t += 1.0 / 30
        }
        val events = e.drainEvents()
        assertEquals(s.hops.size, events.filterIsInstance<RouletteEngine.Event.Bounce>().size)
        val landed = events.filterIsInstance<RouletteEngine.Event.Landed>()
        assertEquals(1, landed.size)
        assertEquals(s.result, landed[0].result)
        // lancer terminé : le numéro reste affiché
        assertTrue(e.update(t + 5) is RouletteEngine.View.Result)
    }

    @Test
    fun `barre pleine pour le rouge, contour pour le noir, rien pour le zero`() {
        val r = RouletteRenderer()
        fun lit(n: Int) = r.render(
            RouletteEngine.View.Result(RouletteEngine.Shown(n, Roulette.colorOf(n)))
        ).count { it > 0 }

        // LEDs d'un numéro dans la police 5×7
        fun digits(n: Int) = n.toString().sumOf { c ->
            Sprites.DIGITS.getValue(c).sumOf { row -> row.count { it == '1' } }
        }
        // 32 rouge, 17 noir, 0 : ce qui dépasse du numéro est la barre
        assertEquals(digits(32) + 44, lit(32)) // 11 × 4 pleine
        assertEquals(digits(17) + 26, lit(17)) // 11 × 4 en contour
        assertEquals(digits(0), lit(0))        // le zéro, seul
    }

    @Test
    fun `table - l appui long change de jeu, refuse en plein tirage`() {
        val table = Table(Random(1))
        assertEquals(Game.SLOT, table.game)
        assertTrue(table.launch(0.0))
        assertFalse(table.switch(1.0))
        assertTrue(table.switch(Reels.STOPS[2] + 0.01))
        assertEquals(Game.ROULETTE, table.game)
        assertTrue(table.launch(10.0))
        assertFalse(table.switch(11.0))
    }

    @Test
    fun `angle de case - zero sous la phase`() {
        assertEquals(0, Roulette.pocketIndex(1.0, 1.0))
        assertEquals(1, Roulette.pocketIndex(1.0 + Roulette.POCKET, 1.0))
        assertEquals(Roulette.POCKETS - 1, Roulette.pocketIndex(1.0 - Roulette.POCKET, 1.0))
        assertTrue(abs(Roulette.POCKET * Roulette.POCKETS - 2 * PI) < 1e-12)
    }
}
