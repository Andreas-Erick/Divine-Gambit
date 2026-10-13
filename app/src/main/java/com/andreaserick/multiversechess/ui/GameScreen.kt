package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.Pos

@Composable
fun GameScreen(
    session: GameSession,
    thinking: Boolean,
    onTap: (Pos) -> Unit,
    onSubmit: () -> Unit,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = remember { CameraState() }
    var showLog by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    var dismissedOutcome by remember { mutableStateOf<Outcome?>(null) }
    val state = session.state

    // Re-center on the present whenever the turn changes (and once the view has a size).
    LaunchedEffect(state.sideToMove, state.present, camera.viewSize) {
        val (l, t) = state.focusBoard()
        camera.focus(l, t)
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("Menu") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showRules = true }) { Text("Rules") }
            TextButton(onClick = { showLog = true }) { Text("Moves") }
            TextButton(onClick = { confirmResign = true }, enabled = session.outcome == Outcome.Ongoing) { Text("Resign") }
        }

        MultiverseView(session, camera, onTap, Modifier.weight(1f).fillMaxWidth())

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText(session, thinking), style = MaterialTheme.typography.titleMedium)
                session.message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onUndo, enabled = session.previous != null) { Text("Undo") }
                    OutlinedButton(onClick = {
                        val (l, t) = state.focusBoard()
                        camera.focus(l, t, resetZoom = true)
                    }) { Text("Present") }
                    Button(
                        onClick = onSubmit,
                        enabled = state.canSubmit && !session.isComputerTurn && session.outcome == Outcome.Ongoing,
                        modifier = Modifier.weight(1f),
                    ) { Text("Submit", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }

    if (showRules) RulesDialog(onDismiss = { showRules = false })

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
            title = { Text("Moves") },
            text = {
                if (session.log.isEmpty()) {
                    Text("No moves yet.")
                } else {
                    LazyColumn { itemsIndexed(session.log) { i, line -> Text("${i + 1}. $line", style = MaterialTheme.typography.bodySmall) } }
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
        AlertDialog(
            onDismissRequest = { dismissedOutcome = outcome },
            confirmButton = { TextButton(onClick = onExit) { Text("Back to menu") } },
            dismissButton = { TextButton(onClick = { dismissedOutcome = outcome }) { Text("View board") } },
            title = { Text("Game over") },
            text = { Text(outcomeText(outcome)) },
        )
    }
}

private fun outcomeText(outcome: Outcome): String = when (outcome) {
    Outcome.Ongoing -> ""
    is Outcome.Checkmate -> "Checkmate. ${outcome.winner.displayName} wins."
    Outcome.Stalemate -> "Stalemate. The game is drawn."
    is Outcome.Resignation -> "${outcome.winner.opponent.displayName} resigned. ${outcome.winner.displayName} wins."
}

private fun statusText(session: GameSession, thinking: Boolean): String {
    if (session.outcome != Outcome.Ongoing) return outcomeText(session.outcome)
    val state = session.state
    val side = state.sideToMove.displayName
    if (thinking) return "Computer ($side) is thinking…"
    val parts = mutableListOf("$side to move", "present T${Notation.turnNumber(state.present)}")
    val left = state.mandatoryTimelines.size
    when {
        state.canSubmit -> parts += "ready to submit"
        left > 0 -> parts += if (left == 1) "1 board to play" else "$left boards to play"
    }
    if (state.threats().isNotEmpty()) parts += "CHECK"
    return parts.joinToString(" · ")
}
