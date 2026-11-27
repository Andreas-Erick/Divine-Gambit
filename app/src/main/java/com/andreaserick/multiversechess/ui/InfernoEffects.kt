package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Firelight's flicker, around 1: waves out of step with each other, so it never quite repeats. */
internal fun flicker(t: Float, seed: Float = 0f): Float =
    1f + 0.12f * sin(t * 11.3f + seed) + 0.08f * sin(t * 23.7f + seed * 2.1f) + 0.05f * sin(t * 41.9f + seed * 3.7f)

/**
 * Behind the trial's boards: the abyss, deep below, with ridges of rock one behind another, each
 * moving less than the boards as the view pans ([pan] is the camera's offset), so they read as far
 * away. Each part is on its own layer, so only what moves is drawn again: the glow and the distant
 * embers with [time] (in seconds), the rock only when the view pans, the smoke with both.
 */
@Composable
internal fun InfernoDepths(time: () -> Float, pan: () -> Offset, modifier: Modifier = Modifier) {
    val edgePath = remember { Path() }
    val rockPath = remember { Path() }
    val sky = remember { BrushCache() }
    val lava = remember { BrushCache() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawAbyss(time(), pan(), sky) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawRidges(pan(), edgePath, rockPath) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawSmoke(time(), pan(), lava) }
    }
}

/** Where the horizon of the abyss lies, for a view panned to [pan]. */
private fun DrawScope.horizon(pan: Offset) = (size.height * 0.4f + pan.y * 0.03f).coerceIn(size.height * 0.2f, size.height * 0.7f)

/** Far below, a red glow at the horizon, black overhead, and embers drifting in the distance. */
private fun DrawScope.drawAbyss(t: Float, pan: Offset, sky: BrushCache) {
    val w = size.width
    val h = size.height
    val horizon = horizon(pan)
    val hf = horizon / h
    // The same sky until the view pans or resizes, rather than a new gradient every frame.
    drawRect(
        sky.get(hf, h) {
            Brush.verticalGradient(
                0f to Color(0xFF060102),
                (hf - 0.18f) to Color(0xFF1A0507),
                hf to Color(0xFF4E1009),
                (hf + 0.14f) to Color(0xFF1F0605),
                1f to Color(0xFF0A0202),
            )
        },
    )
    val glow = Offset(w / 2f + pan.x * 0.02f, horizon)
    val glowRadius = w * 1.1f
    unitDisc(ABYSS_GLOW, glow, glowRadius, glowRadius, alpha = flicker(t * 0.25f))

    // Embers drifting in the distance, barely moving as the view does; as many to the screen's
    // area on a tablet as on a phone.
    for (i in 0 until perArea(36, 108)) {
        val drift = pan.x * 0.06f + t * (4f + 6f * hash(i, 1)) * density
        val x = floorMod(hash(i, 2) * w * 1.5f + drift, w * 1.5f) - w * 0.25f
        val y = horizon + (hash(i, 3) - 0.6f) * h * 0.5f + sin(t * 0.4f + i) * 6f * density + pan.y * 0.06f
        val alpha = 0.25f + 0.35f * hash(i, 4) * flicker(t * 0.7f, i.toFloat())
        drawCircle(Color(0xFFFF8A3D).copy(alpha = alpha.coerceIn(0f, 1f) * 0.6f), (0.6f + hash(i, 5)) * density, Offset(x, y))
    }

}

/**
 * Ridges of rock, one behind another: the farther, the redder and hazier, the less they move as
 * the view pans, and the brighter the glow along their edges.
 */
private fun DrawScope.drawRidges(pan: Offset, edge: Path, rock: Path) {
    val w = size.width
    val h = size.height
    val horizon = horizon(pan)
    for (i in 0 until RIDGES) {
        val depth = i / (RIDGES - 1f)
        val parallax = 0.05f + 0.4f * depth * depth
        val base = horizon + h * (0.03f + 0.13f * i) + pan.y * parallax * 0.4f
        val amplitude = h * (0.05f + 0.035f * i)
        // By the shorter side, so a screen held sideways keeps the crags' proportions.
        val wavelength = minOf(w, h) * (0.22f + 0.14f * i)
        val step = 10f * density
        edge.reset()
        rock.reset()
        rock.moveTo(0f, h)
        var x = 0f
        while (x <= w + step) {
            val y = base - amplitude * ridge((x - pan.x * parallax) / wavelength, i * 3.7f)
            if (x == 0f) edge.moveTo(x, y) else edge.lineTo(x, y)
            rock.lineTo(x, y)
            x += step
        }
        rock.lineTo(x - step, h)
        rock.close()
        drawPath(rock, lerp(Color(0xFF4A130C), Color(0xFF0D0303), depth))
        drawPath(edge, Color(0xFFFF6A2A).copy(alpha = 0.55f - 0.4f * depth), style = Stroke((2.4f - depth) * density))
        // Haze between one ridge and the next.
        if (i < RIDGES - 1) {
            val top = base - amplitude
            val bottom = base + h * 0.06f
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFF5A1409).copy(alpha = 0.22f), startY = top, endY = bottom),
                topLeft = Offset(0f, top),
                size = Size(w, bottom - top),
            )
        }
    }

}

