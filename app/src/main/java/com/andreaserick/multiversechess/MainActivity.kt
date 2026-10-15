package com.andreaserick.multiversechess

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.ui.AiSettingsScreen
import com.andreaserick.multiversechess.ui.AiSettingsState
import com.andreaserick.multiversechess.ui.ConnectingScreen
import com.andreaserick.multiversechess.ui.GameScreen
import com.andreaserick.multiversechess.ui.InfernoScreen
import com.andreaserick.multiversechess.ui.InvitationScreen
import com.andreaserick.multiversechess.ui.LobbyScreen
import com.andreaserick.multiversechess.ui.MenuScreen
import com.andreaserick.multiversechess.ui.MultiverseChessTheme
import com.andreaserick.multiversechess.ui.Palette
import com.andreaserick.multiversechess.ui.PuzzleListScreen
import com.andreaserick.multiversechess.ui.portal

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openInvitation(intent)
        // The app is always dark, so system bar icons are always light.
        val bars = SystemBarStyle.dark(Palette.background.copy(alpha = 0f).toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            MultiverseChessTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MultiverseChessApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openInvitation(intent)
    }

    /** An invitation link to an online game (https://<relay>/join/<code>) joins it. */
    private fun openInvitation(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val segments = intent.data?.pathSegments ?: return
        if (segments.size == 2 && segments[0] == "join") viewModel.joinFriend(segments[1])
    }
}

@Composable
fun MultiverseChessApp(modifier: Modifier = Modifier, viewModel: GameViewModel = viewModel()) {
    val session = viewModel.session
    val online = viewModel.online
    val screen = when {
        viewModel.showingAiSettings -> Screen.AI_SETTINGS
        viewModel.connecting != null -> Screen.CONNECTING
        viewModel.invitation != null -> Screen.INVITATION
        online != null && !online.opponentJoined && online.problem == null -> Screen.LOBBY
        session != null -> Screen.GAME
        viewModel.showingPuzzles -> Screen.PUZZLES
        else -> Screen.MENU
    }
    Crossfade(screen, label = "screen") { shown ->
        Screen(shown, viewModel, modifier)
    }
}

/** The game itself, as on screen while the player is on the board. */
@Composable
private fun GameBoard(
    viewModel: GameViewModel,
    session: GameSession,
    onInfernoPlaced: (Offset) -> Unit,
    onEnterInferno: (Int?, Offset?) -> Unit,
    modifier: Modifier,
) {
    GameScreen(
        session = session,
        thinking = viewModel.thinking,
        online = viewModel.online,
        aiName = viewModel.aiModel?.name?.takeIf { session.mode.aiOpponent },
        aiRemark = viewModel.aiRemark,
        nextPuzzle = viewModel.nextPuzzle,
        onTap = viewModel::tap,
        onSubmit = viewModel::submit,
        onUndo = viewModel::undo,
        onResign = viewModel::resign,
        onShowSolution = viewModel::showSolution,
        onRestartPuzzle = viewModel::restartPuzzle,
        onClaimNoLegalTurn = viewModel::claimNoLegalTurn,
        onStartPuzzle = viewModel::startPuzzle,
        onExit = viewModel::back,
        modifier = modifier,
        infernoSouls = viewModel.hellSouls(),
        hellClosed = viewModel.hellClosed,
        infernoNotice = viewModel.infernoNotice.takeIf { !viewModel.inHell },
        onEnterInferno = onEnterInferno,
        onInfernoPlaced = onInfernoPlaced,
    )
}

/**
 * Draws the picture [still] gives in place of the content while there is one; otherwise draws
 * the content through [layer], so a picture of it can be taken at any moment.
 */
