package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.Puzzle

@Composable
fun PuzzleListScreen(
    puzzles: List<Puzzle>,
    solved: Set<String>,
    onOpen: (Puzzle) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Menu") }
            Spacer(Modifier.weight(1f))
            Text(
                "${puzzles.count { it.id in solved }} / ${puzzles.size} solved",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.widthIn(max = 480.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Puzzles", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "White to move and checkmate in one turn. Remember that pieces can strike through time and across timelines.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn(Modifier.padding(top = 12.dp)) {
                itemsIndexed(puzzles) { i, puzzle ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(puzzle) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
                        Text(puzzle.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (puzzle.id in solved) {
                            Text("✓ Solved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
