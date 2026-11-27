package com.andreaserick.multiversechess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.GameKind
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Puzzle
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.WaitingSoul
import com.andreaserick.multiversechess.engine.winnerOrNull
import com.andreaserick.multiversechess.online.OnlineStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GameScreen(
    session: GameSession,
    thinking: Boolean,
    online: OnlineStatus?,
    /** The language model playing the computer's side, if it's one. */
    aiName: String?,
    /** What it said with its last turn. */
    aiRemark: String?,
    nextPuzzle: Puzzle?,
    onTap: (Pos) -> Unit,
    onSubmit: () -> Unit,
    onUndo: () -> Unit,
    onResign: () -> Unit,
    onShowSolution: () -> Unit,
    onRestartPuzzle: () -> Unit,
    onClaimNoLegalTurn: () -> Unit,
    onStartPuzzle: (Puzzle) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onDeselect: () -> Unit = {},
    /** With the Inferno rule: the player's pieces waiting there. */
    infernoSouls: List<WaitingSoul> = emptyList(),
    /** Why the Inferno can't be entered now, or null if it can. */
    hellClosed: String? = null,
    /** News from the Inferno, such as a piece returning. */
    infernoNotice: String? = null,
    /**
     * Descends, with the trials counting for the soul at the index given (the game's choice if
     * null), the fire breaking through from the point given (the Descend button if null).
     */
    onEnterInferno: (Int?, Offset?) -> Unit = { _, _ -> },
    /** Where the way into the Inferno is, for the portal to open from. */
    onInfernoPlaced: (Offset) -> Unit = {},
    /** Another screen (the Inferno) is in front: this one keeps still, with no dialogs and no Back. */
    covered: Boolean = false,
    /** A newcomer's first turns: a line saying what to do next. */
    tips: Boolean = false,
    onHideTips: () -> Unit = {},
    onShowTipsAgain: (() -> Unit)? = null,
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
    // A result set aside comes back if the game goes on and ends the same way again.
    LaunchedEffect(session.outcome) { if (session.outcome == Outcome.Ongoing) dismissedOutcome = null }
    var slice by remember { mutableStateOf<SliceView?>(null) }
    var choosingSoul by remember { mutableStateOf(false) }
    var soulChosen by remember { mutableStateOf(false) }
    val puzzle = session.mode.puzzle
    val state = session.state
    val ongoing = session.outcome == Outcome.Ongoing
    // Online, this player's submitted turn is still on its way.
    val sending = online?.sending == true

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
    val roomySquare = with(LocalDensity.current) { ROOMY_SQUARE.toPx() }
    suspend fun focusPresent(resetZoom: Boolean = false) {
        val (l, t) = state.focusBoard()
        camera.focus(state.focusArea(), resetZoom, comfortableSquare, fallback = Layout.boardRect(l, t), maxSquare = roomySquare)
    }
    LaunchedEffect(state.sideToMove, turnStart, camera.viewSize) {
        // A game continued in the middle of a turn still needs placing once.
        if ((turnStart != null || !camera.initialized) && slice == null) focusPresent()
    }

    // A slice view frames what it keeps, and follows the selected piece to another timeline (for
    // History, which also lays out each side's boards differently) or another moment (for Parallel).
    val anchor = session.sliceAnchor()
    val sliceKey = if (slice == SliceView.HISTORY) anchor.l to Math.floorMod(anchor.t, 2) else anchor.t
    LaunchedEffect(slice, sliceKey, camera.viewSize) {
        slice?.let { camera.frame(state.sliceArea(it, anchor), TIMELINE_MIN_BOARD) }
    }

    BackHandler(enabled = slice != null && !covered) { slice = null }

    val canEndTurn = !thinking && !sending && !session.isOpponentsTurn &&
        ((ongoing && state.canSubmit) || (session.isExploring && state.turnMoves.isNotEmpty()))
    fun showPresent() {
        slice = null
        scope.launch { focusPresent(resetZoom = true) }
    }

    // With a keyboard (a tablet in its keyboard cover, say), the screen takes the keys: it holds
    // focus whenever it's in front, so Escape and the shortcuts work without tabbing to a button.
    val keys = remember { FocusRequester() }
    val keyPan = with(LocalDensity.current) { 64.dp.toPx() }
    val dialogOpen = showLog || showRules || showMenu || confirmResign || showHint || confirmSolution || choosingSoul
    LaunchedEffect(covered, dialogOpen) {
        if (!covered && !dialogOpen) runCatching { keys.requestFocus() }
    }

    var topBarHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    when {
                        slice != null -> {
                            slice = null
                            true
                        }
                        session.selected != null -> {
                            onDeselect()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            // After a focused button has had its turn: Enter ends the turn, Ctrl+Z takes back a
            // move, P returns to the present.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || covered) return@onKeyEvent false
                when {
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) && canEndTurn -> {
                        onSubmit()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Z && session.previous != null && !sending -> {
                        onUndo()
                        true
                    }
                    event.key == Key.P && !event.isCtrlPressed -> {
                        showPresent()
                        true
                    }
                    // Arrows pan the multiverse, and + and - zoom it about the middle of the view.
                    event.key in KEY_PANS -> {
                        val (dx, dy) = KEY_PANS.getValue(event.key)
                        camera.transform(Offset.Zero, Offset(dx, dy) * keyPan, 1f)
                        true
                    }
                    event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd ||
                        event.key == Key.Minus || event.key == Key.NumPadSubtract -> {
                        val zoomIn = event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd
                        val middle = Offset(camera.viewSize.width / 2f, camera.viewSize.height / 2f)
                        camera.transform(middle, Offset.Zero, if (zoomIn) WHEEL_ZOOM_STEP else 1f / WHEEL_ZOOM_STEP)
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable(),
    ) {
        // At most a little over half the screen, and on a big tablet no taller than it needs to be
        // to keep the multiverse in view (the news scrolls).
        val cardMaxHeight = if (maxWidth >= 600.dp) minOf(maxHeight * 0.55f, 400.dp) else maxHeight * 0.55f
        // On a wide screen held sideways, the card sits in the bottom corner, under the right
        // thumb, instead of over the middle of the board.
        val docked = maxWidth >= 840.dp && maxWidth > maxHeight
        // A little room between the card and what the camera frames above it.
        val coveredGap = with(LocalDensity.current) { 16.dp.toPx() }
        // What the card covers, for the camera: the whole bottom, or when docked only its corner
        // (the card and the 12 dp around it), so boards can use the full height beside it.
        val cardMargin = with(LocalDensity.current) { 24.dp.toPx() }
        var bottomBand by remember { mutableIntStateOf(0) }
        var cardWidth by remember { mutableIntStateOf(0) }
        // Told as the card is measured, so the camera knows before it first frames the boards,
        // and again when the screen turns (docking can change without the card's height changing).
        fun reportCovered() {
            camera.coveredBottom = if (docked) 0f else bottomBand + coveredGap
            camera.coveredCornerHeight = if (docked) bottomBand + coveredGap else 0f
            camera.coveredCornerWidth = if (docked) cardWidth + cardMargin + coveredGap else 0f
        }
        LaunchedEffect(docked) { reportCovered() }
        MultiverseView(session, camera, onTap, Modifier.fillMaxSize(), slice)

        // Top: back, title and tools, floating over the multiverse on a band of the background that
        // fades out, so boards passing underneath don't run through the title.
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    camera.coveredTop = it.height.toFloat()
                    topBarHeight = it.height
                }
                .background(
                    Brush.verticalGradient(
                        0f to Palette.background.copy(alpha = 0.97f),
                        0.7f to Palette.background.copy(alpha = 0.88f),
                        1f to Palette.background.copy(alpha = 0f),
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                Icons.AutoMirrored.Filled.ArrowBack,
                when {
                    puzzle != null -> "Back to the puzzles"
                    session.isResumable || session.mode.kind == GameKind.ONLINE -> "Back to the menu (your game is saved)"
                    else -> "Back to the menu"
                },
                onExit,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    puzzle?.title ?: session.mode.variant.displayName.let { if (it == "Standard") "Divine Gambit" else it },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (session.mode.kind) {
                        GameKind.PUZZLE -> "Puzzle · checkmate in one turn"
                        GameKind.ONLINE -> online?.let { "Online · ${it.nameOf(it.ticket.side)} vs ${it.opponentName}" } ?: "Online"
                        GameKind.COMPUTER, GameKind.AI -> "vs ${aiName ?: "computer"}"
                        GameKind.PASS_AND_PLAY -> "Pass and play"
                    } + if (session.mode.inferno) " · Inferno" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (puzzle != null) {
                RoundIconButton(Icons.Filled.Refresh, "Restart puzzle", onRestartPuzzle, enabled = !thinking)
                Spacer(Modifier.width(8.dp))
            }
            RoundIconButton(Icons.Filled.Info, "How to play", { showRules = true })
            Spacer(Modifier.width(8.dp))
            Box {
                RoundIconButton(Icons.Filled.MoreVert, "More", { showMenu = true })
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (puzzle != null) {
                        DropdownMenuItem(text = { Text("Hint") }, onClick = { showMenu = false; showHint = true })
                        DropdownMenuItem(
                            text = { Text("Restart puzzle") },
                            enabled = !thinking,
                            onClick = { showMenu = false; onRestartPuzzle() },
                        )
                        DropdownMenuItem(
                            text = { Text("Show solution") },
                            enabled = ongoing && !thinking,
                            onClick = { showMenu = false; confirmSolution = true },
                        )
                    } else {
                        DropdownMenuItem(text = { Text("Move list") }, onClick = { showMenu = false; showLog = true })
                        DropdownMenuItem(
                            text = { Text("Stuck? Check for a legal turn") },
                            enabled = session.isPlayersTurn && !thinking && !sending,
                            onClick = { showMenu = false; onClaimNoLegalTurn() },
                        )
                        DropdownMenuItem(
                            text = { Text("Resign") },
                            enabled = ongoing && !sending,
                            onClick = { showMenu = false; confirmResign = true },
                        )
                    }
                }
            }
        }

        // Below the tools: zoom out to the whole multiverse, or keep only the selected piece's
        // timeline (History) or moment (Parallel).
        // Placed under the top bar as it is measured, so a title in large text never runs into it.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(end = 12.dp)
                .offset { IntOffset(0, (topBarHeight - 28.dp.roundToPx() + 12.dp.roundToPx()).coerceAtLeast(0)) },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIconButton(OverviewIcon, "Show the whole multiverse", {
                slice = null
                scope.launch { camera.frame(state.overviewArea()) }
            })
            for ((view, icon, label) in listOf(
                Triple(SliceView.HISTORY, HistoryIcon, "History view: this timeline through time"),
                Triple(SliceView.PARALLEL, ParallelIcon, "Parallel view: this moment across timelines"),
            )) {
                RoundIconButton(icon, label, {
                    // Leaving History view spreads the boards out again: stay on the same board.
                    if (slice == SliceView.HISTORY && view == SliceView.HISTORY) {
                        scope.launch { camera.focus(Layout.boardRect(anchor.l, anchor.t)) }
                    }
                    slice = if (slice == view) null else view
                }, selected = slice == view)
            }
        }

        // Bottom: status and turn controls.
        Box(
            Modifier
                .align(if (docked) Alignment.BottomEnd else Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged {
                    bottomBand = it.height
                    reportCovered()
                }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(12.dp),
            contentAlignment = if (docked) Alignment.BottomEnd else Alignment.BottomCenter,
        ) {
            // The news scrolls, and the turn's buttons stay in reach.
            SlabCard(Modifier.widthIn(max = if (docked) 460.dp else 560.dp).fillMaxWidth().onSizeChanged {
                cardWidth = it.width
                reportCovered()
            }.heightIn(max = cardMaxHeight)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusLine(session, thinking || sending, online, aiName)
                        if (tips && !thinking && !sending) tip(session)?.let { TipLine(it, onHideTips) }
                        online?.let { OnlineNote(it, session) }
                        LingeringText(infernoNotice) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Palette.gold)
                        }
                        if (session.mode.inferno && ongoing) {
                            InfernoBar(infernoSouls, hellClosed, onInfernoPlaced) {
                                // With more than one piece to seek, the player chooses first.
                                if (infernoSouls.count { !it.free } > 1) choosingSoul = true else onEnterInferno(null, null)
                            }
                        }
                        LingeringText(aiRemark) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = Palette.ink)
                        }
                        LingeringText(session.message) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryPill(onClick = onUndo, enabled = session.previous != null && !sending) { Text("Undo") }
                        SecondaryPill(onClick = ::showPresent) { Text("Present") }
                        PrimaryPill(
                            onClick = onSubmit,
                            // After the game, End turn explains why the moves tried can't be played.
                            enabled = canEndTurn,
                            modifier = Modifier.weight(1f),
                        ) { Text("End turn", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }

        if (choosingSoul) {
            SoulScroll(
                infernoSouls,
                onChoose = { soul, from ->
                    // The scroll stays for the descent, so the fire burns through it, and goes once
                    // the Inferno covers it.
                    soulChosen = true
                    onEnterInferno(soul, from)
                    scope.launch {
                        delay(SCROLL_BURNS_MILLIS)
                        choosingSoul = false
                        soulChosen = false
                    }
                },
                onDismiss = { choosingSoul = false },
                active = !soulChosen,
            )
        }
    }

    if (showRules) RulesDialog(onDismiss = { showRules = false }, onShowTipsAgain = onShowTipsAgain?.takeIf { !tips })

    if (showHint && puzzle != null) {
        AlertDialog(
            onDismissRequest = { showHint = false },
            confirmButton = { TextButton(onClick = { showHint = false }) { Text("Got it") } },
            title = { Text("Hint") },
            text = { Text(puzzle.hint, Modifier.verticalScroll(rememberScrollState())) },
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
                    // Opens on the latest moves, which are the ones wanted in a long game.
                    LazyColumn(
                        state = rememberLazyListState(initialFirstVisibleItemIndex = session.log.lastIndex.coerceAtLeast(0)),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(session.log) { i, line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR_NUMS),
                                    color = Palette.inkSoft,
                                    modifier = Modifier.width(28.dp),
                                )
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
                DestructiveTextButton("Resign") {
                    confirmResign = false
                    onResign()
                }
            },
            dismissButton = { TextButton(onClick = { confirmResign = false }) { Text("Keep playing") } },
            title = { Text("Resign this game?") },
        )
    }

    val outcome = session.outcome
    if (outcome != Outcome.Ongoing && outcome != dismissedOutcome && !covered) {
        val solved = puzzle != null
        // A win for someone at this device: the puzzle solved, the player's side, or in pass and play either side.
        val won = solved || outcome.winnerOrNull.let { it != null && (session.mode.localSide == null || it == session.mode.localSide) }
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
                Box(Modifier.size(52.dp).background(if (won) Palette.gold else Palette.inkSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        if (won) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = Palette.onGold,
                        modifier = Modifier.size(30.dp),
                    )
                }
            },
            title = { Text(if (solved) "Puzzle solved" else outcomeTitle(outcome)) },
            text = {
                Text(
                    when {
                        !solved -> outcomeText(outcome, online.names())
                        session.solutionShown -> "That's the checkmate. Try the next one on your own."
                        else -> "Checkmate. Every reply leaves a black king exposed somewhere in the multiverse."
                    },
                )
            },
        )
    }
}

