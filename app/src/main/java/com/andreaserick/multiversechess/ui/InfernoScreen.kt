package com.andreaserick.multiversechess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.PieceType
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Soul
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** A soul waiting in the Inferno, as shown: its [index] among the game's souls and the trials it has passed. */
class SoulView(val index: Int, val soul: Soul, val solved: Int) {
    val free: Boolean get() = solved >= soul.trials
}

/**
 * The Inferno: the player's taken pieces, and a puzzle ("trial") to solve for the one chosen. The
 * clock counts down while it's the player's turn on the board.
 */
@Composable
fun InfernoScreen(
    side: Side,
    souls: List<SoulView>,
    chosen: Int?,
    trial: GameSession?,
    checking: Boolean,
    seconds: Int,
    /** Whether the clock is running: it's the player's turn on the board. */
    clockRunning: Boolean,
    notice: String?,
    /** How the game ended while the player was here, until they go back. */
    ended: Outcome?,
    onChoose: (Int) -> Unit,
    onTap: (Pos) -> Unit,
    onSubmit: () -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onLeave: () -> Unit,
    /** Whether it's on screen: it only animates then. */
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val camera = remember { CameraState() }
    var showHint by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val comfortableSquare = with(LocalDensity.current) { COMFORTABLE_SQUARE.toPx() }
    // Seconds, for the fire and smoke; only read while drawing, so only drawing follows it.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val start = withFrameNanos { it } - (time.floatValue * 1e9f).toLong()
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }

    // Frame each new trial, and again whenever it starts over.
    val trialStart = trial?.state?.takeIf { it.turnMoves.isEmpty() }?.timelines
    LaunchedEffect(trial?.mode?.puzzle?.id, trialStart, camera.viewSize) {
        val state = trial?.state ?: return@LaunchedEffect
        if (trialStart != null || !camera.initialized) camera.focus(state.focusArea(), resetZoom = true, comfortableSquare)
    }
    // News from the board is felt as well as read.
    LaunchedEffect(notice, ended) {
        if (active && (notice != null || ended != null)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(modifier.fillMaxSize()) {
        InfernoDepths({ time.floatValue }, { camera.offset }, Modifier.fillMaxSize())
        if (trial != null) {
            // On a layer of its own, so the fire moving behind and in front doesn't redraw the boards.
            MultiverseView(trial, camera, onTap, Modifier.fillMaxSize().graphicsLayer(), backdrop = {})
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (active) CircularProgressIndicator(color = Palette.accent)
                Spacer(Modifier.size(12.dp))
                Text("Forging a trial…", color = Palette.inkSoft)
            }
        }
        InfernoAtmosphere({ time.floatValue }, Modifier.fillMaxSize())

        // Top: the way back, the clock, and the souls waiting here.
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { camera.coveredTop = it.height.toFloat() }
                .background(
                    Brush.verticalGradient(
                        0f to Palette.background.copy(alpha = 0.97f),
                        0.75f to Palette.background.copy(alpha = 0.88f),
                        1f to Palette.background.copy(alpha = 0f),
                    ),
                )
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to the board", onLeave)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GlowingTitle("The Inferno") { time.floatValue }
                    Text(
                        "Abandon all hope, ye who enter here",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = Palette.inkSoft,
                        maxLines = 2,
                    )
                }
                if (trial != null) {
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(
                        Icons.Filled.Refresh,
                        "Start the trial over",
                        onRestart,
                        enabled = !checking && trial.state.turnMoves.isNotEmpty() && trial.outcome == Outcome.Ongoing,
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.Filled.Info, "Hint", { showHint = true })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HellClock(seconds, clockRunning, side)
                for (soul in souls) SoulChip(soul, selected = soul.index == chosen, onClick = { onChoose(soul.index) })
            }
        }

        // Bottom: the trial's status and controls.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { camera.coveredBottom = it.height.toFloat() + 40f }
                .navigationBarsPadding()
                .padding(12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SlabCard(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedVisibility(notice != null) {
                        Text(notice.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Palette.gold)
                    }
                    val soul = souls.firstOrNull { it.index == chosen }
                    val passed = trial?.outcome is Outcome.Checkmate
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    checking -> "Judging your answer…"
                                    passed -> "Trial passed"
                                    soul != null -> "Trial for your ${soul.soul.type.displayName.lowercase()}"
                                    else -> "A trial"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                when {
                                    soul != null && !passed -> "${soul.solved} of ${soul.soul.trials} passed · White to move, checkmate in one turn"
                                    else -> "White to move: checkmate in one turn"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.inkSoft,
                            )
                        }
                    }
                    AnimatedVisibility(trial?.message != null && !passed) {
                        Text(trial?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (passed) {
                            if (souls.any { !it.free }) {
                                SecondaryPill(onClick = onLeave) { Text("To the board") }
                                PrimaryPill(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next trial") }
                            } else {
                                PrimaryPill(onClick = onLeave, modifier = Modifier.weight(1f)) { Text("Back to the board") }
                            }
                        } else {
                            SecondaryPill(onClick = onUndo, enabled = trial?.previous != null && !checking) { Text("Undo") }
                            SecondaryPill(onClick = onSkip, enabled = !checking) { Text("Another") }
                            PrimaryPill(
                                onClick = onSubmit,
                                enabled = trial != null && trial.state.canSubmit && !checking,
                                modifier = Modifier.weight(1f),
                            ) { Text("Submit", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }

    if (showHint && trial?.mode?.puzzle != null) {
        AlertDialog(
            onDismissRequest = { showHint = false },
            confirmButton = { TextButton(onClick = { showHint = false }) { Text("Got it") } },
            title = { Text("Hint") },
            text = { Text(trial.mode.puzzle!!.hint) },
        )
    }

    if (ended != null) {
        val won = (ended as? Outcome.Checkmate)?.winner == side || (ended as? Outcome.Resignation)?.winner == side
        AlertDialog(
            onDismissRequest = onLeave,
            confirmButton = { TextButton(onClick = onLeave) { Text("Back to the board") } },
            icon = {
                Box(Modifier.size(52.dp).background(if (won) Palette.gold else Palette.threat, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(FlameIcon, contentDescription = null, tint = Palette.onGold, modifier = Modifier.size(30.dp))
                }
            },
            title = { Text(if (won) "You won" else if (ended == Outcome.Stalemate) "Stalemate" else "You lost") },
            text = {
                Text(
                    when (ended) {
                        is Outcome.Checkmate ->
                            if (won) "While you were in the Inferno, the game ended: ${side.opponent.displayName} has no legal turn."
                            else "While you were in the Inferno, ${ended.winner.displayName} checkmated you."
                        is Outcome.Resignation ->
                            if (won) "While you were in the Inferno, ${side.opponent.displayName} resigned."
                            else "While you were in the Inferno, the game was resigned."
                        Outcome.Stalemate -> "While you were in the Inferno, the game ended in stalemate."
                        Outcome.Ongoing -> ""
                    },
                )
            },
        )
    }
}

/** Time left in the Inferno; paused, with whose turn it is, while the opponent plays. */
@Composable
private fun HellClock(seconds: Int, running: Boolean, side: Side) {
    val low = running && seconds <= 15
    val pulse = if (!low) 1f else rememberInfiniteTransition(label = "clock").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse",
    ).value
    Surface(
        shape = RoundedCornerShape(50),
        color = if (low) Palette.threat.copy(alpha = 0.25f + 0.25f * pulse) else Palette.card,
        border = BorderStroke(1.dp, if (low) Palette.threat else Palette.presentEdge),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (running) "%d:%02d".format(seconds / 60, seconds % 60) else "Paused",
                style = MaterialTheme.typography.titleMedium,
                color = if (low) Palette.threat else Palette.ink,
            )
            Text(
                if (running) "your turn" else "${side.opponent.displayName}'s turn",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.inkSoft,
            )
        }
    }
}

/** A taken piece, with a pip for each trial: lit once passed. */
@Composable
private fun SoulChip(soul: SoulView, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !soul.free,
        shape = RoundedCornerShape(50),
        color = if (selected) Palette.accent.copy(alpha = 0.22f) else Palette.card,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.accent else Palette.presentEdge),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pieceSymbol(soul.soul.type), style = MaterialTheme.typography.titleLarge, color = Palette.ink)
            Spacer(Modifier.width(6.dp))
            if (soul.free) {
                Text("Free", style = MaterialTheme.typography.labelMedium, color = Palette.gold)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(soul.soul.trials) { i ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (i < soul.solved) Palette.flame else Color.Transparent, CircleShape)
                                .border(1.dp, if (i < soul.solved) Palette.flame else Palette.inkSoft, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/** A piece's symbol for text: the solid chess symbol, or a fairy piece's letter. */
internal fun pieceSymbol(type: PieceType): String = when (type) {
    PieceType.KING -> "♚︎"
    PieceType.QUEEN -> "♛︎"
    PieceType.ROOK -> "♜︎"
    PieceType.BISHOP -> "♝︎"
    PieceType.KNIGHT -> "♞︎"
    PieceType.PAWN -> "♟︎"
    else -> type.letter.toString()
}

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
private fun InfernoDepths(time: () -> Float, pan: () -> Offset, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawAbyss(time(), pan()) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawRidges(pan()) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawSmoke(time(), pan()) }
    }
}

/** Where the horizon of the abyss lies, for a view panned to [pan]. */
private fun DrawScope.horizon(pan: Offset) = (size.height * 0.4f + pan.y * 0.03f).coerceIn(size.height * 0.2f, size.height * 0.7f)

/** Far below, a red glow at the horizon, black overhead, and embers drifting in the distance. */
private fun DrawScope.drawAbyss(t: Float, pan: Offset) {
    val w = size.width
    val h = size.height
    val horizon = horizon(pan)
    val hf = horizon / h
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF060102),
            (hf - 0.18f) to Color(0xFF1A0507),
            hf to Color(0xFF4E1009),
            (hf + 0.14f) to Color(0xFF1F0605),
            1f to Color(0xFF0A0202),
        ),
    )
    val glow = Offset(w / 2f + pan.x * 0.02f, horizon)
    val glowRadius = w * 1.1f
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFC2380F).copy(alpha = 0.38f * flicker(t * 0.25f)), Color.Transparent), glow, glowRadius),
        glowRadius,
        glow,
    )

    // Embers drifting in the distance, barely moving as the view does.
    for (i in 0 until 36) {
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
private fun DrawScope.drawRidges(pan: Offset) {
    val w = size.width
    val h = size.height
    val horizon = horizon(pan)
    for (i in 0 until RIDGES) {
        val depth = i / (RIDGES - 1f)
        val parallax = 0.05f + 0.4f * depth * depth
        val base = horizon + h * (0.03f + 0.13f * i) + pan.y * parallax * 0.4f
        val amplitude = h * (0.05f + 0.035f * i)
        val wavelength = w * (0.22f + 0.14f * i)
        val step = 10f * density
        val edge = Path()
        val rock = Path().apply { moveTo(0f, h) }
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
private fun DrawScope.drawSmoke(t: Float, pan: Offset) {
    val w = size.width
    val h = size.height
    val big = maxOf(w, h)
    for (i in 0 until 3) {
        val center = Offset(
            w * (0.5f + 0.55f * sin(t * 0.045f * (i + 1) + i * 1.7f)) + pan.x * 0.25f % w,
            h * (0.55f + 0.45f * cos(t * 0.035f * (i + 2) + i * 0.9f)),
        )
        val radius = big * (0.3f + 0.08f * sin(t * 0.21f + i * 2.3f))
        val color = if (i % 2 == 0) Color(0xFF6E120B) else Color(0xFF3A0716)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent), center, radius), radius, center)
    }
    val lava = (0.34f * flicker(t)).coerceIn(0f, 1f)
    val lavaTop = h * 0.55f
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFFB8300F).copy(alpha = lava), startY = lavaTop, endY = h),
        topLeft = Offset(0f, lavaTop),
        size = Size(w, h - lavaTop),
    )
}

