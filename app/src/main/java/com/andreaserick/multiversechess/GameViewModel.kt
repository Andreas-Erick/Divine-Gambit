package com.andreaserick.multiversechess

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreaserick.multiversechess.engine.ComputerPlayer
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Pos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel : ViewModel() {
    /** The current game, or null while the menu is showing. */
    var session by mutableStateOf<GameSession?>(null)
        private set

    var thinking by mutableStateOf(false)
        private set

    private var computerJob: Job? = null

    fun startGame(mode: GameMode) = show(GameSession(mode))

    fun exitToMenu() {
        stopComputer()
        session = null
    }

    fun tap(pos: Pos) = update { it.tap(pos) }

    fun submit() = update { it.submit() }

    fun resign() = update { it.resign() }

    fun undo() {
        stopComputer()
        update { it.undo() }
    }

    private inline fun update(action: (GameSession) -> GameSession) {
        session?.let { show(action(it)) }
    }

    private fun show(next: GameSession) {
        session = next
        if (next.isComputerTurn) runComputer(next)
    }

    private fun runComputer(from: GameSession) {
        stopComputer()
        thinking = true
        computerJob = viewModelScope.launch {
            val moves = withContext(Dispatchers.Default) { ComputerPlayer().chooseTurn(from.state) }
            // Ignore the result if the player undid or left the game meanwhile.
            if (session === from) {
                thinking = false
                show(from.applyComputerTurn(moves))
            }
        }
    }

    private fun stopComputer() {
        computerJob?.cancel()
        computerJob = null
        thinking = false
    }
}
