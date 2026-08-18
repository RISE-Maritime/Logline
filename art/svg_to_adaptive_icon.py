#!/usr/bin/env python3
"""Regenerate the launcher icon drawables from logline_icon.svg.

    python3 art/svg_to_adaptive_icon.py

Writes app/src/main/res/drawable/ic_launcher_{background,foreground}.xml.

Android has no SVG support, so the source art is converted to VectorDrawables. This handles the
subset the icon uses — straight-line paths, solid fills and linear gradients — and is not a general
SVG converter. If the artwork grows curves or other features, Android Studio's Vector Asset import
is the fallback.

The foreground is scaled into the adaptive-icon safe zone: the launcher masks the icon to the inner
72dp of a 108dp canvas, so artwork that fills the canvas gets its edges cut off by a circular mask.
"""
import os
import re
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SVG = os.path.join(HERE, "logline_icon.svg")
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

# Artwork is ~1018 units wide on a 1254 canvas; the safe zone is 1254 * 72/108 = 836.
SCALE = 0.80

HEAD = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    "<!-- Generated from art/logline_icon.svg — edit the SVG, not this file.\n"
    "     Regenerate with: python3 art/svg_to_adaptive_icon.py -->\n"
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    '    android:viewportWidth="1254"\n'
    '    android:viewportHeight="1254">\n'
)


def gradient_block(stops, x0, y0, x1, y1, indent):
    p = " " * indent
    out = [
        f'{p}<aapt:attr name="android:fillColor">',
        f"{p}    <gradient",
        f'{p}        android:type="linear"',
        f'{p}        android:startX="{x0:.0f}"',
        f'{p}        android:startY="{y0:.0f}"',
        f'{p}        android:endX="{x1:.0f}"',
        f'{p}        android:endY="{y1:.0f}">',
    ]
    for off, col in stops:
        out.append(f'{p}        <item android:offset="{off}" android:color="{col}"/>')
    out += [f"{p}    </gradient>", f"{p}</aapt:attr>"]
    return "\n".join(out)


def main():
    root = ET.parse(SVG).getroot()
    grads = {
        g.get("id"): [(s.get("offset"), s.get("stop-color")) for s in g.iter(NS + "stop")]
        for g in root.iter(NS + "linearGradient")
    }

    with open(os.path.join(DRAWABLE, "ic_launcher_background.xml"), "w") as f:
        f.write(HEAD)
        f.write('    <path android:pathData="M0,0h1254v1254h-1254z">\n')
        f.write(gradient_block(grads["bg"], 0, 0, 1254, 1254, 8))
        f.write("\n    </path>\n</vector>\n")

    body = []
    for p in root.iter(NS + "path"):
        d = p.get("d").strip()
        fill = p.get("fill")
        rule = "evenOdd" if p.get("fill-rule") == "evenodd" else "nonZero"
        if fill.startswith("url("):
            # SVG gradients here are objectBoundingBox, so each path gets its own span.
            nums = [float(v) for v in re.findall(r"-?\d+\.?\d*", d)]
            xs, ys = nums[0::2], nums[1::2]
            body.append(
                f'        <path\n            android:pathData="{d}"\n'
                f'            android:fillType="{rule}">\n'
                + gradient_block(grads[fill[5:-1]], min(xs), min(ys), max(xs), max(ys), 12)
                + "\n        </path>"
            )
        else:
            body.append(
                f'        <path\n            android:pathData="{d}"\n'
                f'            android:fillColor="{fill}"\n'
                f'            android:fillType="{rule}"/>'
            )

    with open(os.path.join(DRAWABLE, "ic_launcher_foreground.xml"), "w") as f:
        f.write(HEAD)
        f.write(
            "    <!-- Scaled to keep the mark inside the adaptive-icon safe zone (inner 66% of\n"
            "         108dp); the artwork is 1018 units wide and a circular mask would clip it. -->\n"
            "    <group\n"
            '        android:pivotX="627"\n'
            '        android:pivotY="627"\n'
            f'        android:scaleX="{SCALE}"\n'
            f'        android:scaleY="{SCALE}"\n'
            '        android:translateX="5"\n'
            '        android:translateY="11">\n'
        )
        f.write("\n".join(body))
        f.write("\n    </group>\n</vector>\n")

    print(f"wrote ic_launcher_background.xml and ic_launcher_foreground.xml ({len(body)} paths)")
    legacy(root, grads)


# Densities Android expects for a legacy launcher icon, in px.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def legacy(root, grads):
    """Render the raster mipmaps.

    minSdk is 30, so the adaptive icon is always used by the launcher and these are belt-and-braces —
    but the project template shipped a green Android robot in them, and stale artwork in the APK is
    worse than none. Needs cairosvg; skipped with a note if it is not installed.
    """
    try:
        import cairosvg
    except ImportError:
        print("cairosvg not installed — skipped raster mipmaps (vectors are up to date)")
        return

    defs = ET.tostring(root.find(NS + "defs"), encoding="unicode").replace("ns0:", "")
    art = "\n".join(
        f'<path d="{p.get("d")}" fill="{p.get("fill")}" fill-rule="evenodd"/>'
        for p in root.iter(NS + "path")
    )
    # Same composition as the adaptive layers, so the two never drift apart.
    shapes = {
        "ic_launcher": '<rect x="0" y="0" width="1254" height="1254" rx="280"/>',
        "ic_launcher_round": '<circle cx="627" cy="627" r="627"/>',
    }
    written = 0
    for name, mask in shapes.items():
        doc = (
            '<svg xmlns="http://www.w3.org/2000/svg" width="1254" height="1254" '
            'viewBox="0 0 1254 1254">'
            f'<defs>{defs}<clipPath id="m">{mask}</clipPath></defs>'
            '<g clip-path="url(#m)">'
            '<rect width="1254" height="1254" fill="url(#bg)"/>'
            f'<g transform="translate(5,11) translate(627,627) scale({SCALE}) '
            f'translate(-627,-627)">{art}</g>'
            "</g></svg>"
        )
        for density, px in DENSITIES.items():
            out_dir = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{density}")
            os.makedirs(out_dir, exist_ok=True)
            cairosvg.svg2png(
                bytestring=doc.encode(),
                write_to=os.path.join(out_dir, f"{name}.png"),
                output_width=px,
                output_height=px,
            )
            written += 1
    print(f"wrote {written} raster mipmaps across {len(DENSITIES)} densities")


if __name__ == "__main__":
    main()
