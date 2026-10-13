package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
    val solvedCount = puzzles.count { it.id in solved }
    Box(modifier.fillMaxSize().background(Palette.parchment), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 560.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
                    RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                    Spacer(Modifier.size(20.dp))
                    Text("Puzzles", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "White to move and checkmate in one turn. Pieces can strike through time and across timelines.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.inkSoft,
                    )
                    Spacer(Modifier.size(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { if (puzzles.isEmpty()) 0f else solvedCount / puzzles.size.toFloat() },
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)),
                            color = Palette.amethyst,
                            trackColor = Palette.floorBand,
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("$solvedCount of ${puzzles.size} solved", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            itemsIndexed(puzzles, key = { _, p -> p.id }) { i, puzzle ->
                PuzzleCard(i + 1, puzzle, puzzle.id in solved, onClick = { onOpen(puzzle) })
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun PuzzleCard(number: Int, puzzle: Puzzle, solved: Boolean, onClick: () -> Unit) {
    val preview = remember(puzzle) {
        val start = puzzle.start()
        val (l, t) = start.focusBoard()
        start.boardAt(l, t)!! to start.timelines.size
    }
    Box {
        Box(
            Modifier
                .matchParentSize()
                .padding(start = 5.dp, top = 6.dp)
                .background(Palette.slab, MaterialTheme.shapes.large),
        )
        Surface(
            onClick = onClick,
            modifier = Modifier.padding(end = 5.dp, bottom = 6.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Palette.card,
            border = BorderStroke(if (solved) 2.dp else 1.dp, if (solved) Palette.gold else Palette.presentEdge),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(92.dp)
                        .background(Palette.whiteBoardFrame, RoundedCornerShape(6.dp))
                        .padding(4.dp),
                ) {
                    BoardPreview(preview.first, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Puzzle $number", style = MaterialTheme.typography.labelMedium, color = Palette.amethyst)
                    Text(puzzle.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (preview.second > 1) "${preview.second} timelines" else "One timeline",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (solved) {
                    Box(
                        Modifier.size(30.dp).background(Palette.gold, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, "Solved", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
