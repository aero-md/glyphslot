# glyphslot — Spécifications de la préview web

La préview reproduit le toy dans un navigateur, **posé sur une photo du dos
d'un Nothing Phone (3)** : la Glyph Matrix est rendue à sa position et à son
échelle réelles, et le bouton d'interaction est calé sur le Glyph Button
physique. La cinématique, les sprites et les constantes du moteur sont
partagés avec le toy — voir [SPECS.md](SPECS.md).

## Fichiers

| Fichier | Rôle |
|---|---|
| [`glyph-slot-preview.jsx`](glyph-slot-preview.jsx) | Source unique de vérité — composant React complet |
| [`docs/index.html`](docs/index.html) | Page GitHub Pages : charge le `.jsx` depuis `main`, adapte l'ESM au React UMD, transpile via Babel standalone |
| [`docs/phone3-back.webp`](docs/phone3-back.webp) | Photo du dos, détourée et rognée (704 × 913, ~52 Ko) |

Publication : GitHub Pages en mode *legacy*, source `main` + `/docs` → **le
push suffit**, aucun workflow à déclencher.

## Photo du dos

Source 2048 × 2048 avec canal alpha, détourée au corps du téléphone
(845 × 1768 px), puis rognée aux **62 % supérieurs** — les 40 % du bas ne sont
jamais rendus. Réduite à 704 px de large, encodée en WebP q0.78.

Toutes les positions sont exprimées en **pourcentage du cadre photo**, jamais
en pixels : c'est ce qui garde le calage quand la préview est redimensionnée.

| Constante | Valeur | Description |
|---|---:|---|
| `PH_W` / `PH_H` | 704 / 913 | Dimensions de l'asset — servent au ratio du cadre |
| `MX.x` / `MX.y` | 79,53 % / 15,36 % | Centre du disque de la Glyph Matrix |
| `MX.d` | 26,04 % | Diamètre du disque, en % de la largeur |
| `BT.x` / `BT.y` | 84,53 % / 74,82 % | Centre du Glyph Button |
| `BT.d` | 15,86 % | Diamètre du bouton |

Relevés par superposition de cercles sur la photo à l'échelle native, puis
affinés visuellement. Pour changer de photo : refaire le relevé et mettre à
jour ces six valeurs, rien d'autre ne bouge.

La coupe basse se fait par `mask-image` sur l'`<img>` (fondu 86 % → 99 %) et
non par un aplat superposé, sinon la trame de fond disparaîtrait sur la bande.

## Grille LED : pixels entiers

Un canvas de taille fixe redimensionné par le navigateur avec un ratio
fractionnaire produit une trame irrégulière — une colonne sur n gagne un pixel
de gap. La grille est donc calculée depuis le `devicePixelRatio`, et la taille
du téléphone en découle :

```js
const CELL   = Math.max(3, Math.round(6 * DPR));      // px de canvas par LED
const LED    = Math.max(2, Math.round(CELL * 2 / 3)); // côté du carré allumé
const CANVAS = SIZE * CELL;                           // 150 @1x, 300 @2x
const MX_PX  = SIZE * 6;                              // 150 px CSS
const PHONE_W = `min(${Math.round(MX_PX / (MX.d / 100))}px, 100%)`; // 576 px
```

Mesuré : `backing 150×150`, `cssBox 149,98` → **ratio 1,0001**, pas de
rééchantillonnage. Exact pour dpr 1 / 1,5 / 2 / 3 ; approché pour les dpr en
quarts. Sous 576 px de large, la mise en page redevient proportionnelle et
l'irrégularité peut réapparaître.

Le téléphone mesure donc **576 × 747 px**, la matrice **150 px** de diamètre —
soit l'échelle réelle du composant sur un écran classique.

L'amplitude du tremblement jackpot est exprimée en fractions de cellule
(`CELL/4`, `CELL/6`) pour rester constante quelle que soit la grille.

## Interaction

Le bouton est un `<button>` transparent superposé au Glyph Button de la photo,
avec un glow jaune Nothing (`255,229,0`, constante `YEL`) :

| État | Rendu |
|---|---|
| repos | pulse `glyphPulse` 2,2 s |
| survol | même pulse à 1,1 s + halo radial |
| pressé | anneau plein, halo large, ombre interne |
| occupé (`busy`) | animation coupée |

Un appui de **450 ms** déclenche le spin. Relâché avant, ou pointeur sorti du
bouton en cours d'appui, un rappel `APPUI LONG →` apparaît à gauche du bouton
pendant 1,9 s et le pulse passe à 0,7 s. Les refs `down` / `fired` distinguent
les deux cas ; les timers sont nettoyés au démontage.

Le `background` du bouton est porté par la classe CSS, jamais en style inline —
un style inline gagnerait sur les règles `:hover` et `.is-pressed`.

### Tactile

Sans précautions, l'appui long mobile déclenche l'appui long *système* — sur
Chrome Android, le menu contextuel de l'image du dos, que le bouton recouvre.
Quatre verrous, tous nécessaires :

| Verrou | Rôle |
|---|---|
| `pointer-events: none` sur l'`<img>` | la photo sort du hit-test : plus de menu « enregistrer l'image » |
| `touch-action: none` sur le bouton | le navigateur ne prend pas l'appui pour un début de scroll et ne vole pas le geste |
| `-webkit-touch-callout` + `user-select: none` | pas de callout iOS, pas de sélection de texte |
| `preventDefault` sur `touchstart` et `contextmenu` | désamorce l'appui long système avant nos 450 ms |

Le listener `touchstart` est attaché **nativement** (`{passive:false}`) via
`btnRef` : React attache `onTouchStart` en passive, où `preventDefault` est sans
effet. `pointerdown` est émis avant `touchstart`, la machine à états est donc
intacte.

`onPointerCancel` remet l'état à zéro **sans** afficher le rappel : le geste a
été repris par le navigateur, l'utilisateur n'a rien fait de travers.

Le déclenchement s'accompagne d'un `navigator.vibrate(12)` là où c'est supporté.

## Fond

Gris très sombre légèrement bleuté + trame de points, façon DA Nothing.

| Constante | Valeur |
|---|---|
| `BG` | `#0C0D10` |
| point | carré 2 × 2 px, `#9AA3C8` à 22 % |
| `DOT_GAP` | 132 px |

Les points sont un SVG en data-URI, pas un `radial-gradient` : à ce rayon un
gradient serait antialiasé et donnerait des ronds flous. `background-attachment:
fixed` — la trame ne défile pas avec le contenu.

Le conteneur racine porte `box-sizing: border-box`. Sans lui, `min-height:
100vh` s'additionne au padding vertical et la page déborde de 68 px : une barre
de scroll apparaît même quand tout tient à l'écran. Hauteur naturelle du
contenu : **1157 px**.

## Contraintes de publication

`docs/index.html` charge le `.jsx` depuis `raw.githubusercontent.com` : les
modifications ne sont visibles sur Pages qu'une fois **poussées sur `main`**.

`PHONE_SRC` est surchargeable via `window.GLYPH_PHONE_SRC`, ce qui permet
d'inliner la photo en base64 pour les environnements sans réseau (pages à
Content-Security-Policy stricte). Dans ce cas, la police Space Mono doit aussi
être inlinée en `@font-face` data-URI, le `@import` Google Fonts étant bloqué.