@Composable
private fun StatusLine(session: GameSession, thinking: Boolean, online: OnlineStatus?, aiName: String?) {
    val state = session.state
    val side = state.sideToMove
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (thinking) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
        } else {
            SideDisc(side)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(headline(session, thinking, online, aiName), style = MaterialTheme.typography.titleMedium)
            Text(detail(session, online.names()), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_NUMS), color = Palette.inkSoft)
        }
        if (session.outcome == Outcome.Ongoing && state.threats().isNotEmpty()) {
            Text(
                "CHECK",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Palette.onThreat,
                modifier = Modifier
                    .background(Palette.threat, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * What a newcomer should do next on this turn, or null when there's nothing for them to do. The
 * first turns teach tapping and ending a turn; once a few turns are in, branching through time.
 */
private fun tip(session: GameSession): String? {
    val state = session.state
    if (!session.isPlayersTurn || session.isExploring) return null
    val boards = state.mandatoryTimelines.size
    return when {
        session.selected != null && session.targets.isEmpty() ->
            "This piece has nowhere to go. Tap another of your pieces."
        session.selected != null ->
            "Tap a marked square to move there, even on another board. Tap the piece again to put it down."
        state.canSubmit && state.turnMoves.isNotEmpty() ->
            "Done here. Tap End turn to let the other side play."
        state.turnMoves.isNotEmpty() && boards == 0 -> null
        state.turnMoves.isNotEmpty() ->
            if (boards == 1) "One more board with a gold ring needs a move." else "$boards more boards with a gold ring need a move."
        Notation.turnNumber(state.present) >= 3 && state.timelines.size == 1 ->
            "Pieces can also move back to an earlier board. That starts a new timeline, a row of its own."
        state.timelines.size > 1 ->
            "Each row is a timeline. Move on every board with a gold ring, then End turn."
        else -> "Tap one of your pieces to see where it can go."
    }
}

/** A newcomer's tip, with a way to stop seeing them. */
@Composable
private fun TipLine(text: String, onHide: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.ink, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        TextButton(onClick = onHide) { Text("Hide tips") }
    }
}

/** Which way each arrow key moves the view's contents: the multiverse slides opposite the arrow. */
private val KEY_PANS = mapOf(
    Key.DirectionLeft to (1f to 0f),
    Key.DirectionRight to (-1f to 0f),
    Key.DirectionUp to (0f to 1f),
    Key.DirectionDown to (0f to -1f),
)

/** How long the scroll stays after a choice: as long as the descent through it takes. */
private const val SCROLL_BURNS_MILLIS = 1_600L

/** The way into the Inferno, with the pieces waiting there and their trials. */
@Composable
private fun InfernoBar(souls: List<WaitingSoul>, closed: String?, onPlaced: (Offset) -> Unit, onEnter: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(FlameIcon, contentDescription = null, tint = Palette.flame, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (souls.isEmpty()) "Inferno" else "Inferno · " + souls.joinToString("  ") { "${pieceSymbol(it.soul.type)} ${it.solved}/${it.soul.trials}" },
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR_NUMS),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    souls.isEmpty() -> "Taken pieces go here. Solve trials to win them back."
                    closed != null -> closed
                    else -> "Solve trials to win your pieces back."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.inkSoft,
            )
        }
        Spacer(Modifier.width(10.dp))
        PrimaryPill(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onEnter()
            },
            enabled = closed == null,
            modifier = Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot().center) },
        ) { Text("Descend") }
    }
}

