# glyphslot — Annexe technique

Détails d'implémentation issus du debug sur appareil (Nothing Phone (3)).
Specs principales : [SPECS.md](SPECS.md).

## Luminosité de la Glyph Matrix

- `GlyphMatrixManager.setMatrixFrame(int[])` attend des luminosités **0..4095**,
  pas 0..255. Constantes internes du SDK (`GlyphMatrixUtils`, vérifiées par
  décompilation de `GlyphMatrixSDK.aar`) : `BRIGHTNESS_MULTIPLIER = 16`,
  `MAX_BRIGHTNESS = 4095`, `ON = 4095`.
- La conversion officielle (`rgbaToGrayscale`) : gris 0..255 (Rec. 601,
  299/587/114), seuil soustrait, clamp 0..255, puis **×16** → 0..4080.
- Chez nous : `MatrixRenderer` reste en 0..255 (partagé avec la préview Compose
  qui divise par 255) ; `SlotToyService.push()` applique le ×16 dans un buffer
  réutilisé (pas d'allocation à 30 fps). Envoyer du 0..255 brut donne ~6 % de
  la puissance max (matrice terne — symptôme observé).

## Ordre des bandes par rouleau

- `Reels.ORDER[i][slot] = slot × (i+1) mod 5` : les 3 rouleaux portent les
  mêmes 5 symboles mais avec un pas différent (+1, +2, +3). 5 étant premier,
  chaque bande est une permutation complète.
- Propriété garantie (test `voisins jamais alignes quand la payline est
  identique`) : quand un même symbole est aligné sur la payline des 3 rouleaux,
  ses voisins haut/bas diffèrent d'un rouleau à l'autre — aucun alignement
  hors zone centrale.
- `targetOffset(reel, k)` passe par `SLOT` (inverse de `ORDER`) :
  `offset ≡ -9·SLOT[reel][k] (mod 45)`.

## Icône de preview du toy (`res/drawable/toy_preview.xml`)

Générée depuis les calques LED de `glyphslot-icon.svg` (512×512, pitch 17,9,
LEDs 11,5 px), en excluant : fond adaptive-icon, liserés/bordures, halo flou
(`feGaussianBlur` non supporté par VectorDrawable).

Ajustements calés visuellement sur les previews des Glyph Toys système :

| Paramètre | Valeur | Pourquoi |
|-----------|-------:|----------|
| Taille intrinsèque | 192 dp | La liste des toys **rasterise le drawable à sa taille intrinsèque** puis agrandit le bitmap → 48 dp rend flou |
| Fond de plaque | `#000000` | Noir pur, comme le rendu système |
| LEDs éteintes | blanc, `fillAlpha=0.12` | Visibles comme sur les previews système (0,05 du SVG trop sombre) |
| LEDs atténuées | `#B9B9B4`, `fillAlpha=0.55` | Repris tel quel du SVG (tiges + reflets) |
| Taille des LEDs | 13,0 (pitch 17,9 inchangé) | Gaps visuellement trop grands à 11,5 (remplissage 64 % → 73 %), recentrées de −0,75 |
| Scale global | `0.995` (groupe pivot 256,256) | Disque un poil plus large que les icônes système à 1,0, trop petit à 0,99 |

Piège aapt2 : une chaîne de ressource est limitée à **32 Ko UTF-8**
(`STRING_TOO_LARGE`, non bloquant au build mais le path est vidé). Les 339 LEDs
éteintes sont donc des carrés simples (`h13v13`) et non des rects arrondis ;
les coins arrondis (r=1,5) ne sont gardés que sur les calques atténué/plein.

## Debug ADB sous Windows

- Le pare-feu Windows en mode « stealth » **jette silencieusement** les SYN
  vers les ports loopback fermés (pas de RST) : un client `adb` qui tente
  `127.0.0.1:5037` sans serveur actif reste bloqué en `SYN_SENT` au lieu
  d'échouer et de lancer le serveur. Des processus `adb` zombies s'accumulent.
- Remède : tuer tous les `adb.exe`, puis `adb.exe nodaemon server` (ou
  `adb start-server`) pour créer le listener avant tout client.
- Mode debug Glyph (clé `NothingKey=test`, 48 h) :
  `adb shell settings put global nt_glyph_interface_debug_enable 1`.

## Clé API Nothing — restriction levée sur Android 16

- Le README du [Glyph-Developer-Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit)
  indique : « The API key restriction has been removed starting from Android B
  (Android 16). You no longer need to apply for an API key from Nothing if your
  application is targeting this version or later », en recommandant de garder
  le meta-data `NothingKey` pour la compatibilité.
- **Vérifié le 16/07/2026 sur Phone (3) / Nothing OS Android 16** : avec
  `targetSdk = 36` et `nt_glyph_interface_debug_enable = 0`, le toy fonctionne
  avec `NothingKey=test` — la levée s'applique aussi au GlyphMatrix SDK.
- Conséquence : `targetSdk = 36` requis (d'où AGP 8.9.2 + Gradle 8.11.1) ;
  le meta-data `NothingKey=test` est conservé dans le manifest.
