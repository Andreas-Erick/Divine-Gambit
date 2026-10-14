package com.andreaserick.multiversechess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Puzzle
import kotlinx.coroutines.launch

@Composable
fun GameScreen(
    session: GameSession,
    thinking: Boolean,
    nextPuzzle: Puzzle?,
    onTap: (Pos) -> Unit,
    onSubmit: () -> Unit,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onShowSolution: () -> Unit,
    onStartPuzzle: (Puzzle) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = remember { CameraState() }
    val scope = rememberCoroutineScope()
    var showLog by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var confirmSolution by remember { mutableStateOf(false) }
    var dismissedOutcome by remember { mutableStateOf<Outcome?>(null) }
    val puzzle = session.mode.puzzle
    val state = session.state
    val ongoing = session.outcome == Outcome.Ongoing

    // A light tick for each move, and a firmer one when the turn passes.
    val haptics = LocalHapticFeedback.current
    var movesSeen by remember { mutableIntStateOf(state.turnMoves.size) }
    var sideSeen by remember { mutableStateOf(state.sideToMove) }
    LaunchedEffect(state.turnMoves.size, state.sideToMove) {
        when {
            state.sideToMove != sideSeen -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            state.turnMoves.size > movesSeen -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        movesSeen = state.turnMoves.size
        sideSeen = state.sideToMove
    }

    // Glide to the present at the start of each turn (and once the view has a size), but not after
    // every move, so the player keeps sight of what they just did.
    val turnStart = state.takeIf { it.turnMoves.isEmpty() }?.timelines
    val comfortableSquare = with(LocalDensity.current) { COMFORTABLE_SQUARE.toPx() }
    suspend fun focusPresent(resetZoom: Boolean = false) {
        val (l, t) = state.focusBoard()
        camera.focus(state.focusArea(), resetZoom, comfortableSquare, fallback = Layout.boardRect(l, t))
    }
    LaunchedEffect(state.sideToMove, turnStart, camera.viewSize) {
        if (turnStart != null) focusPresent()
    }

    Box(modifier.fillMaxSize()) {
        MultiverseView(session, camera, onTap, Modifier.fillMaxSize())

        // Top: back, title and tools, floating over the multiverse on a band of parchment that
        // fades out, so boards passing underneath don't run through the title.
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { camera.coveredTop = it.height.toFloat() }
                .background(
                    Brush.verticalGradient(
                        0f to Palette.parchment.copy(alpha = 0.97f),
                        0.7f to Palette.parchment.copy(alpha = 0.88f),
                        1f to Palette.parchment.copy(alpha = 0f),
                    ),
                )
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onExit)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    puzzle?.title ?: session.mode.variant.displayName.let { if (it == "Standard") "Multiverse Chess" else it },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        puzzle != null -> "Puzzle · checkmate in one turn"
                        session.mode.computerSide != null -> "vs computer"
                        else -> "Pass and play"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.inkSoft,
                )
            }
            RoundIconButton(Icons.Filled.Info, "How to play", { showRules = true })
            Spacer(Modifier.width(8.dp))
            Box {
                RoundIconButton(Icons.Filled.MoreVert, "More", { showMenu = true })
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (puzzle != null) {
                        DropdownMenuItem(text = { Text("Hint") }, onClick = { showMenu = false; showHint = true })
                        DropdownMenuItem(
                            text = { Text("Show solution") },
                            enabled = ongoing && !thinking,
                            onClick = { showMenu = false; confirmSolution = true },
                        )
                    } else {
                        DropdownMenuItem(text = { Text("Move list") }, onClick = { showMenu = false; showLog = true })
                        DropdownMenuItem(
                            text = { Text("Resign") },
                            enabled = ongoing,
                            onClick = { showMenu = false; confirmResign = true },
                        )
                    }
                }
            }
        }

        // Below the tools: zoom out to the whole multiverse.
        RoundIconButton(
            OverviewIcon,
            "Show the whole multiverse",
            { scope.launch { camera.frame(state.overviewArea()) } },
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 68.dp, end = 12.dp),
        )

        // Bottom: status and turn controls.
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
                    StatusLine(session, thinking)
                    AnimatedVisibility(session.message != null) {
                        Text(
                            session.message.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryPill(onClick = onUndo, enabled = session.previous != null) { Text("Undo") }
                        SecondaryPill(onClick = {
                            scope.launch { focusPresent(resetZoom = true) }
                        }) { Text("Present") }
                        PrimaryPill(
                            onClick = onSubmit,
                            // After the game, Submit explains why the moves tried can't be played.
                            enabled = !thinking && !session.isComputerTurn &&
                                ((ongoing && state.canSubmit) || (session.isExploring && state.turnMoves.isNotEmpty())),
                            modifier = Modifier.weight(1f),
                        ) { Text("Submit", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }

    if (showRules) RulesDialog(onDismiss = { showRules = false })

    if (showHint && puzzle != null) {
        AlertDialog(
            onDismissRequest = { showHint = false },
            confirmButton = { TextButton(onClick = { showHint = false }) { Text("Got it") } },
            title = { Text("Hint") },
            text = { Text(puzzle.hint) },
        )
    }

    if (confirmSolution) {
        AlertDialog(
            onDismissRequest = { confirmSolution = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmSolution = false
                    onShowSolution()
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { confirmSolution = false }) { Text("Keep trying") } },
            title = { Text("Show the solution?") },
            text = { Text("The puzzle won't count as solved.") },
        )
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
            title = { Text("Moves") },
            text = {
                if (session.log.isEmpty()) {
                    Text("No moves yet.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(session.log) { i, line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Palette.inkSoft, modifier = Modifier.width(28.dp))
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
        )
    }

    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmResign = false
                    onResign()
                }) { Text("Resign") }
            },
            dismissButton = { TextButton(onClick = { confirmResign = false }) { Text("Keep playing") } },
            title = { Text("Resign this game?") },
        )
    }

    val outcome = session.outcome
    if (outcome != Outcome.Ongoing && outcome != dismissedOutcome) {
        val solved = puzzle != null
        AlertDialog(
            onDismissRequest = { dismissedOutcome = outcome },
            confirmButton = {
                when {
                    !solved -> TextButton(onClick = onExit) { Text("Back to menu") }
                    nextPuzzle != null -> TextButton(onClick = { onStartPuzzle(nextPuzzle) }) { Text("Next puzzle") }
                    else -> TextButton(onClick = onExit) { Text("All puzzles") }
                }
            },
            dismissButton = { TextButton(onClick = { dismissedOutcome = outcome }) { Text("View board") } },
            icon = {
                Box(Modifier.size(52.dp).background(if (outcome == Outcome.Stalemate) Palette.inkSoft else Palette.gold, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        if (outcome is Outcome.Checkmate) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            },
            title = { Text(if (solved) "Puzzle solved" else outcomeTitle(outcome)) },
            text = {
                Text(
                    when {
                        !solved -> outcomeText(outcome)
                        session.solutionShown -> "That's the checkmate. Try the next one on your own."
                        else -> "Checkmate. Every reply leaves a black king exposed somewhere in the multiverse."
                    },
                )
            },
        )
    }
}

@Composable
private fun StatusLine(session: GameSession, thinking: Boolean) {
    val state = session.state
    val side = state.sideToMove
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (thinking) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.amethyst)
        } else {
            SideDisc(side)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(headline(session, thinking), style = MaterialTheme.typography.titleMedium)
            Text(detail(session), style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
        if (session.outcome == Outcome.Ongoing && state.threats().isNotEmpty()) {
            Text(
                "CHECK",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .background(Palette.threat, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun headline(session: GameSession, thinking: Boolean): String {
    if (session.outcome != Outcome.Ongoing) return outcomeTitle(session.outcome)
    val side = session.state.sideToMove.displayName
    return when {
        thinking && session.mode.puzzle != null -> "Checking your answer…"
        thinking -> "$side is thinking…"
        session.mode.computerSide != null && session.mode.computerSide != session.state.sideToMove -> "Your turn · $side"
        else -> "$side to move"
    }
}

private fun detail(session: GameSession): String {
    if (session.isExploring) {
        return outcomeText(session.outcome) + " Try ${session.state.sideToMove.displayName}'s moves to see why."
    }
    if (session.outcome != Outcome.Ongoing) return outcomeText(session.outcome)
    val state = session.state
    val parts = mutableListOf("Present: turn ${Notation.turnNumber(state.present)}")
    val left = state.mandatoryTimelines.size
    when {
        state.canSubmit -> parts += "ready to submit"
        left == 1 -> parts += "1 board to play"
        left > 1 -> parts += "$left boards to play"
    }
    if (state.timelines.size > 1) parts += "${state.timelines.size} timelines"
    return parts.joinToString(" · ")
}

private fun outcomeTitle(outcome: Outcome): String = when (outcome) {
    Outcome.Ongoing -> ""
    is Outcome.Checkmate -> "Checkmate"
    Outcome.Stalemate -> "Stalemate"
    is Outcome.Resignation -> "Resignation"
}

private fun outcomeText(outcome: Outcome): String = when (outcome) {
    Outcome.Ongoing -> ""
    is Outcome.Checkmate -> "${outcome.winner.displayName} wins."
    Outcome.Stalemate -> "No legal turn and no king in danger: the game is drawn."
    is Outcome.Resignation -> "${outcome.winner.opponent.displayName} resigned. ${outcome.winner.displayName} wins."
}
