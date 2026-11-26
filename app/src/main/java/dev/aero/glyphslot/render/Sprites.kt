package dev.aero.glyphslot.render

/**
 * Sprites 7×7 et police du bandeau — données pures.
 * Niveaux : '.' éteint, '1' ≈ 45 %, '2' = 100 %.
 */
object Sprites {

    /** Indices dans la bande. */
    const val SEVEN = 0
    const val CHERRY = 1
    const val BAR = 2
    const val DIAMOND = 3
    const val BELL = 4

    private val RAW = arrayOf(
        // SEVEN
        arrayOf(
            "2222222",
            "......2",
            ".....2.",
            "....2..",
            "...2...",
            "..2....",
            "..2....",
        ),
        // CHERRY
        arrayOf(
            "...1...",
            "..1.1..",
            ".1...1.",
            ".2...2.",
            "222.222",
            "222.222",
            ".2...2.",
        ),
        // BAR
        arrayOf(
            "2222222",
            "2222222",
            ".......",
            "2222222",
            "2222222",
            ".......",
            "2222222",
        ),
        // DIAMOND
        arrayOf(
            "...2...",
            "..222..",
            ".22222.",
            "2222222",
            ".22222.",
            "..222..",
            "...2...",
        ),
        // BELL
        arrayOf(
            "...2...",
            "..222..",
            ".22222.",
            ".22222.",
            ".22222.",
            "2222222",
            "...2...",
        ),
    )

    private fun level(c: Char): Float = when (c) {
        '1' -> 0.45f
        '2' -> 1f
        else -> 0f
    }

    /** STRIP[symbole][ligne][colonne] → luminosité 0..1. Ordre de la bande. */
    val STRIP: Array<Array<FloatArray>> = Array(RAW.size) { s ->
        Array(7) { r -> FloatArray(7) { c -> level(RAW[s][r][c]) } }
    }

    /* Police 5×7 pour le bandeau JACKPOT */
    private val FONT = mapOf(
        'J' to arrayOf("22222", "...2.", "...2.", "...2.", "2..2.", "2..2.", ".22.."),
        'A' to arrayOf(".222.", "2...2", "2...2", "22222", "2...2", "2...2", "2...2"),
        'C' to arrayOf(".2222", "2....", "2....", "2....", "2....", "2....", ".2222"),
        'K' to arrayOf("2...2", "2..2.", "2.2..", "22...", "2.2..", "2..2.", "2...2"),
        'P' to arrayOf("2222.", "2...2", "2...2", "2222.", "2....", "2....", "2...."),
        'O' to arrayOf(".222.", "2...2", "2...2", "2...2", "2...2", "2...2", ".222."),
        'T' to arrayOf("22222", "..2..", "..2..", "..2..", "..2..", "..2..", "..2.."),
    )

    /** Bandeau « JACKPOT » : 7 lignes, lettres séparées d'une colonne vide (41 colonnes). */
    val BANNER: Array<BooleanArray> = Array(7) { r ->
        val row = "JACKPOT".map { FONT.getValue(it)[r] }.joinToString(".")
        BooleanArray(row.length) { c -> row[c] == '2' }
    }

    val BANNER_W = BANNER[0].size

    /**
     * Chiffres 5×7 du numéro de la roulette — la police `F5` du portail, au point
     * près, pour que la préview web et le toy écrivent le même numéro.
     */
    val DIGITS: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
        '3' to arrayOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    )
}
