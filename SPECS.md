# glyphslot — Spécifications techniques

## Constantes

| Nom | Valeur | Description |
|-----|-------:|-------------|
| `SIZE` | 25 | Matrice 25×25 |
| `RADIUS` | 12,5 | Masque circulaire centré (12,12) — ~489 LEDs |
| `COLS` | 1, 9, 17 | x de départ des 3 rouleaux (7 px de large) |
| `PAY_TOP` / `PAY_BOT` | 9 / 15 | Fenêtre payline (7 lignes) |
| `SYM_H` | 9 | 7 px symbole + 2 px de blanc |
| `STRIP_LEN` | 45 | 5 symboles × 9 lignes, bande bouclée |
| `STOPS` | 2,6 / 3,8 / 4,9 s | Arrêts en cascade des rouleaux |
| `DECEL` | 1,3 s | Durée de décélération par rouleau |
| `V` | 34 lignes/s | Vitesse plein régime |
| FPS | 30 | Boucle de rendu (33 ms) |

## Bande & offsets

- Ordre de la bande : `[SEVEN, CHERRY, BAR, DIAMOND, BELL]`.
- Défilement haut → bas : la ligne affichée en y de la fenêtre est
  `stripRow = mod(y - PAY_TOP - round(offset), 45)`.
- Symbole k aligné sur la payline ⇔ `offset ≡ -9k (mod 45)`.
- Lignes hors payline : luminosité ×0,2 (symboles voisins visibles).

## Plan de spin (pré-calculé à l'appui long)

1. Tirage du résultat `[k1, k2, k3]` :
   - 5 % triple 7, 15 % triple autre symbole, sinon tirage sans triple.
2. Pour chaque rouleau i :
   - `t1 = STOPS[i] - DECEL`, `o1 = off0 + V·t1`
   - distance de décélération `d = 30 + mod(target - (o1+30), 45)` ∈ [30, 75)
   - `oF = o1 + d`
3. `offset(t)` :
   - `t < 0,45 s` : **armement du ressort** — recul lent en sens inverse,
     `off0 − 5·sin(π/2 · t/0,45)` (les symboles remontent doucement)
   - `0,45 ≤ t < 0,75 s` : **détente** — Hermite de `off0 − 5` (vitesse 0)
     vers la trajectoire linéaire, tangente finale = V (lancer violent)
   - `t < t1` : linéaire `off0 + V·t`
   - `t1 ≤ t < STOPS[i]` : Hermite `h(u)` avec `p0=o1`, `p1=oF`,
     tangente initiale `m0 = V·DECEL`, tangente finale 0
     → continuité de vitesse à l'entrée, arrêt net avec léger settle.
   - ensuite : `oF` (≡ target mod 45).

## Sprites 7×7

Niveaux : `.` éteint, `1` ≈ 45 %, `2` = 100 %.

```
SEVEN      CHERRY     BAR        DIAMOND    BELL
2222222    ...1...    2222222    ...2...    ...2...
......2    ..1.1..    2222222    ..222..    ..222..
.....2.    .1...1.    .......    .22222.    .22222.
....2..    .2...2.    2222222    2222222    .22222.
...2...    222.222    2222222    .22222.    .22222.
..2....    222.222    .......    ..222..    2222222
..2....    .2...2.    2222222    ...2...    ...2...
```

## Machine à états

```
IDLE ──appui long──▶ SPINNING ──▶ R1_STOP ──▶ R2_STOP ──▶ R3_STOP ──▶ RESULT ──▶ IDLE
```

- `RESULT` : `lose` (~1 s), `win` (~2,8 s), `jackpot` (~4 s), puis retour IDLE
  avec les symboles finaux affichés.

## Effets

- **win (×3)** : luminosité des symboles modulée
  `0,3 + 0,7·(0,5 + 0,5·sin(6π·t))` + anneau périphérique pulsé (d > 11,3), ~2,8 s.
- **jackpot (777)** — chorégraphie ~7,5 s en 5 phases :
  1. *0–0,6 s* : triple strobe plein disque (flashs décroissants) + shake.
  2. *0,25–1,3 s* : 3 ondes de choc concentriques (r = 14·t, largeur 1 px).
  3. *1,3–4,1 s* : bandeau « JACKPOT » en gros (police 5×7 sur la payline),
     défilement droite → gauche sur écran dédié.
  4. *3,9–5,4 s* : 7 feux d'artifice à positions aléatoires, 12 particules
     chacun avec gravité (2,5 cell/s²) + shake léger à chaque burst.
  5. *5,4–7,3 s* : SEPT GÉANT — écran dédié, zoom 1 → 2,7 (ease-out 0,45 s),
     pulse rapide, fade-out final.
  - Scintillement continu (36 LEDs aléatoires) de 1,0 à 7,2 s.
- Haptique : tick à chaque arrêt de rouleau, pattern long sur jackpot.

## Architecture

```
engine/   SlotEngine.kt, Reels.kt   — logique pure, testable JUnit, sans SDK
render/   MatrixRenderer.kt, Sprites.kt, Effects.kt — IntArray(625)/frame
toy/      SlotToyService.kt         — seul module dépendant du GlyphMatrixSDK
MainActivity.kt                     — préview Compose 25×25 + réglages
```

## Interaction Glyph Button

| Event | Action |
|-------|--------|
| Short press | Système : cycle entre les toys |
| Long press (« change ») | Lancer le spin (ignoré si spin en cours) |
| `onUnbind` | Stop boucle + extinction matrice |

## Icône

`glyphslot-icon.svg` : cerise pixel-art en grand sur la matrice circulaire
(berries pleines, tiges atténuées, halo sur LEDs allumées), fond adaptive icon sombre.
