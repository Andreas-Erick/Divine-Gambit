package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The menu's sky, the whole game on one screen: Dante's heavens above, nine spheres of stars
 * turning slowly around a golden light behind the title; a timeline branching in three below the
 * card, one rising toward the heavens, one going on, one descending; and the warmth of the
 * Inferno breathing up from beneath.
 *
 * [light] is where the heavens' light shines (the middle of the title), and [fork] how far down
 * the timeline branches (below the card); until they're known, places for a typical phone.
 * Distances are in units of a 412-wide screen, scaled to the real one.
 */
@Composable
fun Heavens(light: () -> Offset?, fork: () -> Float?, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    val stars = remember {
        val random = Random(7)
        List(90) { Star(random.nextFloat(), random.nextFloat() * 0.77f, 0.4f + random.nextFloat() * 1.1f, 0.25f + random.nextFloat() * 0.6f, if (random.nextFloat() < 0.4f) 2f + random.nextFloat() * 4f else 0f, random.nextFloat() * 6f) }
    }
    val rays = remember {
        val random = Random(11)
        List(28) { 260f + random.nextFloat() * 140f to 0.03f + random.nextFloat() * 0.05f }
    }
    // Each part on its own layer: the timeline only moves when the card does.
    Canvas(modifier.graphicsLayer()) {
        val u = size.width / 412f
        val c = light() ?: Offset(size.width / 2, size.height * 0.235f)
        val t = time.floatValue
        drawSky(c, u)
        drawSpheres(c, u, t)
        drawRays(c, u, rays)
        drawLight(c, u, t)
        drawStars(stars, t)
    }
    Canvas(modifier.graphicsLayer()) {
        val u = size.width / 412f
        drawTimeline((fork() ?: (size.height * 0.84f)).coerceAtMost(size.height - 120f * u), u)
    }
    Canvas(modifier.graphicsLayer()) { drawWarmth(size.width / 412f, time.floatValue) }
}

/** A star: where (fractions of the screen), how big and bright, how fast it twinkles (0: not), and its phase. */
private class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float, val period: Float, val phase: Float)

private fun DrawScope.drawSky(c: Offset, u: Float) {
    drawRect(
        Brush.radialGradient(
            0f to Color(0xFF1E2050),
            0.45f to Color(0xFF0E0F2B),
            1f to Color(0xFF06060F),
            center = c,
            radius = 640f * u,
        ),
    )
}

/**
 * How visible the heavens are at [p]: nothing right around the title, so no line runs through its
 * words, fully a little further out.
 */
