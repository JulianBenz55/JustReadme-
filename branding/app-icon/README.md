# App icon

Single, fixed app icon: the Vellum logo (four brackets, four rhombi, a cube) on the app's
dark surface colour. Generated — do not hand-edit the outputs here.

```sh
python3 scripts/generate_app_icons.py     # needs Pillow only; no browser
```

The source is `source/logo-transparent.svg` (`source/logo.svg` is the same artwork on black).
The logo is flat-coloured, so every Android layer is a VectorDrawable — no gradient, no
raster foreground.

## Colour

| Where | Colour |
| --- | --- |
| Launcher field | `#111019` — the app's dark surface (`backgroundDark` in `Color.kt`) |
| Launcher logo | the logo's own colours (brackets `#F2F0F6`, rhombi `#7636F6`, cube `#B586FB / #9F63FB / #5325C1`) |
| Android 13+ themed icon | one tint chosen by the launcher; the cube faces keep alpha 1 / 0.62 / 0.38 so they stay apart |
| **In-app icon** (`AppIcon`) | repainted in the app theme at runtime: brackets = `onSurface`; rhombi and cube = the theme primary's hue and saturation at the artwork's own lightness; plate = `surfaceVariant`. Follows light/dark and dynamic/custom seed colours |

A launcher icon cannot follow the app theme on its own; the themed (monochrome) layer is the
launcher's mechanism for that.

## Outputs

| File | Use |
| --- | --- |
| `vellum-icon.svg` | 1024 square master — iOS, desktop, stores |
| `vellum-icon-android-{background,foreground,monochrome}.svg` | 108vp adaptive layers |
| `ic_launcher_{background,foreground,monochrome}.xml` | the same layers as VectorDrawables (deployed to `app/src/main/res/drawable/`) |
| `AppIconArtwork.kt` | the logo as Kotlin for the in-app icon (deployed to `shared/.../ui/`) |
| `png/ios/` | 14 AppIcon slots, opaque, no baked rounding |
| `png/android/mipmap-*/` | legacy `ic_launcher` (squircle) + `ic_launcher_round` (circle), deployed to `res/` |
| `png/desktop/` | 16–1024 PNG ladder, `vellum.ico`, `vellum.icns` |
| `png/store/` | `play-store-512.png`, `app-store-1024.png` |
| `png/branding/` | README header + favicon ladder |
| `preview/android-masks.png` | adaptive icon under four masks + themed glyph |

## Geometry

The logo's bounding box is measured from the path data, not hard-coded. It is placed by a
uniform scale so its farthest point sits 32.5dp from the centre of the 108dp viewport, inside
the 33dp safe circle every launcher mask preserves. `AndroidLauncherIconContractTest` asserts
this, plus: three layers declared, background is one full-viewport solid fill, monochrome is a
single flat colour with no field, in-app artwork carries the same 11 paths at the same scale.