/** How the connection to an online game stands, when there's something to say. */
@Composable
private fun OnlineNote(online: OnlineStatus, session: GameSession) {
    val (text, problem) = when {
        online.problem != null -> online.problem to true
        !online.connected -> "Connection lost. Reconnecting…" to true
        !online.opponentPresent && session.outcome == Outcome.Ongoing ->
            "${online.opponentName} has left the game for now. Their turns will arrive when they're back." to false
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (problem) MaterialTheme.colorScheme.error else Palette.inkSoft)
}

private fun headline(session: GameSession, thinking: Boolean, online: OnlineStatus?, aiName: String?): String {
    if (session.outcome != Outcome.Ongoing) return outcomeTitle(session.outcome)
    val side = session.state.sideToMove.displayName
    return when {
        online?.problem != null -> "Game stopped"
        online != null && thinking && !session.isRemoteTurn && online.sending -> "Sending your turn…"
        session.isRemoteTurn -> "${online?.opponentName ?: "Your friend"}'s turn · $side"
        thinking && session.mode.puzzle != null -> "Checking your answer…"
        thinking && !session.isComputerTurn -> "Checking the position…"
        thinking -> "${aiName ?: side} is thinking…"
        session.mode.localSide == session.state.sideToMove -> "Your turn · $side"
        else -> "$side to move"
    }
}

