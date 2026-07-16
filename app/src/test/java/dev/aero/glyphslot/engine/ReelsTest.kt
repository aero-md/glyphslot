package dev.aero.glyphslot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelsTest {

    @Test
    fun `targetOffset aligne le symbole k sur la payline`() {
        // le symbole au slot s est aligné quand offset ≡ -9s (mod 45)
        for (reel in 0 until 3) {
            for (slot in 0 until Reels.SYMBOL_COUNT) {
                val k = Reels.ORDER[reel][slot]
                assertEquals(
                    "reel=$reel slot=$slot k=$k",
                    Reels.mod(-(Reels.SYM_H * slot).toDouble(), Reels.STRIP_LEN.toDouble()),
                    Reels.targetOffset(reel, k),
                    1e-9,
                )
            }
        }
    }

    @Test
    fun `chaque bande contient les 5 symboles et les ordres different entre rouleaux`() {
        for (reel in 0 until 3) {
            assertEquals(
                "reel=$reel",
                (0 until Reels.SYMBOL_COUNT).toSet(),
                Reels.ORDER[reel].toSet(),
            )
        }
        assertTrue(!Reels.ORDER[0].contentEquals(Reels.ORDER[1]))
        assertTrue(!Reels.ORDER[1].contentEquals(Reels.ORDER[2]))
        assertTrue(!Reels.ORDER[0].contentEquals(Reels.ORDER[2]))
    }

    @Test
    fun `voisins jamais alignes quand la payline est identique`() {
        // pour tout symbole k aligné sur les 3 rouleaux, les symboles au-dessus
        // (slot-1) et en-dessous (slot+1) sont distincts d'un rouleau à l'autre
        val n = Reels.SYMBOL_COUNT
        for (k in 0 until n) {
            for (delta in intArrayOf(-1, 1)) {
                val neighbors = (0 until 3).map { reel ->
                    val slot = Reels.ORDER[reel].indexOf(k)
                    Reels.ORDER[reel][Reels.mod(slot + delta, n)]
                }
                assertEquals("k=$k delta=$delta voisins=$neighbors", 3, neighbors.toSet().size)
            }
        }
    }

    @Test
    fun `le plan atterrit pile sur le symbole cible pour toutes les combinaisons`() {
        for (reel in 0 until 3) {
            for (k0 in 0 until Reels.SYMBOL_COUNT) {
                for (k in 0 until Reels.SYMBOL_COUNT) {
                    for (tStop in Reels.STOPS) {
                        val p = Reels.makePlan(reel, Reels.targetOffset(reel, k0), k, tStop)
                        assertEquals(
                            "reel=$reel k0=$k0 k=$k tStop=$tStop",
                            Reels.targetOffset(reel, k),
                            Reels.mod(p.oF, Reels.STRIP_LEN.toDouble()),
                            1e-9,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `distance de deceleration dans l intervalle 30 a 75`() {
        for (reel in 0 until 3) {
            for (k0 in 0 until Reels.SYMBOL_COUNT) {
                for (k in 0 until Reels.SYMBOL_COUNT) {
                    val p = Reels.makePlan(reel, Reels.targetOffset(reel, k0), k, Reels.STOPS[2])
                    val d = p.oF - p.o1
                    assertTrue("d=$d", d >= 30.0 && d < 75.0)
                }
            }
        }
    }

    @Test
    fun `offset continu aux frontieres de phases`() {
        val p = Reels.makePlan(0, Reels.targetOffset(0, 2), 3, Reels.STOPS[2])
        val eps = 1e-6
        for (tb in doubleArrayOf(Reels.T_PULL, Reels.T_LAUNCH, p.t1, p.tStop)) {
            val before = Reels.offsetAt(p, tb - eps)
            val after = Reels.offsetAt(p, tb + eps)
            assertEquals("t=$tb", before, after, 1e-3)
        }
    }

    @Test
    fun `vitesse continue a l entree en deceleration`() {
        val p = Reels.makePlan(0, Reels.targetOffset(0, 1), 4, Reels.STOPS[1])
        val h = 1e-4
        val v = (Reels.offsetAt(p, p.t1 + h) - Reels.offsetAt(p, p.t1 - h)) / (2 * h)
        assertEquals(Reels.V, v, 0.5)
    }

    @Test
    fun `armement du ressort - recul de 5 lignes puis retour`() {
        val p = Reels.makePlan(0, Reels.targetOffset(0, 0), 2, Reels.STOPS[0])
        // à t=0 : position de repos
        assertEquals(p.off0, Reels.offsetAt(p, 0.0), 1e-9)
        // recul maximal juste avant la détente
        assertEquals(p.off0 - Reels.PULL, Reels.offsetAt(p, Reels.T_PULL - 1e-6), 1e-3)
        // le recul est monotone pendant l'armement
        var prev = Reels.offsetAt(p, 0.0)
        var t = 0.02
        while (t < Reels.T_PULL) {
            val cur = Reels.offsetAt(p, t)
            assertTrue("recul non monotone à t=$t", cur <= prev + 1e-9)
            prev = cur
            t += 0.02
        }
    }

    @Test
    fun `arret net - offset fige apres tStop`() {
        val p = Reels.makePlan(0, Reels.targetOffset(0, 3), 0, Reels.STOPS[2])
        assertEquals(p.oF, Reels.offsetAt(p, p.tStop), 1e-9)
        assertEquals(p.oF, Reels.offsetAt(p, p.tStop + 10.0), 1e-9)
    }
}