private fun Modifier.stillOr(layer: GraphicsLayer, still: () -> ImageBitmap?): Modifier = drawWithContent {
    val picture = still()
    if (picture != null) {
        drawImage(picture, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    } else {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** Slow to leave, slow to arrive: the passage between the board and the Inferno. */
private val PassageEasing = CubicBezierEasing(0.6f, 0f, 0.3f, 1f)

private enum class Screen { MENU, PUZZLES, GAME, LOBBY, CONNECTING, INVITATION, AI_SETTINGS }

@Composable
private fun Screen(screen: Screen, viewModel: GameViewModel, modifier: Modifier) {
    val session = viewModel.session
    when (screen) {
        Screen.GAME -> {
            // While fading out after the game has closed, there's nothing left to draw.
            if (session == null) return
            BackHandler { if (viewModel.inHell) viewModel.leaveHell() else viewModel.back() }
            // Into the Inferno and back: a ring of fire burns open from the Descend button while the
            // board sinks away into the dark. The screen being left is kept as a still picture for
            // the passage, so it keeps its colours while the other is already in its own, and
            // nothing has to be laid out again mid-passage.
            val inHell = viewModel.inHell
            val descent = remember { Animatable(if (inHell) 1f else 0f) }
            val boardLayer = rememberGraphicsLayer()
            val hellLayer = rememberGraphicsLayer()
            var stillBoard by remember { mutableStateOf<ImageBitmap?>(null) }
            var stillHell by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(inHell) {
                if (inHell == (descent.value == 1f)) {
                    Palette.heat = if (inHell) 1f else 0f
                    return@LaunchedEffect
                }
                stillHell = null
                stillBoard = null
                if (inHell) {
                    stillBoard = boardLayer.toImageBitmap()
                    Palette.heat = 1f
                    descent.animateTo(1f, tween(1_500, easing = PassageEasing))
                } else {
                    stillHell = hellLayer.toImageBitmap()
                    Palette.heat = 0f
                    descent.animateTo(0f, tween(1_200, easing = PassageEasing))
                }
                stillBoard = null
                stillHell = null
            }
            // Seconds since the passage began, to make its fire crawl.
            val passing by remember { derivedStateOf { descent.value > 0f && descent.value < 1f } }
            val clock = remember { mutableFloatStateOf(0f) }
            LaunchedEffect(passing) {
                if (!passing) return@LaunchedEffect
                val start = withFrameNanos { it }
                while (true) withFrameNanos { clock.floatValue = (it - start) / 1e9f }
            }
            DisposableEffect(Unit) { onDispose { Palette.heat = 0f } }
            val hellShown by remember { derivedStateOf { descent.value > 0f } }
            // The fire breaks through from where the player chose to descend (the Descend button,
            // or the scroll), and closes back into the Descend button on the way up.
            var portalOrigin by remember { mutableStateOf<Offset?>(null) }
            var descentOrigin by remember { mutableStateOf<Offset?>(null) }
            Box(modifier) {
                // Both stay composed, so neither has to be built mid-passage; whichever is in front
                // takes the touches.
                GameBoard(
                    viewModel,
                    session,
                    onInfernoPlaced = { portalOrigin = it },
                    onEnterInferno = { soul, from ->
                        descentOrigin = from
                        viewModel.enterHell(soul)
                    },
                    modifier = Modifier
                        .graphicsLayer {
                            // The board sinks and turns away into the dark.
                            val d = descent.value
                            scaleX = 1f - 0.14f * d
                            scaleY = 1f - 0.14f * d
                            rotationZ = -3f * d
                            translationY = 36.dp.toPx() * d
                            alpha = if (d >= 1f) 0f else 1f
                        }
                        .drawWithContent {
                            drawContent()
                            val d = descent.value
                            if (d > 0f) drawRect(Brush.radialGradient(listOf(Color(0x99400602), Color(0xF20A0101))), alpha = d)
                        }
                        .stillOr(boardLayer) { stillBoard },
                )
                if (session.mode.inferno) {
                    val side = session.mode.onlineSide ?: session.mode.computerSide?.opponent ?: session.state.sideToMove
                    InfernoScreen(
                        side = side,
                        opponent = viewModel.online?.nameOf(side.opponent) ?: side.opponent.displayName,
                        souls = viewModel.hellSouls(),
                        chosen = viewModel.trialSoul,
                        trial = viewModel.trial,
                        checking = viewModel.trialChecking,
                        seconds = viewModel.hellSeconds,
                        clockRunning = session.outcome == Outcome.Ongoing && !session.isComputerTurn && !session.isRemoteTurn,
                        notice = viewModel.infernoNotice,
                        ended = viewModel.endedInHell,
                        onChoose = viewModel::chooseSoul,
                        onTap = viewModel::trialTap,
                        onSubmit = viewModel::submitTrial,
                        onUndo = viewModel::trialUndo,
                        onRestart = viewModel::restartTrial,
                        onSkip = viewModel::skipTrial,
                        onNext = viewModel::nextTrial,
                        onLeave = viewModel::leaveHell,
                        active = hellShown && stillHell == null,
                        modifier = Modifier
                            .zIndex(if (hellShown) 1f else -1f)
                            .portal({ descent.value }, { if (inHell) descentOrigin ?: portalOrigin else portalOrigin }, { clock.floatValue })
                            .stillOr(hellLayer) { stillHell },
                    )
                }
            }
        }
        Screen.PUZZLES -> {
            BackHandler { viewModel.back() }
            PuzzleListScreen(
                puzzles = viewModel.allPuzzles,
                solved = viewModel.solvedPuzzles,
                canGenerate = viewModel.canGenerate,
                generating = viewModel.generating,
                generateFailed = viewModel.generateFailed,
                aiName = viewModel.aiModel?.name,
                designing = viewModel.designing,
                designFailed = viewModel.designFailed,
                onDesign = viewModel::designPuzzle,
                onAiSettings = viewModel::openAiSettings,
                onGenerate = viewModel::generatePuzzle,
                onOpen = viewModel::startPuzzle,
                onBack = viewModel::back,
                modifier = modifier,
            )
        }
        Screen.MENU -> MenuScreen(
            onStart = viewModel::startGame,
            resumable = viewModel.resumable,
            onContinue = viewModel::continueGame,
            onDiscard = viewModel::discardGame,
            onPuzzles = viewModel::openPuzzles,
            puzzleProgress = viewModel.allPuzzles.let { all -> "${all.count { it.id in viewModel.solvedPuzzles }} / ${all.size}" },
            canPlayOnline = viewModel.canPlayOnline,
            resumableOnline = viewModel.resumableOnline,
            onContinueOnline = viewModel::continueOnline,
            onDiscardOnline = viewModel::discardOnline,
            playerName = viewModel.playerName,
            aiName = viewModel.aiModel?.name,
            onAiSettings = viewModel::openAiSettings,
            onInvite = viewModel::inviteFriend,
            onJoin = viewModel::joinFriend,
            modifier = modifier,
        )
        Screen.LOBBY -> {
            val online = viewModel.online ?: return
            BackHandler { viewModel.back() }
            LobbyScreen(online, onBack = viewModel::back, modifier = modifier)
        }
        Screen.AI_SETTINGS -> {
            BackHandler { viewModel.back() }
            AiSettingsScreen(
                AiSettingsState(
                    settings = viewModel.aiSettings,
                    onChange = { settings ->
                        viewModel.clearAiTest()
                        viewModel.saveAiSettings(settings)
                    },
                    test = viewModel.aiTest,
                    onTest = { viewModel.testAi(viewModel.aiSettings) },
                    models = viewModel.aiModels,
                    onListModels = { viewModel.listAiModels(viewModel.aiSettings) },
                    installed = viewModel.installedModels,
                    downloads = viewModel.downloads,
                    downloadErrors = viewModel.downloadErrors,
                    onDownload = viewModel::download,
                    onCancelDownload = viewModel::cancelDownload,
                    onDelete = viewModel::deleteModel,
                ),
                onBack = viewModel::back,
                modifier = modifier,
            )
        }
        Screen.INVITATION -> {
            val invitation = viewModel.invitation ?: return
            BackHandler { viewModel.back() }
            InvitationScreen(invitation, viewModel.playerName, onJoin = viewModel::acceptInvitation, onBack = viewModel::back, modifier = modifier)
        }
        Screen.CONNECTING -> {
            val connecting = viewModel.connecting ?: return
            BackHandler { viewModel.back() }
            ConnectingScreen(connecting, onRetry = viewModel::retryConnecting, onBack = viewModel::back, modifier = modifier)
        }
    }
}