/** Smoke rolling slowly in front of the rock, and the light of lava from below. */
private fun DrawScope.drawSmoke(t: Float, pan: Offset, lavaBrush: BrushCache) {
    val w = size.width
    val h = size.height
    val big = maxOf(w, h)
    for (i in 0 until 3) {
        val center = Offset(
            w * (0.5f + 0.55f * sin(t * 0.045f * (i + 1) + i * 1.7f)) + pan.x * 0.25f % w,
            h * (0.55f + 0.45f * cos(t * 0.035f * (i + 2) + i * 0.9f)),
        )
        val radius = big * (0.3f + 0.08f * sin(t * 0.21f + i * 2.3f))
        unitDisc(if (i % 2 == 0) SMOKE_RED else SMOKE_DARK, center, radius, radius, alpha = 1f)
    }
    val lava = (0.34f * flicker(t)).coerceIn(0f, 1f)
    val lavaTop = h * 0.55f
    drawRect(
        lavaBrush.get(lavaTop, h) {
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFFB8300F), startY = lavaTop, endY = h)
        },
        topLeft = Offset(0f, lavaTop),
        size = Size(w, h - lavaTop),
        alpha = lava,
    )
}

// Gradients made once, on a disc of radius 1, and scaled where they're drawn.
private val ABYSS_GLOW = unitGradient(0f to Color(0xFFC2380F).copy(alpha = 0.38f), 1f to Color.Transparent)
private val SMOKE_RED = unitGradient(0f to Color(0xFF6E120B).copy(alpha = 0.3f), 1f to Color.Transparent)
private val SMOKE_DARK = unitGradient(0f to Color(0xFF3A0716).copy(alpha = 0.3f), 1f to Color.Transparent)

/**
 * How many of something to draw for [phone] of them on a phone's screen (412 by 915 dp): more on
 * a bigger screen, so a tablet isn't sparser, up to [most]; never fewer than on a phone.
 */
private fun DrawScope.perArea(phone: Int, most: Int): Int {
    val phoneArea = 412.dp.toPx() * 915.dp.toPx()
    return (phone * size.width * size.height / phoneArea).toInt().coerceIn(phone, most)
}

private const val RIDGES = 4

/** A brush kept until what it depends on ([a], [b]) changes, so drawing a frame doesn't make a new one. */
private class BrushCache {
    private var a = Float.NaN
    private var b = Float.NaN
    private var brush: Brush? = null

    fun get(a: Float, b: Float, make: () -> Brush): Brush {
        val kept = brush
        if (kept != null && a == this.a && b == this.b) return kept
        this.a = a
        this.b = b
        return make().also { brush = it }
    }
}

/** A ridge's height at [u] (wavelengths along it), about 0 to 1: rolling, with sharp crags. */
private fun ridge(u: Float, seed: Float): Float =
    0.5f + 0.28f * sin(u + seed) + 0.16f * sin(u * 2.3f + seed * 1.7f) + 0.22f * (0.5f - abs(sin(u * 4.1f + seed * 2.9f)))

/** A fixed pseudo-random number from 0 to 1 for item [i]'s property [k]. */
internal fun hash(i: Int, k: Int): Float {
    val v = sin(i * 12.9898f + k * 78.233f) * 43758.547f
    return v - floor(v)
}

private fun floorMod(a: Float, b: Float): Float = a - b * floor(a / b)

/**
 * Over the trial: darkness breathing in from the edges, ash drifting down, sparks rising, and
 * flames licking up along the bottom. Faint enough to play through.
 */
