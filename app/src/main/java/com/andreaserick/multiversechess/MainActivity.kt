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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andreaserick.multiversechess.ui.AiSettingsScreen
import com.andreaserick.multiversechess.ui.AiSettingsState
import com.andreaserick.multiversechess.ui.ConnectingScreen
import com.andreaserick.multiversechess.ui.GameScreen
import com.andreaserick.multiversechess.ui.InvitationScreen
import com.andreaserick.multiversechess.ui.LobbyScreen
import com.andreaserick.multiversechess.ui.MenuScreen
import com.andreaserick.multiversechess.ui.MultiverseChessTheme
import com.andreaserick.multiversechess.ui.Palette
import com.andreaserick.multiversechess.ui.PuzzleListScreen

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

private enum class Screen { MENU, PUZZLES, GAME, LOBBY, CONNECTING, INVITATION, AI_SETTINGS }

@Composable
private fun Screen(screen: Screen, viewModel: GameViewModel, modifier: Modifier) {
    val session = viewModel.session
    when (screen) {
        Screen.GAME -> {
            // While fading out after the game has closed, there's nothing left to draw.
            if (session == null) return
            BackHandler { viewModel.back() }
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
            )
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
            onPuzzles = viewModel::openPuzzles,
            puzzleProgress = viewModel.allPuzzles.let { all -> "${all.count { it.id in viewModel.solvedPuzzles }} / ${all.size}" },
            canPlayOnline = viewModel.canPlayOnline,
            resumableOnline = viewModel.resumableOnline,
            onContinueOnline = viewModel::continueOnline,
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
