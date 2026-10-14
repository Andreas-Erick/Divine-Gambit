"""Builds the launcher icon, "Portal": an ivory knight bursting out of a gold-ringed portal onto a
swirling board, on a night sky with a teal glow and a few stars.

Writes the adaptive icon's three vector drawables (background, foreground and monochrome) and an
icon.svg preview, cropped and masked as a round launcher shows it. The knight's shape comes from
Noto Sans Symbols 2 (SIL Open Font License): the solid knight (U+265E) is its body, the outlined
knight (U+2658) its contour and details, mirrored to face right.

Usage: build.py ICON_DIR FONT OUT_DIR
"""
import sys

from shapes import circle, glyph, ring, spiral, stars

here, font, out = sys.argv[1], sys.argv[2], sys.argv[3]

NIGHT_HI, NIGHT_LO = "#2B3070", "#090A1E"
TEAL, GOLD_HI, GOLD_LO = "#3FC1B0", "#FFE29A", "#B98328"
IVORY_HI, IVORY_LO, INK = "#FFFBF0", "#D8CCB4", "#191B33"
BONE, SLATE = "#E9E4D6", "#6E7FA6"
FULL = "M0,0h108v108h-108z"


def lin(x1, y1, x2, y2, *stops):
    return {"type": "linear", "start": (x1, y1), "end": (x2, y2), "stops": list(stops)}


def rad(cx, cy, r, *stops):
    return {"type": "radial", "center": (cx, cy), "radius": r, "stops": list(stops)}


# Everything that matters stays within 33 units of the centre, which every launcher mask shows.
CX, CY, R = 54, 49, 21
KNIGHT_BOX = (35, 33.5, 40)
knight_body = glyph(here, font, "265E", *KNIGHT_BOX, "flip")
knight_contour = glyph(here, font, "2658", *KNIGHT_BOX, "flip")
window = circle(CX, CY, R)
gold_ring = ring(CX, CY, R + 0.6, 3.0)

background = [
    {"d": FULL, "fill": rad(54, 46, 72, (0, NIGHT_HI, 1), (1, NIGHT_LO, 1))},
    {"d": FULL, "fill": rad(54, 48, 34, (0, TEAL, 0.5), (0.45, TEAL, 0.14), (1, TEAL, 0))},
] + [{"d": circle(x, y, r), "fill": ("#FFFFFF", a)} for x, y, r, a in stars(7, 14, avoid=(54, 50, 22))]

foreground = [
    # The portal's shadow, the swirling board inside it, its depth, and its gold rim.
    {"d": circle(CX, CY + 2, R + 2.5), "fill": ("#000000", 0.45)},
    {"d": FULL, "fill": (BONE, 1), "clip": window},
    {"d": spiral(CX, CY, 40, sectors=10, growth=1.24, twist=0.35, r0=1.5), "fill": (SLATE, 1), "clip": window},
    {"d": FULL, "fill": rad(CX, CY - 4, R, (0, "#FFFFFF", 0.55), (0.3, TEAL, 0.35), (0.75, "#0B0C22", 0.2), (1, "#0B0C22", 0.7)), "clip": window},
    {"d": gold_ring, "fill": lin(20, 20, 90, 90, (0, GOLD_HI, 1), (0.5, "#E8B04B", 1), (1, GOLD_LO, 1))},
    # The knight, breaking out over the rim.
    {"d": knight_body, "fill": ("#000000", 0.4), "shift": (1.4, 2.0)},
    {"d": knight_body, "fill": lin(30, 20, 80, 80, (0, IVORY_HI, 1), (1, IVORY_LO, 1))},
    {"d": knight_contour, "fill": (INK, 1)},
]

# Themed icons: the rim and the knight's silhouette, in one colour.
monochrome = [{"d": gold_ring, "fill": ("#000000", 1)}, {"d": knight_body, "fill": ("#000000", 1)}]


def argb(colour, alpha):
    return f"#{round(alpha * 255):02X}{colour[1:]}"


def svg(shapes):
    defs, body = [], []
    for i, s in enumerate(shapes):
        fill = s["fill"]
        if isinstance(fill, dict):
            stops = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in fill["stops"])
            if fill["type"] == "linear":
                (x1, y1), (x2, y2) = fill["start"], fill["end"]
                defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
            else:
                (cx, cy), r = fill["center"], fill["radius"]
                defs.append(f'<radialGradient id="g{i}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{stops}</radialGradient>')
            paint = f'fill="url(#g{i})"'
        else:
            paint = f'fill="{fill[0]}" fill-opacity="{fill[1]}"'
        clip = ""
        if s.get("clip"):
            defs.append(f'<clipPath id="c{i}"><path d="{s["clip"]}"/></clipPath>')
            clip = f' clip-path="url(#c{i})"'
        dx, dy = s.get("shift", (0, 0))
        body.append(f'<path {paint}{clip} transform="translate({dx},{dy})" d="{s["d"]}"/>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="432" height="432">'
            f'<defs>{"".join(defs)}<clipPath id="mask"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
            f'<g clip-path="url(#mask)">{"".join(body)}</g></svg>')


def path_xml(shape, indent):
    fill = shape["fill"]
    if isinstance(fill, dict):
        items = "\n".join(f'{indent}            <item android:offset="{o}" android:color="{argb(c, a)}" />' for o, c, a in fill["stops"])
        if fill["type"] == "linear":
            (x1, y1), (x2, y2) = fill["start"], fill["end"]
            geometry = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
        else:
            (cx, cy), r = fill["center"], fill["radius"]
            geometry = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
        return (f'{indent}<path android:pathData="{shape["d"]}">\n'
                f'{indent}    <aapt:attr name="android:fillColor">\n'
                f'{indent}        <gradient {geometry}>\n{items}\n{indent}        </gradient>\n'
                f'{indent}    </aapt:attr>\n{indent}</path>')
    return f'{indent}<path android:fillColor="{argb(*fill)}" android:pathData="{shape["d"]}" />'


def shape_xml(shape):
    dx, dy = shape.get("shift", (0, 0))
    if not (dx or dy or shape.get("clip")):
        return path_xml(shape, "    ")
    lines = ["    <group" + (f' android:translateX="{dx}" android:translateY="{dy}"' if dx or dy else "") + ">"]
    if shape.get("clip"):
        lines.append(f'        <clip-path android:pathData="{shape["clip"]}" />')
    lines.append(path_xml(shape, "        "))
    lines.append("    </group>")
    return "\n".join(lines)


def vector(shapes, comment):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Generated by docs/icon/build.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{chr(10).join(shape_xml(s) for s in shapes)}
</vector>
"""


open(f"{here}/icon.svg", "w").write(svg(background + foreground))
open(f"{out}/ic_launcher_background.xml", "w").write(vector(background, "The night behind the launcher icon: a teal glow among a few stars."))
open(f"{out}/ic_launcher_foreground.xml", "w").write(vector(foreground, (
    "The launcher icon: a knight bursting out of a gold-ringed portal onto a swirling board. The knight's shape is "
    "from Noto Sans Symbols 2 (SIL Open Font License), see assets/licenses.")))
open(f"{out}/ic_launcher_monochrome.xml", "w").write(vector(monochrome, "The launcher icon in one colour, for themed icons."))
print("ok")
