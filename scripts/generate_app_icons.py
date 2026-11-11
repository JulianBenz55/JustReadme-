#!/usr/bin/env python3
"""Generate the Vellum app icon from branding/app-icon/source/logo-transparent.svg.

The logo is flat-coloured (four neutral brackets, four accent rhombi, a three-face
cube), so every Android layer ships as a VectorDrawable and no gradient is needed.

Outputs (all regenerated, safe to run repeatedly):

  branding/app-icon/vellum-icon*.svg            master + 108vp adaptive layers
  branding/app-icon/ic_launcher_*.xml           the three VectorDrawable layers
  branding/app-icon/AppIconArtwork.kt           the logo as Kotlin for the in-app icon
  branding/app-icon/png/...                     iOS / desktop / store / legacy rasters
  app/src/main/res/drawable/ic_launcher_*.xml   deployed copies
  app/src/main/res/mipmap-*/ic_launcher*.png    deployed legacy icons
  shared/.../ui/AppIconArtwork.kt               deployed in-app artwork

Rasterisation is done here with Pillow (supersampled polygons); no browser needed.
"""
from __future__ import annotations

import math
import pathlib
import re
import shutil

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "branding" / "app-icon"
SOURCE = ICON_DIR / "source" / "logo-transparent.svg"
RES = ROOT / "app" / "src" / "main" / "res"
ARTWORK_DEPLOY = ROOT / "shared/src/commonMain/kotlin/com/aryan/reader/shared/ui/AppIconArtwork.kt"

# The app's own dark surface (Color.kt backgroundDark): the launcher field matches the app.
BACKGROUND = "#111019"
ADAPTIVE_DP = 108.0
ADAPTIVE_REACH_DP = 32.5        # farthest ink from centre; safe circle is 33dp
# Monochrome alphas per element role: the launcher tints one colour, alpha keeps the cube readable.
MONO_ALPHA = {"neutral": 1.0, "accent": 1.0, "cube-top": 1.0, "cube-left": 0.62, "cube-right": 0.38}


def role_of(el_id: str) -> str:
    if el_id.startswith("klammer"):
        return "neutral"
    if el_id.startswith("raute"):
        return "accent"
    return {"wuerfel-oben": "cube-top", "wuerfel-links": "cube-left", "wuerfel-rechts": "cube-right"}[el_id]


def hex_rgb(h: str):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def parse_source():
    svg = SOURCE.read_text()
    out = []
    for el_id, fill, d in re.findall(r'<path id="([^"]+)" fill="([^"]+)" d="([^"]+)"', svg):
        out.append({"id": el_id, "fill": fill.upper(), "d": d, "role": role_of(el_id)})
    assert len(out) == 11, f"expected 11 paths, found {len(out)}"
    return out


def flatten(d: str, n: int = 16):
    toks = re.findall(r"[MLQZ]|-?\d+\.?\d*", d)
    polys, pts, cur, i = [], [], None, 0
    while i < len(toks):
        t = toks[i]
        if t == "M":
            cur = (float(toks[i + 1]), float(toks[i + 2])); pts = [cur]; i += 3
        elif t == "L":
            cur = (float(toks[i + 1]), float(toks[i + 2])); pts.append(cur); i += 3
        elif t == "Q":
            c = (float(toks[i + 1]), float(toks[i + 2])); e = (float(toks[i + 3]), float(toks[i + 4]))
            for k in range(1, n + 1):
                u = k / n
                pts.append(((1 - u) ** 2 * cur[0] + 2 * (1 - u) * u * c[0] + u * u * e[0],
                            (1 - u) ** 2 * cur[1] + 2 * (1 - u) * u * c[1] + u * u * e[1]))
            cur = e; i += 5
        elif t == "Z":
            polys.append(pts); pts = []; i += 1
        else:
            i += 1
    return polys


ELEMENTS = parse_source()
for _e in ELEMENTS:
    _e["polys"] = flatten(_e["d"])
_pts = [p for e in ELEMENTS for poly in e["polys"] for p in poly]
MIN_X, MAX_X = min(p[0] for p in _pts), max(p[0] for p in _pts)
MIN_Y, MAX_Y = min(p[1] for p in _pts), max(p[1] for p in _pts)
CX, CY = (MIN_X + MAX_X) / 2, (MIN_Y + MAX_Y) / 2
BBOX_W, BBOX_H = MAX_X - MIN_X, MAX_Y - MIN_Y
MAX_REACH = max(math.hypot(x - CX, y - CY) for x, y in _pts)      # farthest ink from centre, source units
SCALE_DP = ADAPTIVE_REACH_DP / MAX_REACH                           # source unit -> dp in the 108dp viewport