private const val RIDGES = 4

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
private fun InfernoAtmosphere(time: () -> Float, modifier: Modifier = Modifier) {
    val sparks = remember { Random(7).let { r -> List(40) { Spark(r.nextFloat(), 0.25f + r.nextFloat() * 0.75f, r.nextFloat(), 1f + r.nextFloat() * 2.4f) } } }
    val ash = remember { Random(11).let { r -> List(26) { Spark(r.nextFloat(), 0.3f + r.nextFloat() * 0.7f, r.nextFloat(), 0.8f + r.nextFloat() * 1.6f) } } }
    val tongues = remember { Random(3).let { r -> List(9) { r.nextFloat() } } }
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
        for ((x, speed, phase, radius) in ash) {
            val fall = (t * speed * 0.04f + phase) % 1f
            val sway = sin(fall * 9f + phase * 20f) * 22.dp.toPx()
            val alpha = 0.4f * heat * sin(fall * PI.toFloat())
            drawCircle(Color(0xFF9A8C86).copy(alpha = alpha), radius.dp.toPx(), Offset(size.width * x + sway, size.height * fall))
        }
        for ((x, speed, phase, radius) in sparks) {
            // Each spark rises at its own speed, wavering and glinting, and dies out near the top.
            val rise = (t * speed * 0.33f + phase) % 1f
            val at = Offset(size.width * x + sin(rise * 6f + phase * 10f) * 14.dp.toPx(), size.height * (1f - rise))
            val alpha = ((1f - rise) * 0.8f * heat * flicker(t * 1.5f, phase * 30f)).coerceIn(0f, 1f)
            drawCircle(Palette.flame.copy(alpha = alpha), radius.dp.toPx(), at)
            drawCircle(Palette.ember.copy(alpha = alpha * 0.35f), radius.dp.toPx() * 2.8f, at)
        }
        val step = size.width / (tongues.size - 1)
        for ((i, seed) in tongues.withIndex()) {
            val x = i * step
            val height = 215.dp.toPx() * (0.65f + 0.35f * seed) * flicker(t, seed * 40f)
            val tip = Offset(x + sin(t * 2.3f + seed * 12f) * 18.dp.toPx(), size.height - height)
            val half = step * 0.8f
            val tongue = Path().apply {
                moveTo(x - half, size.height)
                cubicTo(x - half * 0.6f, size.height - height * 0.5f, tip.x - half * 0.2f, tip.y + height * 0.3f, tip.x, tip.y)
                cubicTo(tip.x + half * 0.2f, tip.y + height * 0.3f, x + half * 0.6f, size.height - height * 0.5f, x + half, size.height)
                close()
            }
            drawPath(
                tongue,
                Brush.verticalGradient(
                    0f to Color(0x00FFD27A),
                    0.3f to Color(0xB3FF9A2E),
                    0.7f to Color(0xD9D2381A),
                    1f to Color(0xFF6A0C06),
                    startY = tip.y,
                    endY = size.height,
                ),
                alpha = heat,
            )
        }
    }
}

