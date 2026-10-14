package com.andreaserick.multiversechess

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreaserick.multiversechess.engine.ComputerPlayer
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Puzzle
import com.andreaserick.multiversechess.engine.Puzzles
import com.andreaserick.multiversechess.engine.SavedGame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel(application: Application) : AndroidViewModel(application) {
    /** The current game, or null while a menu is showing. */
    var session by mutableStateOf<GameSession?>(null)
        private set

    /** Whether the puzzle list is showing (when there is no game). */
    var showingPuzzles by mutableStateOf(false)
        private set

    /** Busy computing: the computer's turn, or checking a puzzle answer. */
    var thinking by mutableStateOf(false)
        private set

    private val prefs = application.getSharedPreferences("progress", Context.MODE_PRIVATE)

    /** Ids of puzzles solved without looking at the solution. */
    var solvedPuzzles by mutableStateOf(prefs.getStringSet(KEY_SOLVED, emptySet())!!.toSet())
        private set

    private var job: Job? = null

    /** Where the current game is kept, so it survives the app being closed. */
    private val saveFile = File(application.filesDir, "game.txt")

    /** Saves run one at a time, in order, off the main thread. */
    private val saving = Dispatchers.IO.limitedParallelism(1)

    /** An unfinished game that can be continued from the menu. */
    var resumable by mutableStateOf<GameSession?>(null)
        private set

    init {
        viewModelScope.launch {
            val saved = withContext(saving) {
                runCatching { saveFile.takeIf { it.exists() }?.readText()?.let(SavedGame::decode) }.getOrNull()
            }
            if (session == null && saved != null && saved.outcome == Outcome.Ongoing) resumable = saved
        }
    }

    fun startGame(mode: GameMode) = show(GameSession(mode))

    fun continueGame() {
        resumable?.let { show(it) }
    }

    fun openPuzzles() {
        showingPuzzles = true
    }

    fun startPuzzle(puzzle: Puzzle) = startGame(GameMode(puzzle = puzzle))

    /** The puzzle after the current one, if any. */
    val nextPuzzle: Puzzle?
        get() {
            val current = session?.mode?.puzzle ?: return null
            return Puzzles.all.getOrNull(Puzzles.all.indexOf(current) + 1)
        }

    /** Leaves the game, back to the puzzle list if it was a puzzle; from the puzzle list, to the menu. */
    fun back() {
        stopJob()
        if (session == null) showingPuzzles = false
        session?.let { if (it.mode.puzzle == null && it.outcome == Outcome.Ongoing) resumable = it }
        session = null
    }

    fun tap(pos: Pos) = update { it.tap(pos) }

    /** Checking whether the opponent has a legal reply (or a puzzle answer is mate) can take a moment. */
    fun submit() {
        val current = session ?: return
        runInBackground(current) { session, _ -> session.submit() }
    }

    /** Searches every turn; this can take a while, and Undo or leaving the game cancels it. */
    fun claimNoLegalTurn() {
        val current = session ?: return
        runInBackground(current) { session, stop -> session.claimNoLegalTurn(stop) }
    }

    fun showSolution() = update { it.showSolution() }

    fun resign() = update { it.resign() }

    fun undo() {
        stopJob()
        update { it.undo() }
    }

    private inline fun update(action: (GameSession) -> GameSession) {
        session?.let { show(action(it)) }
    }

    private fun show(next: GameSession) {
        val previous = session
        session = next
        recordSolved(previous, next)
        save(next)
        if (next.isComputerTurn) {
            runInBackground(next) { session, stop -> session.applyComputerTurn(ComputerPlayer().chooseTurn(session.state, stop)) }
        }
    }

    /** Keeps [game] on disk while it's unfinished; a finished game (or a puzzle) isn't kept. */
    private fun save(game: GameSession) {
        if (game.mode.puzzle != null) return
        resumable = null
        viewModelScope.launch(saving) {
            runCatching {
                if (game.outcome == Outcome.Ongoing) saveFile.writeText(SavedGame.encode(game)) else saveFile.delete()
            }
        }
    }

    private fun recordSolved(previous: GameSession?, next: GameSession) {
        val puzzle = next.mode.puzzle ?: return
        if (next.outcome != Outcome.Checkmate(next.state.sideToMove.opponent) || previous?.outcome != Outcome.Ongoing) return
        if (next.solutionShown || puzzle.id in solvedPuzzles) return
        solvedPuzzles = solvedPuzzles + puzzle.id
        prefs.edit { putStringSet(KEY_SOLVED, solvedPuzzles) }
    }

    /** Runs [action] off the main thread; its stop callback turns true once the job is cancelled. */
    private fun runInBackground(from: GameSession, action: (GameSession, () -> Boolean) -> GameSession) {
        stopJob()
        thinking = true
        job = viewModelScope.launch {
            val next = withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                action(from) { !job.isActive }
            }
            // Ignore the result if the player undid or left the game meanwhile.
            if (session === from) {
                thinking = false
                show(next)
            }
        }
    }

    private fun stopJob() {
        job?.cancel()
        job = null
        thinking = false
    }

    private companion object {
        const val KEY_SOLVED = "solved_puzzles"
    }
}