def fmt(v: float, digits: int = 4) -> str:
    s = f"{v:.{digits}f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ------------------------------------------------------------------ rasteriser
def render(px: int, scale: float, centre_px: tuple[float, float], elements, bg=None, ss: int = 4,
           mono: bool = False, mono_rgb=(255, 255, 255)):
    """Draw the logo: `scale` source-units->px, source centre lands on centre_px."""
    S = px * ss
    img = Image.new("RGBA", (S, S), (*hex_rgb(bg), 255) if bg else (0, 0, 0, 0))
    for e in elements:
        mask = Image.new("L", (S, S), 0)
        dr = ImageDraw.Draw(mask)
        for poly in e["polys"]:
            dr.polygon([((x - CX) * scale * ss + centre_px[0] * ss,
                         (y - CY) * scale * ss + centre_px[1] * ss) for x, y in poly], fill=255)
        rgb, alpha = (mono_rgb, MONO_ALPHA[e["role"]]) if mono else (hex_rgb(e["fill"]), 1.0)
        if alpha < 1.0:
            mask = mask.point(lambda v, a=alpha: int(v * a))
        layer = Image.new("RGBA", (S, S), (*rgb, 0))
        layer.putalpha(mask)
        img = Image.alpha_composite(img, layer)
    return img.resize((px, px), Image.LANCZOS)


def master(px: int = 1024, height_fraction: float = 0.74):
    scale = px * height_fraction / BBOX_H
    return render(px, scale, (px / 2, px / 2), ELEMENTS, bg=BACKGROUND, ss=3 if px > 600 else 4)


def adaptive(px: int, mono=False, with_bg=True):
    s = px / ADAPTIVE_DP
    return render(px, SCALE_DP * s, (px / 2, px / 2), ELEMENTS, bg=BACKGROUND if with_bg and not mono else None,
                  ss=3 if px > 500 else 4, mono=mono)


def legacy(size: int, shape: str):
    """API<26 launcher icon: the central 72dp of the adaptive composition, mask baked in."""
    full = int(round(size * ADAPTIVE_DP / 72))
    img = adaptive(full)
    off = (full - size) // 2
    img = img.crop((off, off, off + size, off + size))
    ss = 4
    m = Image.new("L", (size * ss, size * ss), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size * ss - 1, size * ss - 1), radius=int(size * ss * 0.28), fill=255)
    m = m.resize((size, size), Image.LANCZOS)
    img.putalpha(m)
    return img


# ------------------------------------------------------------------ vector writers
def svg_group(elements, transform: str, mono=False) -> str:
    rows = []
    for e in elements:
        fill = "#FFFFFF" if mono else e["fill"]
        op = f' fill-opacity="{MONO_ALPHA[e["role"]]}"' if mono and MONO_ALPHA[e["role"]] < 1 else ""
        rows.append(f'    <path id="{e["id"]}" fill="{fill}"{op} d="{e["d"]}"/>')
    return f'  <g transform="{transform}">\n' + "\n".join(rows) + "\n  </g>"


def adaptive_transform():
    tx = ADAPTIVE_DP / 2 - CX * SCALE_DP
    ty = ADAPTIVE_DP / 2 - CY * SCALE_DP
    return tx, ty


def android_vector(mono: bool) -> str:
    tx, ty = adaptive_transform()
    paths = []
    for e in ELEMENTS:
        if mono:
            a = MONO_ALPHA[e["role"]]
            alpha = f'\n        android:fillAlpha="{fmt(a, 2)}"' if a < 1 else ""
            paths.append(f'    <path\n        android:fillColor="#FFFFFFFF"{alpha}\n        android:pathData="{e["d"]}"/>')
        else:
            paths.append(f'    <path\n        android:fillColor="#FF{e["fill"].lstrip("#")}"\n        android:pathData="{e["d"]}"/>')
    note = ("Generated by scripts/generate_app_icons.py. Single flat colour (white) so the launcher can tint it; "
            "alpha keeps the cube faces apart. No field: the mark floats on the themed background.") if mono else \
           "Generated by scripts/generate_app_icons.py. The logo on transparency, inside the 66dp safe circle."
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {note} -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            f'  <group android:scaleX="{fmt(SCALE_DP, 5)}" android:scaleY="{fmt(SCALE_DP, 5)}"\n'
            f'      android:translateX="{fmt(tx, 3)}" android:translateY="{fmt(ty, 3)}">\n'
            + "\n".join(paths) + "\n  </group>\n</vector>\n")


