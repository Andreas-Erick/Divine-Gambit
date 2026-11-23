package com.andreaserick.multiversechess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.GameKind
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Variant
import com.andreaserick.multiversechess.online.Ticket

/** Which page of the menu's card is showing. */
private enum class MenuPage { HOME, SINGLE, MULTI }

@Composable
fun MenuScreen(
    onStart: (GameMode) -> Unit,
    resumable: GameSession?,
    onContinue: () -> Unit,
    /** Throws away the game on offer under Continue game. */
    onDiscard: () -> Unit,
    onPuzzles: () -> Unit,
    puzzleProgress: String,
    canPlayOnline: Boolean,
    resumableOnline: Ticket?,
    onContinueOnline: () -> Unit,
    /** Forgets the online game on offer under Continue game with a friend. */
    onDiscardOnline: () -> Unit,
    playerName: String,
    aiName: String?,
    onAiSettings: () -> Unit,
    onInvite: (Variant, Int?, Side, String, Boolean) -> Unit,
    onJoin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(MenuPage.HOME) }
    var showRules by remember { mutableStateOf(false) }
    var showVariants by remember { mutableStateOf(false) }
    var variant by rememberSaveable { mutableStateOf(Variant.STANDARD) }
    var timelineLimit by rememberSaveable { mutableStateOf<Int?>(GameSession.DEFAULT_TIMELINE_LIMIT) }
    var showLimits by remember { mutableStateOf(false) }
    var showOpponents by remember { mutableStateOf(false) }
    // Play the AI, if one is set up and was chosen.
    var preferAi by rememberSaveable { mutableStateOf(true) }
    val againstAi = preferAi && aiName != null
    var showInvite by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var inferno by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDiscardOnline by remember { mutableStateOf(false) }
    var titleCenter by remember { mutableStateOf<Offset?>(null) }
    var cardBottom by remember { mutableStateOf<Float?>(null) }

    BackHandler(enabled = page != MenuPage.HOME) { page = MenuPage.HOME }

    Box(modifier.fillMaxSize()) {
        // The timeline branches a little below the card, but never into the warmth at the bottom.
        val forkGap = with(LocalDensity.current) { 56.dp.toPx() }
        Heavens(light = { titleCenter }, fork = { cardBottom?.plus(forkGap) }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            // The heavens' light shines behind the title.
            Column(
                Modifier.onGloballyPositioned { titleCenter = it.boundsInRoot().center },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Divine",
                    style = MaterialTheme.typography.displayMedium.copy(shadow = Shadow(Color(0x73FFE29A), blurRadius = 48f)),
                    color = Palette.ink,
                )
                Text(
                    "G A M B I T",
                    style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Color(0x59E8C77A), blurRadius = 24f)),
                    color = Palette.accent,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Move through time. Branch the timelines.\nDescend, and win back the fallen.",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = Palette.inkSoft,
                textAlign = TextAlign.Center,
            )
            Ornament(Modifier.padding(top = 16.dp, bottom = 20.dp))

            SlabCard(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { cardBottom = it.boundsInRoot().bottom }
                    .drawWithContent {
                        drawContent()
                        // The card's own corners, inside the slab beneath it (5 and 6 dp).
                        drawCardFlourishes(inset = 9.dp.toPx(), length = 18.dp.toPx(), insetEnd = 14.5.dp.toPx())
                    },
            ) {
                // Pages slide in from the right going in, and back out to the right going home.
                val reducedMotion = LocalReducedMotion.current
                AnimatedContent(
                    page,
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
                        } else {
                            val direction = if (targetState != MenuPage.HOME) 1 else -1
                            (slideInHorizontally { it / 4 * direction } + fadeIn())
                                .togetherWith(slideOutHorizontally { -it / 4 * direction } + fadeOut())
                                .using(SizeTransform(clip = false))
                        }
                    },
                    label = "menu page",
                ) { shown ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        @Composable
                        fun variantRow() = SettingRow("Variant", variant.description) {
                            SecondaryPill(onClick = { showVariants = true }) { Text(variant.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }

                        @Composable
                        fun timelinesRow() = SettingRow("Timelines", "How many each player may create.") {
                            SecondaryPill(onClick = { showLimits = true }) { Text(limitLabel(timelineLimit), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }

                        @Composable
                        fun infernoRow(note: String) = ToggleRow(
                            "Inferno",
                            "Taken pieces go to the Inferno. Solve puzzles there, while the game goes on, to win them back.$note",
                            checked = inferno,
                            onCheckedChange = { inferno = it },
                        )

                        when (shown) {
                            MenuPage.HOME -> {
                                if (resumable != null) {
                                    ResumeRow("Continue game", resumeSummary(resumable), "Discard this game", onContinue) { confirmDiscard = true }
                                }
                                if (canPlayOnline && resumableOnline != null) {
                                    ResumeRow(
                                        "Continue game with a friend",
                                        listOfNotNull(
                                            resumableOnline.opponentName?.let { "vs $it" },
                                            "you play ${resumableOnline.side.displayName}",
                                            resumableOnline.displayCode,
                                        ).joinToString(" · "),
                                        "Forget this online game",
                                        onContinueOnline,
                                    ) { confirmDiscardOnline = true }
                                }
                                if (resumable != null || (canPlayOnline && resumableOnline != null)) {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Palette.cardEdge)
                                }
                                val nothingToContinue = resumable == null && !(canPlayOnline && resumableOnline != null)
                                MenuChoice("Singleplayer", "Against the computer or an AI.", primary = nothingToContinue) { page = MenuPage.SINGLE }
                                MenuChoice(
                                    "Multiplayer",
                                    if (canPlayOnline) "Pass and play on this phone, or a friend online." else "Pass and play on this phone.",
                                ) { page = MenuPage.MULTI }
                                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Palette.cardEdge)
                                SecondaryPill(onClick = onPuzzles, modifier = Modifier.fillMaxWidth()) {
                                    Text("Puzzles")
                                    Spacer(Modifier.weight(1f))
                                    Text(puzzleProgress, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_NUMS), color = Palette.inkSoft)
                                }
                                Row(Modifier.align(Alignment.CenterHorizontally)) {
                                    TextButton(onClick = { showRules = true }) { Text("How to play") }
                                    TextButton(onClick = onAiSettings) { Text("AI opponent") }
                                }
                            }
                            MenuPage.SINGLE -> {
                                PageHeader("Singleplayer") { page = MenuPage.HOME }
                                variantRow()
                                timelinesRow()
                                SettingRow("Opponent", if (againstAi) "A language model, choosing among legal turns." else "The built-in computer.") {
                                    SecondaryPill(onClick = { showOpponents = true }) {
                                        Text(if (againstAi) aiName.orEmpty() else "Computer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                infernoRow("")
                                Spacer(Modifier.height(2.dp))
                                PrimaryPill(
                                    onClick = { onStart(GameMode(computerSide = Side.BLACK, variant = variant, timelineLimit = timelineLimit, aiOpponent = againstAi, inferno = inferno)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Play as White") }
                                // One gold call to action; the other side is the quieter choice.
                                SecondaryPill(
                                    onClick = { onStart(GameMode(computerSide = Side.WHITE, variant = variant, timelineLimit = timelineLimit, aiOpponent = againstAi, inferno = inferno)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Play as Black") }
                            }
                            MenuPage.MULTI -> {
                                PageHeader("Multiplayer") { page = MenuPage.HOME }
                                variantRow()
                                timelinesRow()
                                infernoRow("")
                                Spacer(Modifier.height(2.dp))
                                PrimaryPill(
                                    onClick = { onStart(GameMode(variant = variant, timelineLimit = timelineLimit, inferno = inferno)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Pass and play") }
                                if (canPlayOnline) {
                                    // One per row: side by side, the labels don't fit on narrow phones.
                                    SecondaryPill(onClick = { showInvite = true }, modifier = Modifier.fillMaxWidth()) { Text("Invite a friend") }
                                    SecondaryPill(onClick = { showJoin = true }, modifier = Modifier.fillMaxWidth()) { Text("Join a friend") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRules) RulesDialog(onDismiss = { showRules = false })
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            confirmButton = {
                DestructiveTextButton("Discard") {
                    confirmDiscard = false
                    onDiscard()
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") } },
            title = { Text("Discard this game?") },
            text = { Text("It can't be continued afterwards.") },
        )
    }
    if (confirmDiscardOnline && resumableOnline != null) {
        val friend = resumableOnline.opponentName
        AlertDialog(
            onDismissRequest = { confirmDiscardOnline = false },
            confirmButton = {
                DestructiveTextButton("Forget it") {
                    confirmDiscardOnline = false
                    onDiscardOnline()
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscardOnline = false }) { Text("Keep it") } },
            title = { Text("Forget this online game?") },
            text = {
                Text(
                    if (friend == null) {
                        "Nobody has joined it yet. You won't be able to come back to it, so don't send its link."
                    } else {
                        "You won't be able to come back to it, and $friend won't be told. To end it properly, " +
                            "continue the game and resign from ⋮ instead."
                    },
                )
            },
        )
    }
    if (showOpponents) {
        OpponentDialog(
            aiName = aiName,
            againstAi = againstAi,
            onChoose = { ai ->
                preferAi = ai
                showOpponents = false
            },
            onSetUp = {
                showOpponents = false
                onAiSettings()
            },
            onDismiss = { showOpponents = false },
        )
    }
    if (showInvite) {
        InviteDialog(
            variant = variant,
            timelineLimit = timelineLimit,
            inferno = inferno,
            playerName = playerName,
            onInvite = { side, name ->
                showInvite = false
                onInvite(variant, timelineLimit, side, name, inferno)
            },
            onDismiss = { showInvite = false },
        )
    }
    if (showJoin) {
        JoinDialog(
            onJoin = { code ->
                showJoin = false
                onJoin(code)
            },
            onDismiss = { showJoin = false },
        )
    }
    if (showLimits) {
        LimitDialog(
            selected = timelineLimit,
            onSelect = {
                timelineLimit = it
                showLimits = false
            },
            onDismiss = { showLimits = false },
        )
    }
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

/** A game on offer to continue, with a button to throw it away. */
@Composable
private fun ResumeRow(title: String, summary: String, discardLabel: String, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryPill(onClick = onContinue, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.onAccent.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Filled.Close, discardLabel, onDiscard)
    }
}

/** A way into one of the menu's pages: its name, what's in it, and an arrow. */
@Composable
private fun MenuChoice(title: String, description: String, primary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (primary) Palette.onAccent.copy(alpha = 0.72f) else Palette.inkSoft,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = if (primary) Palette.onAccent else Palette.accent)
    }
    if (primary) {
        PrimaryPill(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = content)
    } else {
        SecondaryPill(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** A divider of gold, as in an old book: two fading lines and a diamond between them. */
@Composable
private fun Ornament(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 160.dp, height = 12.dp)) {
        val gold = Color(0xFFE8C77A).copy(alpha = 0.85f)
        val mid = center
        val half = 4.dp.toPx()
        val gap = 10.dp.toPx()
        val line = 64.dp.toPx()
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, gold), mid.x - gap - line, mid.x - gap), Offset(mid.x - gap - line, mid.y), Offset(mid.x - gap, mid.y), 1.dp.toPx())
        drawLine(Brush.horizontalGradient(listOf(gold, Color.Transparent), mid.x + gap, mid.x + gap + line), Offset(mid.x + gap, mid.y), Offset(mid.x + gap + line, mid.y), 1.dp.toPx())
        val diamond = Path().apply {
            moveTo(mid.x, mid.y - half)
            lineTo(mid.x + half, mid.y)
            lineTo(mid.x, mid.y + half)
            lineTo(mid.x - half, mid.y)
            close()
        }
        drawPath(diamond, gold, style = Stroke(1.5.dp.toPx()))
    }
}

/** A page's title, with the way back to the menu's first page. */
@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * A setting: its name and what it does, with [control] to change it at the end. The control is
 * capped in width, so a long choice is shortened rather than squeezing the description away.
 */
@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.widthIn(max = 156.dp)) { control() }
    }
}

/** A setting that's on or off: the whole row is one switch, named by its title for screen readers. */
@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun InviteDialog(
    variant: Variant,
    timelineLimit: Int?,
    inferno: Boolean,
    playerName: String,
    onInvite: (Side, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(playerName) }
    val valid = Ticket.parseName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Invite a friend") },
        text = {
            // Scrolls, so the choice of side stays in reach with the keyboard up or in landscape.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "You'll get a link to send to your friend, to play on their own phone. " +
                        "${variant.displayName}, ${limitLabel(timelineLimit).lowercase()}${if (inferno) ", with the Inferno" else ""}, as chosen under Multiplayer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                NameField(name, onChange = { name = it })
                PrimaryPill(onClick = { valid?.let { onInvite(Side.WHITE, it) } }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) {
                    Text("I play White")
                }
                SecondaryPill(onClick = { valid?.let { onInvite(Side.BLACK, it) } }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) {
                    Text("I play Black")
                }
            }
        },
    )
}

@Composable
private fun JoinDialog(onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val code = Ticket.parseCode(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { code?.let(onJoin) }, enabled = code != null) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Join a friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Type the code from your friend's invitation, or paste the link.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("ABCDE-FGHJK") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { code?.let(onJoin) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** Playing the built-in computer, or the language model set up in AI settings ([aiName], if any). */
@Composable
private fun OpponentDialog(aiName: String?, againstAi: Boolean, onChoose: (Boolean) -> Unit, onSetUp: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Opponent") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val options = listOfNotNull(
                    Triple(false, "Computer", "The built-in engine: looks two turns ahead, plays solidly."),
                    aiName?.let { Triple(true, it, "A language model, set up in AI opponent. Chooses among the engine's legal turns, and talks.") },
                )
                for ((ai, label, detail) in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(ai == againstAi, role = Role.RadioButton, onClick = { onChoose(ai) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ai == againstAi, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
                        }
                    }
                }
                TextButton(onClick = onSetUp) { Text(if (aiName == null) "Set up an AI opponent…" else "AI opponent settings…") }
            }
        },
    )
}

/** E.g. "vs computer · Standard · turn 12 · 3 timelines". */
private fun resumeSummary(game: GameSession): String {
    val parts = mutableListOf(
        when (game.mode.kind) {
            GameKind.AI -> "vs AI"
            GameKind.COMPUTER -> "vs computer"
            GameKind.ONLINE -> "Online"
            GameKind.PUZZLE -> "Puzzle"
            GameKind.PASS_AND_PLAY -> "Pass and play"
        },
    )
    parts += game.mode.variant.displayName
    if (game.mode.inferno) parts += "Inferno"
    parts += "turn ${Notation.turnNumber(game.state.present)}"
    if (game.state.timelines.size > 1) parts += "${game.state.timelines.size} timelines"
    return parts.joinToString(" · ")
}

private val LIMITS: List<Int?> = (1..8).toList() + null

private fun limitLabel(limit: Int?) = if (limit == null) "Unlimited" else "Up to $limit each"

@Composable
private fun LimitDialog(selected: Int?, onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Timelines") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Once a player has created this many timelines, they can't branch any more. A " +
                        "limit keeps the multiverse small enough for the game to reliably tell whether " +
                        "a position is checkmate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.inkSoft,
                )
                Spacer(Modifier.height(8.dp))
                for (option in LIMITS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(limitLabel(option), style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    option == null -> "Branch as often as you like. In huge games the game may not recognise checkmate."
                                    option == GameSession.DEFAULT_TIMELINE_LIMIT -> "Recommended. Checkmate is reliably recognised."
                                    option < GameSession.DEFAULT_TIMELINE_LIMIT -> "Smaller, quicker games."
                                    else -> "Bigger games; checkmate is nearly always recognised."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.inkSoft,
                            )
                        }
                    }
                }
            }
        },
    )
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
                            .heightIn(min = 48.dp)
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
