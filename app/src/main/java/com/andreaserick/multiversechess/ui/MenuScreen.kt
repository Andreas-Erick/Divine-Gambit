package com.andreaserick.multiversechess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Variant
import com.andreaserick.multiversechess.online.Ticket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

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
    onInvite: (Variant, Int?, Side, String) -> Unit,
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

    BackHandler(enabled = page != MenuPage.HOME) { page = MenuPage.HOME }

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
            Text("Divine", style = MaterialTheme.typography.displayMedium, color = Palette.ink)
            Text(
                "G A M B I T",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.accent,
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
                // Pages slide in from the right going in, and back out to the right going home.
                AnimatedContent(
                    page,
                    transitionSpec = {
                        val direction = if (targetState != MenuPage.HOME) 1 else -1
                        (slideInHorizontally { it / 4 * direction } + fadeIn())
                            .togetherWith(slideOutHorizontally { -it / 4 * direction } + fadeOut())
                            .using(SizeTransform(clip = false))
                    },
                    label = "menu page",
                ) { shown ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        @Composable
                        fun variantRow() = SettingRow("Variant", variant.description) {
                            SecondaryPill(onClick = { showVariants = true }) { Text(variant.displayName) }
                        }

                        @Composable
                        fun timelinesRow() = SettingRow("Timelines", "How many each player may create.") {
                            SecondaryPill(onClick = { showLimits = true }) { Text(limitLabel(timelineLimit)) }
                        }

                        @Composable
                        fun infernoRow(note: String) = SettingRow("Inferno", "Taken pieces go to the Inferno. Solve puzzles there, while the game goes on, to win them back.$note") {
                            Switch(checked = inferno, onCheckedChange = { inferno = it })
                        }

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
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Palette.presentEdge.copy(alpha = 0.6f))
                                }
                                MenuChoice("Singleplayer", "Against the computer or an AI.") { page = MenuPage.SINGLE }
                                MenuChoice(
                                    "Multiplayer",
                                    if (canPlayOnline) "Pass and play on this phone, or a friend online." else "Pass and play on this phone.",
                                ) { page = MenuPage.MULTI }
                                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Palette.presentEdge.copy(alpha = 0.6f))
                                SecondaryPill(onClick = onPuzzles, modifier = Modifier.fillMaxWidth()) {
                                    Text("Puzzles")
                                    Spacer(Modifier.weight(1f))
                                    Text(puzzleProgress, color = Palette.inkSoft)
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
                                    SecondaryPill(onClick = { showOpponents = true }, modifier = Modifier.widthIn(max = 170.dp)) {
                                        Text(if (againstAi) aiName.orEmpty() else "Computer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                infernoRow("")
                                Spacer(Modifier.height(2.dp))
                                PrimaryPill(
                                    onClick = { onStart(GameMode(computerSide = Side.BLACK, variant = variant, timelineLimit = timelineLimit, aiOpponent = againstAi, inferno = inferno)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Play as White") }
                                PrimaryPill(
                                    onClick = { onStart(GameMode(computerSide = Side.WHITE, variant = variant, timelineLimit = timelineLimit, aiOpponent = againstAi, inferno = inferno)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Play as Black") }
                            }
                            MenuPage.MULTI -> {
                                PageHeader("Multiplayer") { page = MenuPage.HOME }
                                variantRow()
                                timelinesRow()
                                infernoRow(if (canPlayOnline) " On this phone only, not online." else "")
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
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text("Discard") }
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
                TextButton(onClick = {
                    confirmDiscardOnline = false
                    onDiscardOnline()
                }) { Text("Forget it") }
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
            playerName = playerName,
            onInvite = { side, name ->
                showInvite = false
                onInvite(variant, timelineLimit, side, name)
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
                Text(title)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.onAccent.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Filled.Close, discardLabel, onDiscard)
    }
}

/** A way into one of the menu's pages: its name, what's in it, and an arrow. */
@Composable
private fun MenuChoice(title: String, description: String, onClick: () -> Unit) {
    SecondaryPill(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.inkSoft)
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

/** A setting: its name and what it does, with [control] to change it at the end. */
@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/**
 * A slowly turning checkerboard bent into a logarithmic spiral, receding toward a glowing
 * vanishing point among the stars: the board falling through time.
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
    val night = Palette.background
    val band = Color(0xFF1D2250)
    val glow = Palette.accent
    val path = remember { Path() }
    // Stars, as fractions of the screen: the same sky every time.
    val stars = remember {
        val random = Random(5)
        List(90) { Triple(Offset(random.nextFloat(), random.nextFloat()), 0.6f + random.nextFloat() * 1.4f, 0.25f + random.nextFloat() * 0.6f) }
    }
    Canvas(modifier) {
        drawRect(night)
        for ((at, radius, alpha) in stars) {
            drawCircle(Color.White.copy(alpha = alpha), radius.dp.toPx(), Offset(at.x * size.width, at.y * size.height))
        }
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
                drawPath(path, band, alpha = 0.85f)
            }
            r = next
            ring++
        }
        // A glow at the vanishing point, and night toward the bottom so the menu stays readable.
        drawRect(
            Brush.radialGradient(
                listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0.12f), glow.copy(alpha = 0f)),
                center = center,
                radius = size.minDimension * 0.3f,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.4f to Palette.background.copy(alpha = 0.3f),
                1f to Palette.background.copy(alpha = 0.95f),
            ),
        )
    }
}

@Composable
private fun InviteDialog(variant: Variant, timelineLimit: Int?, playerName: String, onInvite: (Side, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(playerName) }
    val valid = Ticket.parseName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Invite a friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "You'll get a link to send to your friend, to play on their own phone. " +
                        "${variant.displayName}, ${limitLabel(timelineLimit).lowercase()}, as chosen under Multiplayer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                NameField(name, onChange = { name = it })
                PrimaryPill(onClick = { valid?.let { onInvite(Side.WHITE, it) } }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) {
                    Text("I play White")
                }
                PrimaryPill(onClick = { valid?.let { onInvite(Side.BLACK, it) } }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) {
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
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
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
            Column {
                val options = listOfNotNull(
                    Triple(false, "Computer", "The built-in engine: looks two turns ahead, plays solidly."),
                    aiName?.let { Triple(true, it, "A language model, set up in AI opponent. Chooses among the engine's legal turns, and talks.") },
                )
                for ((ai, label, detail) in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(ai == againstAi, role = Role.RadioButton, onClick = { onChoose(ai) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ai == againstAi, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
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
        when {
            game.mode.computerSide == null -> "Pass and play"
            game.mode.aiOpponent -> "vs AI"
            else -> "vs computer"
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
                        Text(heading, style = MaterialTheme.typography.titleSmall, color = Palette.accent)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Reading the boards", style = MaterialTheme.typography.titleSmall, color = Palette.accent)
                BoardLegend()
                Text("Buttons", style = MaterialTheme.typography.titleSmall, color = Palette.accent)
                for (button in BUTTONS) ButtonHelpRow(button)
            }
        },
    )
}

/** One button explained in How to play: an [icon], or a pill reading [pill]. */
private class ButtonHelp(val name: String, val text: String, val icon: ImageVector? = null, val pill: String? = null)

private val BUTTONS = listOf(
    ButtonHelp(
        "Back",
        "Leave the game; it's saved, and Continue game in the menu picks it up again. The × beside Continue game throws it away.",
        icon = Icons.AutoMirrored.Filled.ArrowBack,
    ),
    ButtonHelp("How to play", "Opens these rules.", icon = Icons.Filled.Info),
    ButtonHelp(
        "More",
        "In a game: the move list, No legal turn? (searches every turn when you think you're stuck) and " +
            "Resign. In a puzzle: a hint, restarting it, and showing the solution.",
        icon = Icons.Filled.MoreVert,
    ),
    ButtonHelp("Restart puzzle", "Puts the puzzle back to its start. Undo takes you back to where you were.", icon = Icons.Filled.Refresh),
    ButtonHelp("Overview", "Zooms out to show the whole multiverse.", icon = OverviewIcon),
    ButtonHelp("History view", "Shows only the selected piece's timeline through time, without your opponent's boards.", icon = HistoryIcon),
    ButtonHelp("Parallel view", "Shows only the selected piece's moment, across every timeline.", icon = ParallelIcon),
    ButtonHelp("Undo", "Takes back your last move. Against the computer, it also takes back its reply.", pill = "Undo"),
    ButtonHelp("Present", "Brings you back to the boards you have to play.", pill = "Present"),
    ButtonHelp("Submit", "Ends your turn once every board in the present has been played.", pill = "Submit"),
)

@Composable
private fun ButtonHelpRow(button: ButtonHelp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            if (button.icon != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Palette.card, CircleShape)
                        .border(1.dp, Palette.presentEdge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(button.icon, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(20.dp))
                }
            } else {
                Text(
                    button.pill.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .border(1.dp, Palette.ink.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(button.name, style = MaterialTheme.typography.labelLarge)
            Text(button.text, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
    }
}

private val RULES = listOf(
    "The multiverse" to
        "Each row is a timeline (teal ribbon) and each column a half-move, advancing to the right. " +
        "Drag to pan and pinch to zoom.",
    "Finding your way" to
        "Tap a timeline's ribbon, or its label on the left edge, to see the whole timeline. Tap a " +
        "board that's too small to play on to zoom in on it. The corners button shows the whole " +
        "multiverse, and Present brings you back to the boards you have to play. History view " +
        "keeps only the selected piece's timeline, through time; Parallel view only its moment, " +
        "across every timeline. Both hide your opponent's boards.",
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
    "Timeline limit" to
        "Unless you choose Unlimited when starting a game, each player may only create a few " +
        "timelines. After that they can't branch any more: moves into the past are no longer " +
        "possible, so they can't attack a king's past either.",
    "Active timelines" to
        "Each player may have only one more active timeline than the opponent. Extra timelines are " +
        "faded and don't hold back the present. A teal arrow at the end of the present means " +
        "your next new timeline would be active, so branching into the past would pull the " +
        "present back with it.",
    "Check and mate" to
        "You can't submit a turn that lets the opponent capture any of your kings (or other royal " +
        "pieces), now or in the past. A red ! marks a timeline whose advancement would let a king be captured. With no " +
        "way to move the present without leaving a king attacked you are checkmated, or " +
        "stalemated if no king is threatened. After the game you can still try the loser's " +
        "moves to see why.",
    "AI opponent" to
        "In AI opponent (below the menu) you can connect a language model: Gemini with your API key, " +
        "a model on your computer (LM Studio or Ollama), or one downloaded to this phone. Choose it " +
        "as your Opponent under Singleplayer to play it: it picks one of the legal turns the engine finds and says " +
        "something about it. Under Puzzles it can also design puzzles; the engine checks each one " +
        "really is mate in one turn, and makes one itself if the AI's don't work out.",
    "Playing a friend" to
        "Invite a friend gives you a link to send. When your friend opens it on their phone and " +
        "gives their name, the game begins; without the app, the link shows a code they can type " +
        "under Join a friend. The names you both give are shown to each other in that game. " +
        "Undo only takes back moves you haven't submitted. If either of you leaves, the game " +
        "waits: Continue game with a friend picks it up again.",
    "The Inferno" to
        "If you start a game with the Inferno switched on, a piece that's taken goes to the Inferno. " +
        "Tap Descend to go after it: there you solve trials, each a puzzle to checkmate in one turn, " +
        "while the game goes on. A pawn needs one trial, a knight or bishop two, a rook three and a " +
        "queen four. A piece that has passed its trials returns at the start of your turn, on a square " +
        "where it can't be taken straight away. While it's your turn on the board you have 90 seconds " +
        "there per turn; while your opponent thinks, the clock stops. If the game ends while you're " +
        "in the Inferno, you'll be told.",
    "Fairy pieces" to
        "Some variants add pieces drawn as lettered discs. S, princess: a rook or bishop move. " +
        "U, unicorn: equal distance along exactly three dimensions. D, dragon: equal distance " +
        "along all four. W, brawn: a pawn that can capture one step along any diagonal that " +
        "includes a forward step (rank or timeline) and no backward one. Y, royal queen: moves " +
        "like a queen but is royal, so it must be protected like a king. C, common king: moves " +
        "like a king but isn't royal, so it may be attacked or lost.",
)
