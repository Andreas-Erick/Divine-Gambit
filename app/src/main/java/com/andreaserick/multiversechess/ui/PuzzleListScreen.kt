package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.Puzzle
import com.andreaserick.multiversechess.engine.Puzzles

@Composable
fun PuzzleListScreen(
    puzzles: List<Puzzle>,
    solved: Set<String>,
    canGenerate: Boolean,
    generating: Boolean,
    generateFailed: Boolean,
    aiName: String?,
    designing: String?,
    designFailed: String?,
    onDesign: (String?) -> Unit,
    onAiSettings: () -> Unit,
    onGenerate: () -> Unit,
    onCancelDesign: () -> Unit = {},
    onOpen: (Puzzle) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val solvedCount = puzzles.count { it.id in solved }
    Box(modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.TopCenter) {
        // One column of puzzles on a phone; on a tablet two or three side by side, rather than one
        // narrow column with empty space either side.
        LazyVerticalGrid(
            GridCells.Adaptive(340.dp),
            Modifier.widthIn(max = 1120.dp).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 8.dp).widthIn(max = 640.dp)) {
                    RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                    Spacer(Modifier.size(20.dp))
                    Text("Puzzles", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Checkmate in one turn. Pieces can strike through time and across timelines.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.inkSoft,
                    )
                    Spacer(Modifier.size(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { if (puzzles.isEmpty()) 0f else solvedCount / puzzles.size.toFloat() },
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)),
                            color = Palette.accent,
                            trackColor = Palette.floorBand,
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "$solvedCount of ${puzzles.size} solved",
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_NUMS),
                        )
                    }
                }
            }
            val nextUp = puzzles.firstOrNull { it.id !in solved }?.id
            itemsIndexed(puzzles, key = { _, p -> p.id }) { i, puzzle ->
                PuzzleCard(i + 1, puzzle, puzzle.id in solved, next = puzzle.id == nextUp, onClick = { onOpen(puzzle) })
            }
            // The two ways to more puzzles: side by side when there's room for both, else one above the other.
            item(span = { GridItemSpan(maxLineSpan) }) {
                BoxWithConstraints {
                    val generate = @Composable { m: Modifier ->
                        GenerateCard(
                            builtInLeft = Puzzles.all.count { it.id !in solved },
                            canGenerate = canGenerate,
                            generating = generating,
                            failed = generateFailed,
                            onGenerate = onGenerate,
                            modifier = m,
                        )
                    }
                    val byAi = @Composable { m: Modifier -> AiPuzzleCard(aiName, designing, designFailed, onDesign, onAiSettings, onCancelDesign, m) }
                    if (maxWidth >= 694.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            generate(Modifier.weight(1f))
                            byAi(Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            generate(Modifier.fillMaxWidth())
                            byAi(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

/** Makes a new puzzle, once every built-in one is solved. */
@Composable
private fun GenerateCard(builtInLeft: Int, canGenerate: Boolean, generating: Boolean, failed: Boolean, onGenerate: () -> Unit, modifier: Modifier = Modifier) {
    SlabCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New puzzles", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !canGenerate -> "Solve the ${if (builtInLeft == 1) "last puzzle" else "$builtInLeft remaining puzzles"} above to unlock new ones, made for you on this device."
                    failed -> "No puzzle turned up this time. Try again."
                    else -> "Make a fresh checkmate-in-one-turn puzzle. Every one is checked to have one or two solutions."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.inkSoft,
            )
            PrimaryPill(onClick = onGenerate, enabled = canGenerate && !generating, modifier = Modifier.fillMaxWidth()) {
                if (generating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.onAccent)
                    Spacer(Modifier.width(10.dp))
                    Text("Making a puzzle…")
                } else {
                    Text("Generate a puzzle")
                }
            }
        }
    }
}

/** Has the AI design a puzzle, optionally on a theme; or offers to set one up. */
@Composable
private fun AiPuzzleCard(
    aiName: String?,
    designing: String?,
    failed: String?,
    onDesign: (String?) -> Unit,
    onAiSettings: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var theme by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    SlabCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Puzzles by AI", style = MaterialTheme.typography.titleMedium)
            if (aiName == null) {
                Text(
                    "Set up an AI opponent, and it can design puzzles for you. The engine checks each one, so every puzzle really is mate in one turn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.inkSoft,
                )
                SecondaryPill(onClick = onAiSettings, modifier = Modifier.fillMaxWidth()) { Text("Set up an AI opponent") }
                return@Column
            }
            Text(
                failed ?: "$aiName composes a puzzle, and the engine checks it really is mate in one turn, telling $aiName " +
                    "what to fix. This can take a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) MaterialTheme.colorScheme.error else Palette.inkSoft,
            )
            OutlinedTextField(
                value = theme,
                onValueChange = { if (it.length <= 80) theme = it },
                label = { Text("Theme (optional)") },
                placeholder = { Text("e.g. a knight from another timeline") },
                singleLine = true,
                enabled = designing == null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    focusManager.clearFocus()
                    if (designing == null) onDesign(theme.ifBlank { null })
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryPill(onClick = { onDesign(theme.ifBlank { null }) }, enabled = designing == null, modifier = Modifier.fillMaxWidth()) {
                if (designing != null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.onAccent)
                    Spacer(Modifier.width(10.dp))
                    Text("Designing…")
                } else {
                    Text("Ask $aiName for a puzzle", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            designing?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
                SecondaryPill(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun PuzzleCard(number: Int, puzzle: Puzzle, solved: Boolean, next: Boolean, onClick: () -> Unit) {
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
            border = BorderStroke(
                if (solved || next) 2.dp else 1.dp,
                when {
                    solved -> Palette.gold
                    next -> Palette.accent
                    else -> Palette.presentEdge
                },
            ),
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
                    Text(if (next) "Puzzle $number · Next up" else "Puzzle $number", style = MaterialTheme.typography.labelMedium, color = Palette.accent)
                    Text(puzzle.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        Icon(Icons.Filled.Check, "Solved", tint = Palette.onGold, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
