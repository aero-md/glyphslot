# glyphslot — Plan de développement

Glyph Toy « machine à sous » pour la Glyph Matrix du Nothing Phone (3).

## 1. Setup projet

- Android Studio, Kotlin, minSdk 34 (Android 14+ requis).
- `GlyphMatrixSDK.aar` (SDK 2.0) téléchargé automatiquement au premier build
  via une tâche Gradle (`preBuild`), non versionné (`.gitignore`).
- Base : repo `GlyphMatrix-Example-Project` de Nothing, wrapper Kotlin
  `GlyphMatrixService.kt` réutilisable.

## 2. Enregistrement du Glyph Toy

- `Service` déclaré dans le manifest avec l'intent-filter `com.nothing.glyph.TOY`.
- Meta-data : `toy.name`, `toy.image` (preview), `toy.summary`, `toy.longpress = 1`.
- Appui long sur le Glyph Button → event « change » reçu via Handler + Messenger
  (IBinder retourné par `onBind()`). C'est le déclencheur du lancer.

## 3. Design des rouleaux (25×25)

- Matrice circulaire (~489 LEDs utiles) : contenu dans le disque central.
- 3 colonnes de 7 px (x = 1, 9, 17), payline de 7 lignes au centre (y = 9–15),
  symboles voisins visibles atténués (×0,2).
- Sprites 7×7 monochromes : 7, cerise, BAR, diamant, cloche.
- Bande virtuelle : 5 symboles × 9 lignes (7 px + 2 de blanc) = 45 lignes, bouclée.
- État initial : tirage aléatoire, jamais 3 identiques.

## 4. Machine à états + timeline (5 s)

`IDLE → SPINNING → REEL1_STOP → REEL2_STOP → REEL3_STOP → RESULT → IDLE`

| t (s) | Événement |
|------:|-----------|
| 0     | Appui long : effet ressort — recul lent à l'envers (~0,45 s) |
| 0,45  | Détente : lancer violent, V ≈ 34 lignes/s atteint à 0,75 s |
| 2,6   | Rouleau 1 s'arrête (décélération 1,3 s) |
| 3,8   | Rouleau 2 s'arrête |
| 4,9   | Rouleau 3 s'arrête — total ≈ 5 s |

- Résultat **pré-tiré à l'appui long** (probas pondérées, triple 7 rare),
  offsets calculés pour que la décélération atterrisse pile dessus.
- Décélération : interpolation Hermite (vitesse initiale = V, finale = 0),
  distance ∈ [30, 75) lignes → léger « settle » authentique à l'arrêt.

## 5. Boucle de rendu

- Handler + `postDelayed` ~30 fps (33 ms).
- Frame = `IntArray(625)` ou Bitmap 25×25 via Canvas.
- Push : `GlyphMatrixObject.Builder().setImageSource(bitmap)` →
  `GlyphMatrixFrame.Builder().addTop()` → `setMatrixFrame()`.

## 6. Effets de victoire

- **3 identiques** : pulse des symboles + anneau clignotant, ~2,8 s.
- **Triple 7** : chorégraphie ~7,5 s — strobes, ondes de choc, bandeau
  « JACKPOT » défilant, feux d'artifice, sept géant zoomé (détail dans SPECS.md).
- Vibration synchronisée (tick à chaque arrêt, pattern long pour le jackpot).

## 7. Tests & debug

- Test sur appareil réel uniquement. Activation : Settings → Glyph Interface → Glyph Toys.
- Mode debug : `adb shell settings put global nt_glyph_interface_debug_enable 1` (48 h).
- Préview 25×25 dans l'app (Compose) pour itérer sans retourner le téléphone.
- `onUnbind` : stopper la boucle et éteindre la matrice (flicker/fuites).

## 8. Extensions (v2)

Crédits persistants, hold de rouleau, mode AOD, sons.