private fun detail(session: GameSession, nameOf: (Side) -> String): String {
    if (session.isExploring) {
        return outcomeText(session.outcome, nameOf) + " Try ${nameOf(session.state.sideToMove)}'s moves to see why."
    }
    if (session.outcome != Outcome.Ongoing) return outcomeText(session.outcome, nameOf)
    val state = session.state
    if (session.undecided) {
        return "The search couldn't confirm a legal turn here. If you can't find one, choose ⋮ › Stuck? Check for a legal turn."
    }
    val parts = mutableListOf("Turn ${Notation.turnNumber(state.present)}")
    val left = state.mandatoryTimelines.size
    when {
        state.canSubmit -> parts += "ready to end your turn"
        left == 1 -> parts += "1 board to play"
        left > 1 -> parts += "$left boards to play"
    }
    if (state.timelines.size > 1) parts += "${state.timelines.size} timelines"
    if (state.timelineLimit != null && state.atTimelineLimit(state.sideToMove)) parts += "no more branching for ${state.sideToMove.displayName}"
    return parts.joinToString(" · ")
}

private fun outcomeTitle(outcome: Outcome): String = when (outcome) {
    Outcome.Ongoing -> ""
    is Outcome.Checkmate -> "Checkmate"
    Outcome.Stalemate -> "Stalemate"
    is Outcome.Resignation -> "Resignation"
}

/** How the game ended, naming the players by [nameOf]. */
private fun outcomeText(outcome: Outcome, nameOf: (Side) -> String): String = when (outcome) {
    Outcome.Ongoing -> ""
    is Outcome.Checkmate -> "${nameOf(outcome.winner)} wins."
    Outcome.Stalemate -> "No legal turn and no king in danger: the game is drawn."
    is Outcome.Resignation -> "${nameOf(outcome.winner.opponent)} resigned. ${nameOf(outcome.winner)} wins."
}

/** Online, the players by the names they gave; otherwise, by their sides. */
private fun OnlineStatus?.names(): (Side) -> String = this?.let { it::nameOf } ?: { it.displayName }
