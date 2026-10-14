"""Prints the outline of a chess glyph from a font as an SVG path, fitted into a box.

Usage: knight.py FONT CODEPOINT X Y SIZE [flip]
The glyph is scaled to fit SIZE x SIZE at (X, Y), y pointing down as in SVG and Android vectors;
with "flip" it's mirrored left to right.
"""
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

font_path, codepoint, x, y, size = sys.argv[1], int(sys.argv[2], 16), *map(float, sys.argv[3:6])
flip = len(sys.argv) > 6 and sys.argv[6] == "flip"
font = TTFont(font_path)
glyphs = font.getGlyphSet()
name = font.getBestCmap()[codepoint]
bounds = BoundsPen(glyphs)
glyphs[name].draw(bounds)
x0, y0, x1, y1 = bounds.bounds
scale = size / max(x1 - x0, y1 - y0)
# Centre the glyph in the box, and flip it (font y points up).
dx = x + (size - (x1 - x0) * scale) / 2 - x0 * scale
dy = y + (size - (y1 - y0) * scale) / 2 + y1 * scale
pen = SVGPathPen(glyphs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
if flip:
    # Mirrored: the glyph's left edge x0 lands on the box's right edge, left + width.
    left = x + (size - (x1 - x0) * scale) / 2
    glyphs[name].draw(TransformPen(pen, (-scale, 0, 0, -scale, left + (x1 - x0) * scale + x0 * scale, dy)))
else:
    glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, dx, dy)))
print(pen.getCommands())
