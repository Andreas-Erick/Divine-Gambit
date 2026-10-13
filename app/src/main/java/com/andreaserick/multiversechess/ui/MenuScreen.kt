package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Variant

@Composable
fun MenuScreen(onStart: (GameMode) -> Unit, modifier: Modifier = Modifier) {
    var showRules by remember { mutableStateOf(false) }
    var showVariants by remember { mutableStateOf(false) }
    var variant by rememberSaveable { mutableStateOf(Variant.STANDARD) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Multiverse Chess",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Chess with time travel and parallel timelines",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { showVariants = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Variant: ${variant.displayName}")
            }
            Button(onClick = { onStart(GameMode(variant = variant)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Two players (pass and play)")
            }
            Button(onClick = { onStart(GameMode(computerSide = Side.BLACK, variant = variant)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Play White vs computer")
            }
            Button(onClick = { onStart(GameMode(computerSide = Side.WHITE, variant = variant)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Play Black vs computer")
            }
            OutlinedButton(onClick = { showRules = true }, modifier = Modifier.fillMaxWidth()) {
                Text("How to play")
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
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(option.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(option.description, style = MaterialTheme.typography.bodySmall)
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (paragraph in RULES) Text(paragraph, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

private val RULES = listOf(
    "The screen shows the multiverse: each row is a timeline and each column a half-move, " +
        "advancing to the right. Drag to pan and pinch to zoom.",
    "Pieces move as in chess, but through four dimensions: file, rank, time (one step = one " +
        "full turn back) and timeline (one row up or down).",
    "Rook: any distance along one dimension. Bishop: equal distance along two. Queen: equal " +
        "distance along any number. King: one step in any combination. Knight: two along one " +
        "dimension and one along another.",
    "Pawns advance one rank, or one timeline toward the opponent (up for White, down for " +
        "Black); an unmoved pawn may go two either way. They capture diagonally on the board, " +
        "or one timeline forward and one turn back or ahead.",
    "Moving to an earlier board creates a new timeline. Moving to the latest board of " +
        "another timeline just continues it.",
    "The present is the earliest active timeline. You must move on every board in the " +
        "present (outlined in gold), may move on your other latest boards, then tap Submit.",
    "Each player may only have one more active timeline than the opponent; extra timelines " +
        "are dimmed and don't hold back the present.",
    "You can't submit a turn that lets the opponent capture any of your kings. If you have " +
        "no legal turn you are checkmated (or stalemated if no king is threatened).",
    "Some variants add fairy pieces, drawn as lettered discs. S, princess: a rook or bishop " +
        "move. U, unicorn: equal distance along exactly three dimensions. D, dragon: equal " +
        "distance along all four. W, brawn: a pawn that can also capture by combining one step " +
        "forward (rank or timeline) with one step along any other dimension.",
)
