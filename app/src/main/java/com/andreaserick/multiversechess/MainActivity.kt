package com.andreaserick.multiversechess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andreaserick.multiversechess.engine.Puzzles
import com.andreaserick.multiversechess.ui.GameScreen
import com.andreaserick.multiversechess.ui.MenuScreen
import com.andreaserick.multiversechess.ui.MultiverseChessTheme
import com.andreaserick.multiversechess.ui.Palette
import com.andreaserick.multiversechess.ui.PuzzleListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always light, so system bar icons are always dark.
        val bars = SystemBarStyle.light(Palette.parchment.copy(alpha = 0f).toArgb(), Palette.ink.copy(alpha = 0.3f).toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            MultiverseChessTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MultiverseChessApp()
                }
            }
        }
    }
}

@Composable
fun MultiverseChessApp(modifier: Modifier = Modifier, viewModel: GameViewModel = viewModel()) {
    val session = viewModel.session
    val screen = when {
        session != null -> Screen.GAME
        viewModel.showingPuzzles -> Screen.PUZZLES
        else -> Screen.MENU
    }
    Crossfade(screen, label = "screen") { shown ->
        Screen(shown, viewModel, modifier)
    }
}

private enum class Screen { MENU, PUZZLES, GAME }

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
                nextPuzzle = viewModel.nextPuzzle,
                onTap = viewModel::tap,
                onSubmit = viewModel::submit,
                onUndo = viewModel::undo,
                onResign = viewModel::resign,
                onShowSolution = viewModel::showSolution,
                onClaimNoLegalTurn = viewModel::claimNoLegalTurn,
                onStartPuzzle = viewModel::startPuzzle,
                onExit = viewModel::back,
                modifier = modifier,
            )
        }
        Screen.PUZZLES -> {
            BackHandler { viewModel.back() }
            PuzzleListScreen(
                puzzles = Puzzles.all,
                solved = viewModel.solvedPuzzles,
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
            puzzleProgress = "${Puzzles.all.count { it.id in viewModel.solvedPuzzles }} / ${Puzzles.all.size}",
            modifier = modifier,
        )
    }
}