private fun clearOfTitle(p: Offset, c: Offset, u: Float): Float {
    val dx = (p.x - c.x) / (195f * u)
    val dy = (p.y - c.y) / (170f * 0.8f * u)
    val d = sqrt(dx * dx + dy * dy)
    val x = ((d - 0.55f) / 0.45f).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * Dante's nine spheres: tilted rings around the light, most of them made of stars that travel
 * slowly along them, every third a fine line of gold.
 */
private fun DrawScope.drawSpheres(c: Offset, u: Float, t: Float) {
    for (i in 1..9) {
        val r = (22f + i * i * 4.6f + i * 10f) * u
        val tilt = Math.toRadians(-8.0 + i * 1.5).toFloat()
        val ct = cos(tilt)
        val st = sin(tilt)
        fun point(a: Float): Offset {
            val x = cos(a) * r
            val y = sin(a) * r * 0.42f
            return Offset(c.x + x * ct - y * st, c.y + x * st + y * ct)
        }
        val alpha = 0.85f - i * 0.06f
        if (i % 3 == 0) {
            // A line of gold, drawn in short pieces so each can fade near the title.
            val pieces = 120
            for (k in 0 until pieces) {
                val a0 = 2f * PI.toFloat() * k / pieces
                val a1 = 2f * PI.toFloat() * (k + 1) / pieces
                val p0 = point(a0)
                val fade = clearOfTitle(p0, c, u)
                if (fade <= 0f) continue
                drawLine(Color(0xFFE8C77A).copy(alpha = alpha * fade), p0, point(a1), strokeWidth = 1f * u)
            }
        } else {
            // Stars about (6 + i) apart along the ring, travelling around it in 40 s to 2 min.
            val spacing = (6f + i) * u
            val circumference = 2f * PI.toFloat() * sqrt((r * r + (r * 0.42f) * (r * 0.42f)) / 2f)
            val count = (circumference / spacing).toInt().coerceAtLeast(8)
            val turn = t * ((6f + i) * 20f * u / (40f + i * 12f)) / circumference * 2f * PI.toFloat()
            for (k in 0 until count) {
                val p = point(2f * PI.toFloat() * k / count + turn)
                val fade = clearOfTitle(p, c, u)
                if (fade <= 0f) continue
                drawCircle(Color(0xFFC9C3E8).copy(alpha = alpha * fade), 1.2f * u, p)
            }
        }
    }
}

/** Faint rays from the light, fading in away from the title. */
private fun DrawScope.drawRays(c: Offset, u: Float, rays: List<Pair<Float, Float>>) {
    val path = Path()
    for ((i, ray) in rays.withIndex()) {
        val (length, alpha) = ray
        val a = 2f * PI.toFloat() * i / rays.size
        val w = 0.018f
        fun at(angle: Float, r: Float) = Offset(c.x + cos(angle) * r * u, c.y + sin(angle) * r * u * 0.9f)
        path.reset()
        val p0 = at(a - w, 30f)
        path.moveTo(p0.x, p0.y)
        at(a, length).let { path.lineTo(it.x, it.y) }
        at(a + w, 30f).let { path.lineTo(it.x, it.y) }
        path.close()
        drawPath(
            path,
            Brush.radialGradient(
                0f to Color.Transparent,
                0.35f to Color(0xFFFFE29A).copy(alpha = alpha),
                1f to Color(0xFFFFE29A).copy(alpha = alpha * 0.4f),
                center = c,
                radius = length * u,
            ),
        )
    }
}

/** The light behind the title, breathing slowly. */
private fun DrawScope.drawLight(c: Offset, u: Float, t: Float) {
    val breath = 0.75f + 0.25f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 10f))
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFFFF6DA).copy(alpha = 0.4f * breath),
            0.25f to Color(0xFFFFE29A).copy(alpha = 0.2f * breath),
            0.6f to Color(0xFFE8B04B).copy(alpha = 0.07f * breath),
            1f to Color.Transparent,
            center = c,
            radius = 135f * u,
        ),
        radius = 135f * u,
        center = c,
    )
}

private fun DrawScope.drawStars(stars: List<Star>, t: Float) {
    for (star in stars) {
        val twinkle = if (star.period == 0f) 1f else 0.15f + 0.85f * (0.5f + 0.5f * sin((t + star.phase) * 2f * PI.toFloat() / star.period))
        drawCircle(Color(0xFFFFF6DA).copy(alpha = star.alpha * twinkle), star.radius * density, Offset(star.x * size.width, star.y * size.height))
    }
}

/**
 * One timeline coming in from the left at [forkY] and branching in three, as timelines do in the
 * game: one rises behind the card toward the heavens, one goes on, one descends into the warmth.
 */
private fun DrawScope.drawTimeline(forkY: Float, u: Float) {
    val fork = Offset(62f * u, forkY)
    val start = Offset(-10f * u, forkY)
    val right = size.width + 18f * u
    val ends = listOf(Offset(right, forkY - 348f * u), Offset(right, forkY - 68f * u), Offset(right, forkY + 167f * u))
    val curves = buildList {
        add(Path().apply {
            moveTo(start.x, start.y)
            cubicTo(20f * u, forkY, 40f * u, forkY, fork.x, fork.y)
        })
        for (end in ends) add(Path().apply {
            moveTo(fork.x, fork.y)
            cubicTo(fork.x + 30f * u, fork.y + (end.y - fork.y) * 0.6f, end.x - 160f * u, end.y, end.x, end.y)
        })
    }
    val thread = Brush.horizontalGradient(
        0f to Color(0xFF9FF2E4).copy(alpha = 0.85f),
        0.55f to Color(0xFF3FC1B0).copy(alpha = 0.6f),
        1f to Color(0xFF3FC1B0).copy(alpha = 0.25f),
    )
    for (curve in curves) {
        // A soft glow around each line, then the line.
        drawPath(curve, Color(0xFF3FC1B0).copy(alpha = 0.08f), style = Stroke(12f * u, cap = StrokeCap.Round))
        drawPath(curve, Color(0xFF3FC1B0).copy(alpha = 0.14f), style = Stroke(6f * u, cap = StrokeCap.Round))
        drawPath(curve, thread, style = Stroke(2.6f * u, cap = StrokeCap.Round))
    }
    drawCircle(Color(0xFF9FF2E4), 3.2f * u, fork)
}

