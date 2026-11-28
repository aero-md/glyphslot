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

- Ordre de la bande propre à chaque rouleau : `ORDER[i][slot] = slot·(i+1) mod 5`
  (rouleau 0 : `[SEVEN, CHERRY, BAR, DIAMOND, BELL]`) — détails et propriété
  d'alignement dans [SPECS-ANNEXE.md](SPECS-ANNEXE.md).
- Défilement haut → bas : la ligne affichée en y de la fenêtre est
  `stripRow = mod(y - PAY_TOP - round(offset), 45)`.
- Symbole k aligné sur la payline ⇔ `offset ≡ -9·SLOT[reel][k] (mod 45)`.
- Lignes hors payline : luminosité ×0,2 (symboles voisins visibles).

## Plan de spin (pré-calculé à la secousse)

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
IDLE ──secousse──▶ SPINNING ──▶ R1_STOP ──▶ R2_STOP ──▶ R3_STOP ──▶ RESULT ──▶ IDLE
```

- `RESULT` : `lose` (~1 s), `win` (~2,8 s), `jackpot` (~7,5 s), puis retour IDLE
  avec les symboles finaux affichés.
- **Relance dès l'arrêt du dernier rouleau** (`t ≥ STOPS[2]`) : en `R3_STOP`
  comme en `RESULT`, une secousse coupe les effets et repart. Relancé avant
  l'annonce du résultat (fenêtre `SETTLE`), le tirage précédent est d'abord
  conclu — chaque spin émet ses trois `ReelStopped` et son `SpinFinished`.

## Roulette — le mode alternatif

Appui long : la machine à sous laisse la place à une roulette européenne vue du
dessus, et inversement. Moteur dans `engine/Roulette.kt` + `RouletteEngine.kt`,
rendu dans `render/RouletteRenderer.kt` — port de la préview du portail
(`glyph-portal/src/routes/glyphslot/lib/`), mêmes constantes, les deux se
corrigent ensemble.

| Élément | Rendu |
|---|---|
| Piste | contour du disque, lavis fixe (0,03) — la cuvette ne tourne pas |
| Couronne | 37 cases entre `R − 3,9` et `R − 1,6`, ordre du cylindre européen, supersamplée 2×2 |
| Case rouge / noire / zéro | 0,22 / 0,03 / 0,6 — le zéro est le repère qui fait voir le cylindre tourner |
| Moyeu | fixe, en LEDs pleines (sans anticrénelage) : point central en croix de 5 à 0,45, cercle de rayon 4 à 0,22 |
| Bille | 1,0, traînée de 50 ms ; sur la piste elle suit le contour du disque |
| Résultat | numéro en police 5×7, barre 11×4 dessous : **pleine = rouge, contour = noir**, rien pour le zéro |

Rouge / noir n'est jamais écrit : la matrice est monochrome, plein = rouge,
creux = noir, sur le cylindre comme sous le numéro.

Chronologie d'un lancer (s) :

| t | Événement |
|---:|---|
| 0 | secousse : bille lancée à 1,85 tr/s (antihoraire), cylindre à 0,42 tr/s (horaire) |
| 3,3 | la bille quitte la piste et descend le cône |
| 4,05 | premier rebond dans les cases |
| `tLand` | posée — **1 à 5 rebonds tirés au hasard**, hauteur ≥ 1 cellule, durée 0,28·√h |
| `tLand` + 0,6 | la case gagnante a clignoté ; le cylindre s'éteint (0,35 s), le numéro s'allume (0,35 s) |
| + 8 | le numéro s'éteint (0,35 s), le cylindre revient tourner au ralenti (0,35 s) |

- Numéro **tiré à la secousse** (uniforme sur 37), trajectoire relative
  bille/cylindre intégrée puis recalée pour tomber pile sur la case.
- Relance permise **dès que la bille est posée**, comme la machine à sous —
  y compris pendant le retour au repos, dont le fondu reprend où il en est.
- Au repos le cylindre tourne doucement (0,05 tr/s), sans bille. Un résultat
  reste affiché **8 s** (`Roulette.HOLD`), puis la table se rouvre ; le
  cylindre a continué de tourner derrière le numéro.
- Haptique : tick à chaque rebond, clic appuyé à la pose.

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
engine/   SlotEngine.kt, Reels.kt,
          Roulette.kt, RouletteEngine.kt,
          Table.kt                  — logique pure, testable JUnit, sans SDK
render/   MatrixRenderer.kt, Sprites.kt, Effects.kt,
          RouletteRenderer.kt       — IntArray(625)/frame
toy/      SlotToyService.kt, Shaker.kt — seul module dépendant du GlyphMatrixSDK
MainActivity.kt                     — préview Compose 25×25 + réglages
```

## Interaction

| Event | Action |
|-------|--------|
| Secousse du téléphone | Lancer le jeu affiché (ignorée tant qu'un rouleau tourne / que la bille roule) |
| Short press | Système : cycle entre les toys |
| Long press (« change ») | Passer de la machine à sous à la roulette et retour (refusé pendant un tirage : double tick) |
| `onUnbind` | Stop boucle + capteur + extinction matrice |

La secousse : accéléromètre (réveillant si l'appareil en a un, aucun sur le
Phone (3)), gravité soustraite par moyenne glissante, seuil 14 m/s², temps mort
550 ms — mêmes réglages que le dé de Haloglyph. Le jeu choisi est retenu d'une
session à l'autre (`SharedPreferences` « glyphslot »).

## Icône

`glyphslot-icon.svg` : cerise pixel-art en grand sur la matrice circulaire
(berries pleines, tiges atténuées, halo sur LEDs allumées), fond adaptive icon sombre.
