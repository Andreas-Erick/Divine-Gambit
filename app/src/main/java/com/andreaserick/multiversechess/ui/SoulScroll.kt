package com.andreaserick.multiversechess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Before descending, when more than one taken piece waits in the Inferno: an old scroll, singed at
 * the edges and read by candlelight, unrolls and asks whose soul the player will seek. [onChoose]
 * gets the chosen soul's index and the middle of the scroll on screen, for the fire to break
 * through from there; [onDismiss] turns back.
 */
@Composable
fun SoulScroll(
    souls: List<SoulView>,
    onChoose: (Int, Offset?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** False once a soul is chosen, while the scroll burns away: it takes no more choices. */
    active: Boolean = true,
) {
    var middle by remember { mutableStateOf<Offset?>(null) }
    val haptics = LocalHapticFeedback.current
    val unroll = remember { Animatable(0f) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { unroll.animateTo(1f, tween(900, easing = CubicBezierEasing(0.2f, 0f, 0.1f, 1f))) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    BackHandler(enabled = active, onBack = onDismiss)
    // Most precious first.
    val ordered = souls.filter { !it.free }.sortedByDescending { it.soul.type.value }

    Box(
        modifier
            .fillMaxSize()
            // The world goes dark around the scroll, with a red glow breathing behind it.
            .drawBehind {
                drawRect(Color(0xE6050102))
                val glow = (0.35f + 0.1f * kotlin.math.sin(time.floatValue * 1.3f)) * unroll.value
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF8A1A0A).copy(alpha = glow), Color.Transparent), center, size.minDimension * 0.75f),
                    size.minDimension * 0.75f,
                )
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (active) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .onGloballyPositioned { middle = it.boundsInRoot().center }
                .fillMaxWidth(0.88f)
                // Taps on the scroll itself don't turn back.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Roller()
            // The parchment, unrolling downward from the top roller.
            Box(
                Modifier
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    // Only as tall as it has unrolled, so the lower roller travels down with it, and
                    // nothing below that shows yet.
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, (placeable.height * unroll.value).roundToInt()) { placeable.place(0, 0) }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawParchment(time.floatValue) }
                        .padding(horizontal = 26.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The ink appears once the scroll is open, line after line.
                    fun Modifier.inked(line: Int) = graphicsLayer { alpha = ((unroll.value - 0.45f) * 3f - line * 0.35f).coerceIn(0f, 1f) }
                    Text(
                        "Whose soul will you seek?",
                        modifier = Modifier.inked(0),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        color = Ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Every trial you pass in the Inferno counts toward the one you choose.",
                        modifier = Modifier.inked(0),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = Ink.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    for ((i, soul) in ordered.withIndex()) {
                        InkRule(Modifier.inked(i + 1))
                        SoulLine(
                            soul,
                            Modifier.inked(i + 1),
                            onClick = {
                                if (active) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onChoose(soul.index, middle)
                                }
                            },
                        )
                    }
                    InkRule(Modifier.inked(ordered.size + 1))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().inked(ordered.size + 1), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Turn back",
                            modifier = Modifier
                                .clickable(role = Role.Button) { if (active) onDismiss() }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontStyle = FontStyle.Italic,
                            color = Ink.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.weight(1f))
                        WaxSeal()
                    }
                }
            }
            Roller()
        }
    }
}

/** One soul on the scroll: the piece, its name, and a mark for each trial, filled once passed. */
@Composable
private fun SoulLine(soul: SoulView, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Seek this soul", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pieceSymbol(soul.soul.type), fontSize = 30.sp, color = Ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(soul.soul.type.displayName, style = MaterialTheme.typography.titleMedium, color = Ink)
            val left = soul.soul.trials - soul.solved
            Text(
                if (left == 1) "1 trial to pass" else "$left trials to pass",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = Ink.copy(alpha = 0.7f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(soul.soul.trials) { i ->
                Canvas(Modifier.size(11.dp)) {
                    if (i < soul.solved) drawCircle(BloodInk) else drawCircle(Ink.copy(alpha = 0.6f), style = Stroke(1.4.dp.toPx()))
                }
            }
        }
    }
}

/** A hand-drawn line of ink between entries, thicker in the middle. */
@Composable
private fun InkRule(modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val path = Path().apply {
            moveTo(0f, size.height / 2)
            quadraticTo(size.width / 2, 0f, size.width, size.height / 2)
            quadraticTo(size.width / 2, size.height, 0f, size.height / 2)
            close()
        }
        drawPath(path, Ink.copy(alpha = 0.45f))
    }
}

