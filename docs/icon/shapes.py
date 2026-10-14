"""Shapes for the launcher icon designs, shared by the SVG previews and the Android vectors.

A shape is a dict: d (path data), fill (a colour "#RRGGBB" with optional alpha, or a gradient),
optionally clip (path data) and shift (dx, dy). Gradients: {"type": "linear", "start": (x, y),
"end": (x, y), "stops": [(offset, "#RRGGBB", alpha)]} or {"type": "radial", "center": (x, y),
"radius": r, "stops": [...]}.
"""
import math
import random
import subprocess
import sys


def glyph(here, font, code, x, y, size, *flags):
    command = [sys.executable, "-I", f"{here}/knight.py", font, code, str(x), str(y), str(size), *flags]
    return subprocess.check_output(command, text=True).strip()


def circle(cx, cy, r, reverse=False):
    sweep = 1 if reverse else 0
    return f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,{sweep} {2 * r:.2f},0a{r:.2f},{r:.2f} 0 1,{sweep} -{2 * r:.2f},0z"


def ring(cx, cy, r, width):
    return circle(cx, cy, r + width / 2) + circle(cx, cy, r - width / 2, reverse=True)


def ellipse_band(cx, cy, rx, ry, width, angle, start, end, steps=60):
    """A band along part of an ellipse (angles in degrees along it), rotated by [angle] degrees."""
    a = math.radians(angle)

    def point(t, grow):
        x, y = (rx + grow) * math.cos(t), (ry + grow) * math.sin(t)
        return cx + x * math.cos(a) - y * math.sin(a), cy + x * math.sin(a) + y * math.cos(a)

    ts = [math.radians(start + (end - start) * i / steps) for i in range(steps + 1)]
    outer = [point(t, width / 2) for t in ts]
    inner = [point(t, -width / 2) for t in reversed(ts)]
    pts = outer + inner
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "z"


def spiral(cx, cy, max_r, sectors=12, growth=1.2, twist=0.28, r0=2.5, turn=0.0):
    """The menu's checkerboard vortex: alternate cells of a logarithmic spiral, as one path."""
    parts = []
    r, k = r0, 0
    sweep = 2 * math.pi / sectors
    while r < max_r:
        nxt = r * growth
        for s in range(sectors):
            if (s + k) % 2:
                continue
            a0 = s * sweep + k * twist + turn
            pts = [(cx + math.cos(a0 + sweep * i / 4) * r, cy + math.sin(a0 + sweep * i / 4) * r) for i in range(5)]
            pts += [(cx + math.cos(a0 + twist + sweep * i / 4) * nxt, cy + math.sin(a0 + twist + sweep * i / 4) * nxt) for i in range(4, -1, -1)]
            parts.append("M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "z")
        r = nxt
        k += 1
    return "".join(parts)


def stars(seed, count, avoid=None):
    rnd = random.Random(seed)
    out = []
    while len(out) < count:
        x, y = rnd.uniform(16, 92), rnd.uniform(16, 92)
        if avoid and math.hypot(x - avoid[0], y - avoid[1]) < avoid[2]:
            continue
        out.append((x, y, rnd.uniform(0.35, 0.8), rnd.uniform(0.35, 0.9)))
    return out
