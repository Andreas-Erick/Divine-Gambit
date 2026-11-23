package com.andreaserick.multiversechess.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.PieceType
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.WaitingSoul
import com.andreaserick.multiversechess.engine.winnerOrNull
import kotlin.math.min

/**
 * The Inferno: the player's taken pieces, and a puzzle ("trial") to solve for the one chosen. The
 * clock counts down while it's the player's turn on the board.
 */
@Composable
fun InfernoScreen(
    side: Side,
    /** The other player, by name online and by side otherwise. */
    opponent: String,
    souls: List<WaitingSoul>,
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
    val reducedMotion = LocalReducedMotion.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val comfortableSquare = with(LocalDensity.current) { COMFORTABLE_SQUARE.toPx() }
    // Seconds, for the fire and smoke; only read while drawing, paused when occluded or reduced-motion.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, reducedMotion, windowFocused) {
        if (!active || reducedMotion || !windowFocused) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { nanos ->
                val dt = min((nanos - lastNanos) / 1e9f, 0.1f).coerceAtLeast(0f)
                lastNanos = nanos
                time.floatValue += dt
            }
        }
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

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Touches that land on nothing here (while a trial is forged, say) stop here rather than
            // reaching the board behind.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        val cardMaxHeight = maxHeight * 0.45f
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                HellClock(seconds, clockRunning, opponent, active)
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (soul in souls) SoulChip(soul, selected = soul.index == chosen, onClick = { onChoose(soul.index) })
                }
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
            // At most a little under half the screen, so the souls and the trial stay in view; the
            // news scrolls, and the buttons stay in reach.
            SlabCard(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = cardMaxHeight)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val soul = souls.firstOrNull { it.index == chosen }
                    val passed = trial?.outcome is Outcome.Checkmate
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LingeringText(notice) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Palette.gold)
                        }
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
                        LingeringText(trial?.message?.takeIf { !passed }) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (passed) {
                            if (souls.any { !it.free }) {
                                SecondaryPill(onClick = onLeave, modifier = Modifier.weight(1f)) { Text("To the board", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                PrimaryPill(onClick = onNext, modifier = Modifier.weight(1.2f)) { Text("Next trial", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else {
                                PrimaryPill(onClick = onLeave, modifier = Modifier.weight(1f)) { Text("Back to the board") }
                            }
                        } else {
                            // Setting the trial aside is kept away from Submit, at the far end; the main
                            // action gets the most room, so it never shrinks to a letter in large text.
                            SecondaryPill(onClick = onSkip, enabled = !checking, modifier = Modifier.weight(1f)) {
                                Text("New trial", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            SecondaryPill(onClick = onUndo, enabled = trial?.previous != null && !checking, modifier = Modifier.weight(0.8f)) {
                                Text("Undo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            PrimaryPill(
                                onClick = onSubmit,
                                enabled = trial != null && trial.state.canSubmit && !checking,
                                modifier = Modifier.weight(1.4f),
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
            text = { Text(trial.mode.puzzle!!.hint, Modifier.verticalScroll(rememberScrollState())) },
        )
    }

    if (ended != null) {
        val won = ended.winnerOrNull == side
        AlertDialog(
            onDismissRequest = onLeave,
            confirmButton = { TextButton(onClick = onLeave) { Text("Back to the board") } },
            icon = {
                val disc = when {
                    won -> Palette.gold
                    ended == Outcome.Stalemate -> Palette.inkSoft
                    else -> Palette.threat
                }
                Box(Modifier.size(52.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(FlameIcon, contentDescription = null, tint = Palette.onGold, modifier = Modifier.size(30.dp))
                }
            },
            title = { Text(if (won) "You won" else if (ended == Outcome.Stalemate) "Stalemate" else "You lost") },
            text = {
                Text(
                    when (ended) {
                        is Outcome.Checkmate ->
                            if (won) "While you were in the Inferno, the game ended: $opponent has no legal turn."
                            else "While you were in the Inferno, $opponent checkmated you."
                        is Outcome.Resignation ->
                            if (won) "While you were in the Inferno, $opponent resigned."
                            else "While you were in the Inferno, the game was resigned."
                        Outcome.Stalemate -> "While you were in the Inferno, the game ended in stalemate."
                        Outcome.Ongoing -> ""
                    },
                )
            },
        )
    }
}

/**
 * Time left in the Inferno; paused, with whose turn it is, while the opponent plays. Low on time,
 * it pulses red, only while on screen ([active]); the digits stay light, to read at a glance.
 */
@Composable
private fun HellClock(seconds: Int, running: Boolean, opponent: String, active: Boolean) {
    val low = running && seconds <= 15
    val reducedMotion = LocalReducedMotion.current
    val pulse = if (!low || reducedMotion || !active) null else rememberInfiniteTransition(label = "clock").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse",
    )
    val shape = RoundedCornerShape(50)
    val fill = if (low) Palette.threat else Palette.card
    Surface(
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (low) Palette.threat else Palette.presentEdge),
        // The pulse is read while drawing, so it doesn't recompose the clock on every frame.
        modifier = Modifier
            .widthIn(min = 76.dp, max = 140.dp)
            .drawBehind {
                drawRect(if (low) fill.copy(alpha = 0.25f + 0.25f * (pulse?.value ?: 1f)) else fill)
            },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (running) String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60) else "Paused",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR_NUMS),
                color = Palette.ink,
                maxLines = 1,
            )
            Text(
                if (running) "your turn" else "$opponent's turn",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A taken piece, with a pip for each trial: lit once passed. */
@Composable
private fun SoulChip(soul: WaitingSoul, selected: Boolean, onClick: () -> Unit) {
    val name = soul.soul.type.displayName.lowercase()
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = !soul.free,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = if (soul.free) "Your $name, free" else "Your $name, ${soul.solved} of ${soul.soul.trials} trials passed"
            this.selected = selected
            role = Role.Tab
            if (!soul.free) onClick(label = "Seek this piece") { onClick(); true }
        },
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