@Composable
internal fun InfernoAtmosphere(time: () -> Float, modifier: Modifier = Modifier) {
    // Enough of each for a big tablet; a phone draws the first of them (see perArea).
    val sparks = remember { Random(7).let { r -> List(120) { Spark(r.nextFloat(), 0.25f + r.nextFloat() * 0.75f, r.nextFloat(), 1f + r.nextFloat() * 2.4f) } } }
    val ash = remember { Random(11).let { r -> List(78) { Spark(r.nextFloat(), 0.3f + r.nextFloat() * 0.7f, r.nextFloat(), 0.8f + r.nextFloat() * 1.6f) } } }
    val tongues = remember { Random(3).let { r -> List(40) { r.nextFloat() } } }
    val tongue = remember { Path() }
    val flameBrush = remember { BrushCache() }
    Canvas(
        modifier.graphicsLayer {
            alpha = 0.75f + 0.25f * sin(time() * 0.9f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        drawRect(
            Brush.radialGradient(
                0.4f to Color.Transparent,
                1f to Color(0xFF050000).copy(alpha = 0.75f * Palette.heat),
                center = center,
                radius = maxOf(size.width, size.height) * 0.72f,
            ),
        )
    }
    Canvas(modifier.graphicsLayer()) {
        val t = time()
        val heat = Palette.heat
        for (k in 0 until perArea(26, ash.size)) {
            val (x, speed, phase, radius) = ash[k]
            val fall = (t * speed * 0.04f + phase) % 1f
            val sway = sin(fall * 9f + phase * 20f) * 22.dp.toPx()
            val alpha = 0.4f * heat * sin(fall * PI.toFloat())
            drawCircle(Color(0xFF9A8C86).copy(alpha = alpha), radius.dp.toPx(), Offset(size.width * x + sway, size.height * fall))
        }
        for (k in 0 until perArea(40, sparks.size)) {
            val (x, speed, phase, radius) = sparks[k]
            // Each spark rises at its own speed, wavering and glinting, and dies out near the top.
            val rise = (t * speed * 0.33f + phase) % 1f
            val at = Offset(size.width * x + sin(rise * 6f + phase * 10f) * 14.dp.toPx(), size.height * (1f - rise))
            val alpha = ((1f - rise) * 0.8f * heat * flicker(t * 1.5f, phase * 30f)).coerceIn(0f, 1f)
            drawCircle(Palette.flame.copy(alpha = alpha), radius.dp.toPx(), at)
            drawCircle(Palette.ember.copy(alpha = alpha * 0.35f), radius.dp.toPx() * 2.8f, at)
        }
        // A tongue every 56 dp or so, at least nine: on a wide screen more of them, not wider ones.
        val count = (size.width / 56.dp.toPx()).roundToInt().coerceIn(9, tongues.size)
        val step = size.width / (count - 1)
        // One gradient for every tongue, from the tallest a tongue can reach down to the floor.
        val tallest = 215.dp.toPx() * 1.3f
        val fire = flameBrush.get(size.height, tallest) {
            Brush.verticalGradient(
                0f to Color(0x00FFD27A),
                0.3f to Color(0xB3FF9A2E),
                0.7f to Color(0xD9D2381A),
                1f to Color(0xFF6A0C06),
                startY = size.height - tallest,
                endY = size.height,
            )
        }
        for (i in 0 until count) {
            val seed = tongues[i]
            val x = i * step
            val height = 215.dp.toPx() * (0.65f + 0.35f * seed) * flicker(t, seed * 40f)
            val tip = Offset(x + sin(t * 2.3f + seed * 12f) * 18.dp.toPx(), size.height - height)
            val half = step * 0.8f
            tongue.reset()
            tongue.moveTo(x - half, size.height)
            tongue.cubicTo(x - half * 0.6f, size.height - height * 0.5f, tip.x - half * 0.2f, tip.y + height * 0.3f, tip.x, tip.y)
            tongue.cubicTo(tip.x + half * 0.2f, tip.y + height * 0.3f, x + half * 0.6f, size.height - height * 0.5f, x + half, size.height)
            tongue.close()
            drawPath(tongue, fire, alpha = heat)
        }
    }
}

/** The title, glowing like a coal. */
@Composable
internal fun GlowingTitle(text: String, time: () -> Float) {
    // A steady glow, and a stronger one over it whose opacity flickers: read while drawing, so the
    // title isn't composed again on every frame.
    Box {
        // Blurs in dp, so the glow is the same size on every screen.
        val (soft, strong) = with(LocalDensity.current) { 4.dp.toPx() to 8.5.dp.toPx() }
        Text(text, style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Palette.ember, Offset.Zero, soft)), maxLines = 1)
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Palette.ember, Offset.Zero, strong)),
            maxLines = 1,
            modifier = Modifier.graphicsLayer { alpha = ((flicker(time()) - 0.75f) * 2f).coerceIn(0f, 1f) }.clearAndSetSemantics {},
        )
    }
}

/** A spark's (or ash flake's) place across the screen (0 to 1), speed, where in its course it starts, and size in dp. */
private data class Spark(val x: Float, val speed: Float, val phase: Float, val radius: Float)

