# GlyphSlot

Glyph Toy « machine à sous » pour la Glyph Matrix du Nothing Phone (3).
Appui long sur le Glyph Button → spin de 5 s avec effet ressort, arrêts en
cascade et effets de victoire (pulse ×3, chorégraphie jackpot 777).

Specs détaillées : [SPECS.md](SPECS.md) · plan : [PLAN.md](PLAN.md) ·
prototype web de référence : [glyph-slot-preview.jsx](glyph-slot-preview.jsx).

## Build

- Android Studio (ou `./gradlew assembleDebug`), JDK 17, minSdk 34.
- Sans Android Studio : installer les
  [commandline-tools](https://developer.android.com/studio#command-line-tools-only)
  (`sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`,
  puis `sdkmanager --licenses`) et indiquer le SDK via un `local.properties`
  à la racine du projet avec `sdk.dir=C:\\chemin\\vers\\le\\sdk`
  (ou la variable d'environnement `ANDROID_HOME`). `local.properties` est
  propre à la machine — jamais versionné (.gitignore).
- `GlyphMatrixSDK.aar` est **téléchargé automatiquement au premier build**
  (tâche `downloadGlyphSdk`, hook sur `preBuild`) dans `app/libs/` — non versionné.

## Test sur appareil

1. Installer sur un Phone (3) : `./gradlew installDebug`.
2. Activer le toy : **Settings → Glyph Interface → Glyph Toys → Glyph Slot**.
3. Mode debug de la matrice (48 h) :
   `adb shell settings put global nt_glyph_interface_debug_enable 1`
4. Pour itérer sans retourner le téléphone : la préview Compose 25×25 utilise
   exactement le même moteur et le même renderer que le toy (boutons Tirage /
   Forcer ×3 / Forcer 777). L'icône launcher n'existe qu'en **debug** ; en
   release l'app n'a pas de raccourci (le toy vit dans Glyph Interface).
   Lancement direct si besoin :
   `adb shell am start -n dev.aero.glyphslot/.MainActivity`

## Architecture

```
engine/   SlotEngine.kt, Reels.kt        logique pure, testable JUnit, sans SDK
render/   MatrixRenderer.kt, Sprites.kt,
          Effects.kt                     IntArray(625) par frame, sans SDK
toy/      SlotToyService.kt,
          GlyphMatrixService.kt          seul module dépendant du GlyphMatrixSDK
MainActivity.kt                          préview Compose 25×25
```

Le temps est injecté dans `SlotEngine` (secondes monotones) : la machine à
états et la cinématique des rouleaux se testent en JVM pure
(`./gradlew test`).

## Interaction

| Event | Action |
|-------|--------|
| Appui court | Système : cycle entre les toys |
| Appui long (« change ») | Lancer le spin (ignoré si spin en cours) |
| `onUnbind` | Stop boucle + extinction matrice |

## Licence

[MIT](LICENSE)