/** A red wax seal pressed with a flame. */
@Composable
private fun WaxSeal() {
    Box(
        Modifier
            .size(42.dp)
            .drawBehind {
                // Wax spreads unevenly: a ragged rim around a round seal.
                val rim = Path()
                val points = 18
                for (i in 0..points) {
                    val a = i * 2f * Math.PI.toFloat() / points
                    val r = size.minDimension / 2f * (0.9f + 0.1f * hash(i, 9))
                    val x = center.x + kotlin.math.cos(a) * r
                    val y = center.y + kotlin.math.sin(a) * r
                    if (i == 0) rim.moveTo(x, y) else rim.lineTo(x, y)
                }
                rim.close()
                drawPath(rim, Color(0xFF7A0F0C))
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFB3241B), Color(0xFF6A0B08)), center.copy(x = center.x - 4.dp.toPx(), y = center.y - 4.dp.toPx())),
                    size.minDimension * 0.36f,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(FlameIcon, contentDescription = null, tint = Color(0xFF3A0503).copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
    }
}

/** A wooden roller at the scroll's end, with knobs. */
@Composable
private fun Roller(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val knob = size.height * 0.62f
        val wood = Brush.verticalGradient(listOf(Color(0xFF241005), Color(0xFF7A4520), Color(0xFF3B1C0A), Color(0xFF150802)))
        drawRoundRect(wood, Offset(knob, size.height * 0.12f), Size(size.width - knob * 2, size.height * 0.76f), CornerRadius(size.height * 0.38f))
        for (x in listOf(knob, size.width - knob)) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF9A5B2C), Color(0xFF1C0A03)), Offset(x - 2.dp.toPx(), size.height / 2 - 2.dp.toPx()), knob), knob, Offset(x, size.height / 2))
        }
    }
}

/**
 * Old parchment: yellowed, darker toward its edges, stained, and singed along both sides, where
 * embers still glow faintly. [t] (seconds) makes them, and the candlelight from above, flicker.
 */
private fun DrawScope.drawParchment(t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFD9C08C), Color(0xFFE8D6AC), Color(0xFFE3CC9C), Color(0xFFC9A970))))
    drawRect(
        Brush.horizontalGradient(
            0f to Color(0x8A4A2610),
            0.12f to Color.Transparent,
            0.88f to Color.Transparent,
            1f to Color(0x8A4A2610),
        ),
    )
    // Old stains.
    for (i in 0 until 6) {
        val c = Offset(size.width * hash(i, 1), size.height * hash(i, 2))
        val r = size.minDimension * (0.08f + 0.14f * hash(i, 3))
        drawCircle(Brush.radialGradient(listOf(Color(0x2A5A3415), Color.Transparent), c, r), r, c)
    }
    // Singed edges: a ragged burnt margin down each side, with embers in it.
    val step = 6.dp.toPx()
    for (side in 0..1) {
        val edge = Path()
        val x0 = if (side == 0) 0f else size.width
        val inward = if (side == 0) 1f else -1f
        edge.moveTo(x0, 0f)
        var y = 0f
        var i = 0
        while (y <= size.height) {
            val depth = (2f + 9f * hash(i + side * 100, 4)) * density
            edge.lineTo(x0 + inward * depth, y)
            y += step
            i++
        }
        edge.lineTo(x0, size.height)
        edge.close()
        drawPath(edge, Color(0xFF2A1308))
        // The burn's glowing rim.
        for (k in 0 until 14) {
            val ey = size.height * hash(k + side * 50, 6)
            val glow = (0.35f + 0.65f * hash(k, 7)) * (0.6f + 0.4f * flicker(t * 0.8f, k + side * 7f))
            val ex = x0 + inward * (3f + 6f * hash(k, 8)) * density
            drawCircle(Color(0xFFFF7A2E).copy(alpha = (0.55f * glow).coerceIn(0f, 1f)), 1.6f * density, Offset(ex, ey))
            drawCircle(Color(0xFFE0442B).copy(alpha = (0.18f * glow).coerceIn(0f, 1f)), 5f * density, Offset(ex, ey))
        }
    }
    // Candlelight from above, never quite steady.
    val light = (0.16f * flicker(t * 0.6f, 3f)).coerceIn(0f, 1f)
    drawRect(Brush.radialGradient(listOf(Color(0xFFFFE2A0).copy(alpha = light), Color.Transparent), Offset(size.width / 2, 0f), size.width * 0.9f))
}

private val Ink = Color(0xFF2B140A)
private val BloodInk = Color(0xFF7A0F0C)
