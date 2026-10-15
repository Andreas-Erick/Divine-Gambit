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


def flames(x0, x1, base, height, count, seed, jitter=0.45):
    """Overlapping tongues of fire rising from [base], each curling as it rises, filled down to
    below the icon's edge."""
    rnd = random.Random(seed)
    step = (x1 - x0) / count
    parts = [f"M{x0:.2f},{base:.2f}L{x1:.2f},{base:.2f}L{x1:.2f},{base + 40:.2f}L{x0:.2f},{base + 40:.2f}z"]
    for i in range(count):
        x = x0 + (i + 0.5) * step + rnd.uniform(-0.25, 0.25) * step
        half = step * rnd.uniform(0.7, 1.0)
        h = height * rnd.uniform(1 - jitter, 1)
        lean = rnd.uniform(-0.5, 0.5) * half
        tip_x, tip_y = x + lean, base - h
        parts.append(
            f"M{x - half:.2f},{base + 1:.2f}"
            f"C{x - half * 0.95:.2f},{base - h * 0.45:.2f} {tip_x - half * 0.55 - lean * 0.4:.2f},{tip_y + h * 0.42:.2f} {tip_x:.2f},{tip_y:.2f}"
            f"C{tip_x + half * 0.2 - lean * 0.6:.2f},{tip_y + h * 0.5:.2f} {x + half * 0.95:.2f},{base - h * 0.32:.2f} {x + half:.2f},{base + 1:.2f}z"
        )
    return "".join(parts)


def bezier(p0, p1, p2, p3, steps=40):
    """Points along a cubic Bezier curve."""
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        pts.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return pts


def band(points, w0, w1=None):
    """A ribbon along [points], [w0] wide at its start, narrowing (or widening) to [w1]."""
    w1 = w0 if w1 is None else w1
    left, right = [], []
    n = len(points)
    for i, (x, y) in enumerate(points):
        ax, ay = points[max(i - 1, 0)]
        bx, by = points[min(i + 1, n - 1)]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy) or 1
        nx, ny = -dy / length, dx / length
        w = (w0 + (w1 - w0) * i / (n - 1)) / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    pts = left + right[::-1]
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "z"


def timelines(start, fork, ends, width):
    """One timeline coming from [start] and branching at [fork] toward each of [ends], the way the
    game's timelines branch. The branches part right away, so the fork shows clearly."""
    fx, fy = fork
    trunk = band(bezier(start, (start[0] + 8, start[1]), (fx - 8, fy), fork), width)
    branches = [band(bezier(fork, (fx + 5, fy + (ey - fy) * 0.55), (ex - 26, ey), (ex, ey)), width, width * 0.7) for ex, ey in ends]
    return trunk + "".join(branches)