def background_xml() -> str:
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Generated by scripts/generate_app_icons.py. Flat field: the app's dark surface colour. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            f'  <path android:pathData="M0,0 H108 V108 H0 Z" android:fillColor="#FF{BACKGROUND.lstrip("#")}"/>\n'
            "</vector>\n")


def artwork_kt() -> str:
    role_enum = {"neutral": "Bracket", "accent": "Accent", "cube-top": "CubeTop",
                 "cube-left": "CubeLeft", "cube-right": "CubeRight"}
    layers = []
    for e in ELEMENTS:
        r, g, b = hex_rgb(e["fill"])
        layers.append(
            "        AppIconLayer(\n"
            f'            pathData = "{e["d"]}",\n'
            f"            role = AppIconRole.{role_enum[e['role']]},\n"
            f"            colour = Color(0xFF{r:02X}{g:02X}{b:02X}),\n"
            "        ),")
    return f'''package com.aryan.reader.shared.ui

// Generated by scripts/generate_app_icons.py -- do not edit by hand. The logo
// of the app icon, in the source artwork's own coordinates: the same geometry
// the launcher's adaptive foreground ships, so the icon drawn inside the app
// cannot drift from the launcher icon. The colours are the artwork's own;
// AppIcon repaints them in the app theme at runtime, so this file stays theme-free.

import androidx.compose.ui.graphics.Color

internal object AppIconArtwork {{
    /** The logo's placed size, in the dp the composable sizes its canvas to. */
    const val viewportWidth = {fmt(BBOX_W * SCALE_DP, 4)}f
    const val viewportHeight = {fmt(BBOX_H * SCALE_DP, 4)}f

    /** Source coordinates to viewport: the placement the launcher foreground uses. */
    const val sourceScale = {fmt(SCALE_DP, 5)}f
    const val sourceTranslateX = {fmt(-MIN_X * SCALE_DP, 3)}f
    const val sourceTranslateY = {fmt(-MIN_Y * SCALE_DP, 3)}f

    /** The logo's shapes in the order the artwork paints them. */
    val layers: List<AppIconLayer> = listOf(
{chr(10).join(layers)}
    )
}}
'''