/** The title, glowing like a coal. */
@Composable
private fun GlowingTitle(text: String, time: () -> Float) {
    val glow = flicker(time())
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Palette.ember, Offset.Zero, 10f + 18f * (glow - 0.75f))),
        maxLines = 1,
    )
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
internal fun Modifier.portal(progress: () -> Float, origin: () -> Offset?, time: () -> Float): Modifier = drawWithContent {
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
    clipPath(Path().apply { addOval(Rect(c, r)) }) { this@drawWithContent.drawContent() }

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
    for ((reach, color, seed) in FIRE_LAYERS) drawPath(flameRing(c, r, rim * reach * grow, t, seed), color)
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

/** A ring from radius [r] out to ragged tongues up to [reach] beyond it, which flicker and crawl round. */
private fun flameRing(c: Offset, r: Float, reach: Float, t: Float, seed: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val points = 140
    for (i in 0..points) {
        val a = 2f * PI.toFloat() * i / points
        val k = 0.5f + 0.25f * sin(a * 9f + t * 3.2f + seed) + 0.17f * sin(a * 17f - t * 4.6f + seed * 2f) + 0.1f * sin(a * 31f + t * 7.5f + seed)
        val radius = r + reach * k.coerceAtLeast(0.04f)
        val x = c.x + cos(a) * radius
        val y = c.y + sin(a) * radius
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
    addOval(Rect(c, (r - 1f).coerceAtLeast(0f)))
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
