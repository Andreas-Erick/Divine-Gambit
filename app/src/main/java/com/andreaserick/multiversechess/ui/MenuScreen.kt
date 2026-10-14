package com.andreaserick.multiversechess.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Variant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun MenuScreen(
    onStart: (GameMode) -> Unit,
    onPuzzles: () -> Unit,
    puzzleProgress: String,
    modifier: Modifier = Modifier,
) {
    var showRules by remember { mutableStateOf(false) }
    var showVariants by remember { mutableStateOf(false) }
    var variant by rememberSaveable { mutableStateOf(Variant.STANDARD) }

    Box(modifier.fillMaxSize()) {
        TimeVortex(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("Multiverse", style = MaterialTheme.typography.displayMedium, color = Palette.ink)
            Text(
                "C H E S S",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.amethyst,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Move through time. Branch the timelines.\nProtect your kings in the present and the past.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.inkSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))

            SlabCard(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("New game", style = MaterialTheme.typography.titleLarge)
                            Text(variant.description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
                        }
                        Spacer(Modifier.width(12.dp))
                        SecondaryPill(onClick = { showVariants = true }) { Text(variant.displayName) }
                    }
                    Spacer(Modifier.height(2.dp))
                    PrimaryPill(
                        onClick = { onStart(GameMode(computerSide = Side.BLACK, variant = variant)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Play as White") }
                    PrimaryPill(
                        onClick = { onStart(GameMode(computerSide = Side.WHITE, variant = variant)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Play as Black") }
                    SecondaryPill(onClick = { onStart(GameMode(variant = variant)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Pass and play")
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Palette.presentEdge.copy(alpha = 0.6f))
                    SecondaryPill(onClick = onPuzzles, modifier = Modifier.fillMaxWidth()) {
                        Text("Puzzles")
                        Spacer(Modifier.weight(1f))
                        Text(puzzleProgress, color = Palette.inkSoft)
                    }
                    TextButton(onClick = { showRules = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("How to play")
                    }
                }
            }
        }
    }

    if (showRules) RulesDialog(onDismiss = { showRules = false })
    if (showVariants) {
        VariantDialog(
            selected = variant,
            onSelect = {
                variant = it
                showVariants = false
            },
            onDismiss = { showVariants = false },
        )
    }
}

/**
 * A slowly turning checkerboard bent into a logarithmic spiral, receding toward a vanishing
 * point: the board falling through time.
 */
@Composable
private fun TimeVortex(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "vortex")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    val light = Color(0xFFEAE4D8)
    val dark = Color(0xFFD8CFE3)
    val path = remember { Path() }
    Canvas(modifier) {
        drawRect(light)
        val center = Offset(size.width * 0.5f, size.height * 0.24f)
        val maxRadius = hypot(size.width, size.height)
        val sectors = 16
        val growth = 1.17f
        val twist = 0.2f
        val sweep = 2 * PI.toFloat() / sectors
        var r = 4f
        var ring = 0
        // Rings grow geometrically, and each is turned a little further than the one inside it.
        while (r < maxRadius) {
            val next = r * growth
            for (k in 0 until sectors) {
                if ((k + ring) % 2 == 0) continue
                val a0 = (k.toFloat() / sectors + turn) * 2 * PI.toFloat() + ring * twist
                path.reset()
                val steps = 5
                for (i in 0..steps) {
                    val a = a0 + sweep * i / steps
                    val p = center + Offset(cos(a), sin(a)) * r
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                for (i in steps downTo 0) {
                    val a = a0 + twist + sweep * i / steps
                    val p = center + Offset(cos(a), sin(a)) * next
                    path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, dark)
            }
            r = next
            ring++
        }
        // Mist at the vanishing point, and parchment toward the bottom so the menu stays readable.
        drawRect(Brush.radialGradient(listOf(light, light.copy(alpha = 0f)), center = center, radius = size.minDimension * 0.16f))
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.4f to Palette.parchment.copy(alpha = 0.3f),
                1f to Palette.parchment.copy(alpha = 0.95f),
            ),
        )
    }
}

@Composable
private fun VariantDialog(selected: Variant, onSelect: (Variant) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Variant") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in Variant.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(option.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun RulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How to play") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((heading, body) in RULES) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall, color = Palette.amethyst)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}

private val RULES = listOf(
    "The multiverse" to
        "Each row is a timeline (purple ribbon) and each column a half-move, advancing to the right. " +
        "Drag to pan and pinch to zoom.",
    "Finding your way" to
        "Tap a timeline's ribbon, or its label on the left edge, to see the whole timeline. Tap a " +
        "board that's too small to play on to zoom in on it. The corners button shows the whole " +
        "multiverse, and Present brings you back to the boards you have to play.",
    "Four dimensions" to
        "Pieces move as in chess, but through file, rank, time (one step = one full turn back) and " +
        "timeline (one row up or down).",
    "Pieces" to
        "Rook: any distance along one dimension. Bishop: equal distance along two. Queen: equal " +
        "distance along any number. King: one step in any combination. Knight: two along one " +
        "dimension and one along another.",
    "Pawns" to
        "Pawns advance one rank, or one timeline toward the opponent (up for White, down for Black); " +
        "an unmoved pawn may go two either way. They capture diagonally on the board, or one " +
        "timeline forward and one turn back or ahead.",
    "Branching" to
        "Moving to an earlier board creates a new timeline. Moving to the latest board of another " +
        "timeline just continues it.",
    "The present" to
        "The pale column marks the present: the earliest end of any active timeline. You must move " +
        "on every board in it (gold ring), may move on your other raised boards, then tap Submit.",
    "Active timelines" to
        "Each player may have only one more active timeline than the opponent. Extra timelines are " +
        "faded and don't hold back the present. A purple arrow at the end of the present means " +
        "your next new timeline would be active, so branching into the past would pull the " +
        "present back with it.",
    "Check and mate" to
        "You can't submit a turn that lets the opponent capture any of your kings, now or in the " +
        "past. A red ! marks a timeline whose advancement would let a king be captured. With no " +
        "way to move the present without leaving a king attacked you are checkmated, or " +
        "stalemated if no king is threatened. After the game you can still try the loser's " +
        "moves to see why.",
    "Fairy pieces" to
        "Some variants add pieces drawn as lettered discs. S, princess: a rook or bishop move. " +
        "U, unicorn: equal distance along exactly three dimensions. D, dragon: equal distance " +
        "along all four. W, brawn: a pawn that can capture one step along any diagonal that " +
        "includes a forward step (rank or timeline) and no backward one.",
)