/**
 * The Inferno's warmth, from beneath the bottom edge: an ember glow that swells and settles every
 * 6 s, a hotter core pulsing out of step with it, and soft waves of heat rising from it, widening
 * as they fade, one every 2.4 s.
 */
private fun DrawScope.drawWarmth(u: Float, t: Float) {
    val cx = size.width / 2
    fun ease(phase: Float) = 0.5f - 0.5f * cos(phase * 2f * PI.toFloat())
    val swell = ease((t / 6f) % 1f)
    ellipse(
        Offset(cx, size.height + 45f * u), 330f * u, (210f + 40f * swell) * u,
        listOf(0f to Color(0xFFE0442B).copy(alpha = 0.55f), 0.35f to Color(0xFFB3240F).copy(alpha = 0.32f), 0.7f to Color(0xFF7A0E08).copy(alpha = 0.12f), 1f to Color.Transparent),
        alpha = 0.8f + 0.2f * swell,
    )
    ellipse(
        Offset(cx, size.height + 30f * u), 170f * u, 90f * u,
        listOf(0f to Color(0xFFFF9A4A).copy(alpha = 0.45f), 0.5f to Color(0xFFE0442B).copy(alpha = 0.15f), 1f to Color.Transparent),
        alpha = 0.55f + 0.45f * ease((t / 3.7f) % 1f),
    )
    for (i in 0 until 3) {
        val p = ((t + i * 2.4f) / 7.2f) % 1f
        val alpha = if (p < 0.3f) p / 0.3f else 1f - (p - 0.3f) / 0.7f
        ellipse(
            Offset(cx, size.height + 35f * u), (120f + 300f * p) * u, (50f + 210f * p) * u,
            listOf(0.35f to Color.Transparent, 0.75f to Color(0xFFE0442B).copy(alpha = 0.16f), 1f to Color.Transparent),
            alpha = alpha,
        )
    }
}

/** A radial gradient on an ellipse, [rx] by [ry], centered on [c]. */
private fun DrawScope.ellipse(c: Offset, rx: Float, ry: Float, stops: List<Pair<Float, Color>>, alpha: Float) {
    withTransform({ scale(1f, ry / rx, pivot = c) }) {
        drawCircle(Brush.radialGradient(*stops.toTypedArray(), center = c, radius = rx), radius = rx, center = c, alpha = alpha.coerceIn(0f, 1f))
    }
}

/** The menu card's corners, as an old book's: a curl of gold at the top left and bottom right. */
fun DrawScope.drawCardFlourishes(inset: Float, length: Float, insetEnd: Float = inset) {
    val gold = Color(0xFFE8C77A).copy(alpha = 0.6f)
    val stroke = Stroke(1.5f * density, cap = StrokeCap.Round)
    val corner = length * 0.45f
    drawPath(Path().apply {
        moveTo(inset, inset + length)
        lineTo(inset, inset + corner)
        quadraticTo(inset, inset, inset + corner, inset)
        lineTo(inset + length, inset)
    }, gold, style = stroke)
    val right = size.width - insetEnd
    val bottom = size.height - insetEnd
    drawPath(Path().apply {
        moveTo(right - length, bottom)
        lineTo(right - corner, bottom)
        quadraticTo(right, bottom, right, bottom - corner)
        lineTo(right, bottom - length)
    }, gold, style = stroke)
}
