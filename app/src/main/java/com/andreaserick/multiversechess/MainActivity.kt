package com.andreaserick.multiversechess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andreaserick.multiversechess.ui.GameScreen
import com.andreaserick.multiversechess.ui.MenuScreen
import com.andreaserick.multiversechess.ui.MultiverseChessTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MultiverseChessTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MultiverseChessApp(Modifier.safeDrawingPadding())
                }
            }
        }
    }
}

@Composable
fun MultiverseChessApp(modifier: Modifier = Modifier, viewModel: GameViewModel = viewModel()) {
    val session = viewModel.session
    if (session == null) {
        MenuScreen(onStart = viewModel::startGame, modifier = modifier)
    } else {
        BackHandler { viewModel.exitToMenu() }
        GameScreen(
            session = session,
            thinking = viewModel.thinking,
            onTap = viewModel::tap,
            onSubmit = viewModel::submit,
            onUndo = viewModel::undo,
            onResign = viewModel::resign,
            onExit = viewModel::exitToMenu,
            modifier = modifier,
        )
    }
}