# ------------------------------------------------------------------ main
def save(img: Image.Image, path: pathlib.Path, rgb=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    (img.convert("RGB") if rgb else img).save(path, optimize=True)


def write(path: pathlib.Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def main():
    # SVGs
    s = 1024 * 0.74 / BBOX_H
    write(ICON_DIR / "vellum-icon.svg",
          f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n'
          f'  <rect width="1024" height="1024" fill="{BACKGROUND}"/>\n'
          + svg_group(ELEMENTS, f"translate({fmt(512 - CX * s, 3)} {fmt(512 - CY * s, 3)}) scale({fmt(s, 5)})") + "\n</svg>\n")
    tx, ty = adaptive_transform()
    t = f"translate({fmt(tx, 3)} {fmt(ty, 3)}) scale({fmt(SCALE_DP, 5)})"
    head = '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
    write(ICON_DIR / "vellum-icon-android-background.svg",
          head + f'  <rect width="108" height="108" fill="{BACKGROUND}"/>\n</svg>\n')
    write(ICON_DIR / "vellum-icon-android-foreground.svg", head + svg_group(ELEMENTS, t) + "\n</svg>\n")
    write(ICON_DIR / "vellum-icon-monochrome.svg", head + svg_group(ELEMENTS, t, mono=True) + "\n</svg>\n")

    # VectorDrawables (branding copy + deployed copy)
    for name, text in (("ic_launcher_background.xml", background_xml()),
                       ("ic_launcher_foreground.xml", android_vector(False)),
                       ("ic_launcher_monochrome.xml", android_vector(True))):
        write(ICON_DIR / name, text)
        write(RES / "drawable" / name, text)
    write(ICON_DIR / "AppIconArtwork.kt", artwork_kt())
    write(ARTWORK_DEPLOY, artwork_kt())

    # Rasters
    png = ICON_DIR / "png"
    shutil.rmtree(png, ignore_errors=True)
    m = master(1024)
    ladder = lambda px: m.resize((px, px), Image.LANCZOS)
    for px in (16, 24, 32, 48, 64, 128, 256, 512, 1024):
        save(ladder(px), png / "desktop" / f"vellum-{px}.png", rgb=True)
    m.convert("RGB").save(png / "desktop" / "vellum.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    m.convert("RGB").save(png / "desktop" / "vellum.icns")
    save(ladder(512), png / "store" / "play-store-512.png", rgb=True)
    save(m, png / "store" / "app-store-1024.png", rgb=True)
    for px in (16, 32, 64, 128, 256, 512):
        save(ladder(px), png / "branding" / f"icon-{px}.png")
    ios = {"20x20@1x": 20, "20x20@2x": 40, "20x20@3x": 60, "29x29@1x": 29, "29x29@2x": 58, "29x29@3x": 87,
           "40x40@1x": 40, "40x40@2x": 80, "40x40@3x": 120, "60x60@2x": 120, "60x60@3x": 180,
           "76x76@2x": 152, "83.5x83.5@2x": 167, "1024x1024@1x": 1024}
    for slot, px in ios.items():
        save(ladder(px), png / "ios" / f"Icon-App-{slot}.png", rgb=True)

    # Android legacy launcher icons (mask baked in) -> branding and res
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name, shape in (("ic_launcher.png", "squircle"), ("ic_launcher_round.png", "circle")):
            img = legacy(px, shape)
            save(img, png / "android" / f"mipmap-{density}" / name)
            save(img, RES / f"mipmap-{density}" / name)
        old = RES / f"mipmap-{density}" / "ic_launcher_foreground.png"
        if old.exists():
            old.unlink()

    # preview sheet: adaptive icon under four masks + the themed glyph
    prev = ICON_DIR / "preview"
    shutil.rmtree(prev, ignore_errors=True)
    prev.mkdir(parents=True)
    cell = 216
    sheet = Image.new("RGBA", (cell * 5, cell), (60, 56, 72, 255))
    full = adaptive(cell * 3 // 2 * 2)
    off = (full.width - cell * 3 // 2 * 2 // 3 * 2) // 2
    vis = full.crop((full.width // 6, full.width // 6, full.width * 5 // 6, full.width * 5 // 6)).resize((cell, cell), Image.LANCZOS)
    for i, shape in enumerate(("circle", "squircle", "square", "teardrop-ish")):
        mk = Image.new("L", (cell * 4, cell * 4), 0)
        d = ImageDraw.Draw(mk)
        box = (0, 0, cell * 4 - 1, cell * 4 - 1)
        if shape == "circle": d.ellipse(box, fill=255)
        elif shape == "squircle": d.rounded_rectangle(box, radius=cell, fill=255)
        elif shape == "square": d.rounded_rectangle(box, radius=cell // 8, fill=255)
        else: d.rounded_rectangle(box, radius=cell * 3 // 2, fill=255)
        mk = mk.resize((cell, cell), Image.LANCZOS)
        tile = vis.copy(); tile.putalpha(mk)
        sheet.alpha_composite(tile, (i * cell, 0))
    mono = adaptive(cell * 3, mono=True, with_bg=False)
    mono = mono.crop((cell // 2, cell // 2, cell * 5 // 2, cell * 5 // 2)).resize((cell, cell), Image.LANCZOS)
    tile = Image.new("RGBA", (cell, cell), (208, 188, 255, 255))
    glyph = Image.new("RGBA", (cell, cell), (53, 36, 94, 255)); glyph.putalpha(mono.split()[3])
    tile.alpha_composite(glyph)
    mk = Image.new("L", (cell * 4, cell * 4), 0); ImageDraw.Draw(mk).ellipse((0, 0, cell * 4 - 1, cell * 4 - 1), fill=255)
    tile.putalpha(mk.resize((cell, cell), Image.LANCZOS))
    sheet.alpha_composite(tile, (4 * cell, 0))
    sheet.save(prev / "android-masks.png")
    print(f"bbox {BBOX_W:.1f}x{BBOX_H:.1f}  maxReach {MAX_REACH:.1f}  scale {SCALE_DP:.5f}dp/unit  "
          f"logo {BBOX_W * SCALE_DP:.1f}x{BBOX_H * SCALE_DP:.1f}dp")


if __name__ == "__main__":
    main()