/** The portal's fire: how far each layer's tongues reach (in rims), its colour, and where its flicker starts. */
private val FIRE_LAYERS = listOf(
    Triple(2.4f, Color(0xE67A0E08), 0f),
    Triple(1.6f, Color(0xF2E0441B), 1.7f),
    Triple(0.95f, Color(0xFFFF9A2E), 3.1f),
    Triple(0.4f, Color(0xFFFFE6A8), 4.4f),
)

/**
 * The way into the Inferno: the content shows through a hole burning outward from [origin] (the
 * middle of the lower screen if unknown) as [progress] goes from 0 to 1, and closing again as it
 * returns. Its edge is ragged, crawling fire that throws off sparks; [time] (in seconds) moves it,
 * and the screen flashes as the fire takes it over.
 */
internal fun Modifier.portal(progress: () -> Float, origin: () -> Offset?, time: () -> Float): Modifier {
    val clipScratch = Path()
    val ringScratch = Path()
    return drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val t = time()
        val c = origin() ?: Offset(size.width / 2f, size.height * 0.8f)
        val far = maxOf(
            hypot(c.x, c.y),
            hypot(size.width - c.x, c.y),
            hypot(c.x, size.height - c.y),
            hypot(size.width - c.x, size.height - c.y),
        )
        val rim = 44.dp.toPx()
        val r = (far + rim * 2.5f) * p
        clipScratch.reset()
        clipScratch.addOval(Rect(c, r))
        clipPath(clipScratch) { this@drawWithContent.drawContent() }

        // A glow around the hole, then the flames: dark red outermost, through orange, to white-hot.
        val outer = r + rim * 3f
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                ((r - rim) / outer).coerceAtLeast(0f) to Color.Transparent,
                (r / outer) to Palette.flame.copy(alpha = 0.9f),
                ((r + rim) / outer) to Palette.ember.copy(alpha = 0.5f),
                1f to Color.Transparent,
                center = c,
                radius = outer,
            ),
            radius = outer,
            center = c,
        )
        val grow = (r / (rim * 2f)).coerceAtMost(1f)
        for ((reach, color, seed) in FIRE_LAYERS) {
            fillFlameRing(ringScratch, c, r, rim * reach * grow, t, seed)
            drawPath(ringScratch, color)
        }
        // Sparks thrown off the rim.
        for (i in 0 until 28) {
            val angle = i * 2f * PI.toFloat() / 28 + sin(i * 1.3f)
            val life = (t * 1.6f + i * 0.37f) % 1f
            val distance = r + rim * (0.4f + 3f * life)
            val at = c + Offset(cos(angle) * distance, sin(angle) * distance - life * rim)
            drawCircle(Palette.flame.copy(alpha = (1f - life) * 0.9f), (0.8f + 2.6f * (1f - life)).dp.toPx(), at)
        }
        if (p > 0.85f) drawRect(Color(0xFFFF7A2E), alpha = 0.16f * sin(PI.toFloat() * (p - 0.85f) / 0.15f))
    }
}

/** Populates [target] with a ring from radius [r] out to ragged tongues up to [reach] beyond it. */
private fun fillFlameRing(target: Path, c: Offset, r: Float, reach: Float, t: Float, seed: Float) {
    target.reset()
    target.fillType = PathFillType.EvenOdd
    val points = 140
    for (i in 0..points) {
        val a = 2f * PI.toFloat() * i / points
        val k = 0.5f + 0.25f * sin(a * 9f + t * 3.2f + seed) + 0.17f * sin(a * 17f - t * 4.6f + seed * 2f) + 0.1f * sin(a * 31f + t * 7.5f + seed)
        val radius = r + reach * k.coerceAtLeast(0.04f)
        val x = c.x + cos(a) * radius
        val y = c.y + sin(a) * radius
        if (i == 0) target.moveTo(x, y) else target.lineTo(x, y)
    }
    target.close()
    target.addOval(Rect(c, (r - 1f).coerceAtLeast(0f)))
}

/** A flame, for the Inferno. Drawn here since only the core icon set is bundled. */
val FlameIcon: ImageVector = ImageVector.Builder("Flame", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 1.5f)
        curveTo(13.2f, 5.8f, 18.5f, 8.6f, 18.5f, 14.2f)
        curveTo(18.5f, 18.6f, 15.6f, 22.5f, 12f, 22.5f)
        curveTo(8.4f, 22.5f, 5.5f, 18.8f, 5.5f, 14.8f)
        curveTo(5.5f, 11.6f, 7.4f, 9.4f, 8.8f, 7.8f)
        curveTo(8.9f, 10.4f, 9.9f, 12f, 11.2f, 12.4f)
        curveTo(10.4f, 8.8f, 10.9f, 4.8f, 12f, 1.5f)
        close()
    }
}.build()
