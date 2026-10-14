package com.andreaserick.multiversechess.ai

import com.andreaserick.multiversechess.engine.AiText
import com.andreaserick.multiversechess.engine.ComputerPlayer
import com.andreaserick.multiversechess.engine.DraftCheck
import com.andreaserick.multiversechess.engine.GameState
import com.andreaserick.multiversechess.engine.Move
import com.andreaserick.multiversechess.engine.Puzzle
import com.andreaserick.multiversechess.engine.PuzzleDrafts
import com.andreaserick.multiversechess.engine.PuzzleGenerator
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The AI's turn: its [moves] (null if there's no legal turn at all), what it said about them, and,
 * if the engine had to step in, why.
 */
class AiTurn(val moves: List<Move>?, val remark: String?, val fallback: String?)

/** What a language model does in the game: play turns and design puzzles. */
object AiGames {
    private const val CHOICES = 10
    private const val TURN_TIMEOUT_MILLIS = 150_000L
    private const val DESIGN_ATTEMPTS = 4

    /**
     * Has [model] choose a turn for the side to move in [state], among the engine's shortlist of
     * legal turns. If the model fails or gives no usable answer, the engine's favourite is played.
     */
    suspend fun turn(model: LanguageModel, state: GameState): AiTurn {
        val choices = withContext(Dispatchers.Default) {
            val job = coroutineContext.job
            ComputerPlayer().shortlist(state, CHOICES) { !job.isActive }
        }
        if (choices.isEmpty()) return AiTurn(null, null, null)
        if (choices.size == 1) return AiTurn(choices[0].moves, null, null)
        val prompt = AiText.choosePrompt(state, choices)
        return try {
            val messages = mutableListOf(ChatMessage(false, prompt))
            var reply = withTimeout(TURN_TIMEOUT_MILLIS) { model.chat(AiText.PLAYER_SYSTEM, messages) }
            var choice = AiText.parseChoice(reply, choices.size)
            if (choice == null) {
                // Once more, asking just for the number.
                messages += ChatMessage(true, reply)
                messages += ChatMessage(false, "Please answer with just a number from 1 to ${choices.size}, then your sentence.")
                reply = withTimeout(TURN_TIMEOUT_MILLIS) { model.chat(AiText.PLAYER_SYSTEM, messages) }
                choice = AiText.parseChoice(reply, choices.size)
            }
            if (choice == null) {
                AiTurn(choices[0].moves, null, "${model.name} didn't pick one of the turns, so the engine played its favourite.")
            } else {
                AiTurn(choices[choice.first].moves, choice.second, null)
            }
        } catch (e: AiException) {
            AiTurn(choices[0].moves, null, "${e.message} The engine played instead.")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            AiTurn(choices[0].moves, null, "${model.name} took too long to answer, so the engine played instead.")
        }
    }

    /**
     * Has [model] design a puzzle, optionally on a [theme]: the engine checks each attempt and
     * explains what's wrong, for up to [DESIGN_ATTEMPTS]. If none works out, the engine generates a
     * valid puzzle and the model gives it a title and hint. [progress] reports what's happening.
     */
    suspend fun designPuzzle(model: LanguageModel, number: Int, theme: String?, progress: (String) -> Unit): Puzzle {
        val id = "ai-${Random.nextLong().toULong().toString(16)}"
        val messages = mutableListOf(ChatMessage(false, AiText.designPrompt(theme)))
        var lastProblem: String? = null
        for (attempt in 1..DESIGN_ATTEMPTS) {
            progress(if (attempt == 1) "${model.name} is designing a puzzle…" else "${model.name} is fixing its puzzle (attempt $attempt of $DESIGN_ATTEMPTS)…")
            val reply = model.chat(AiText.DESIGNER_SYSTEM, messages)
            progress("Checking ${model.name}'s puzzle…")
            val check = withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                PuzzleDrafts.read(reply, id) { !job.isActive }
            }
            when (check) {
                is DraftCheck.Valid -> return check.puzzle
                is DraftCheck.Invalid -> {
                    lastProblem = check.problem
                    messages += ChatMessage(true, reply)
                    messages += ChatMessage(false, AiText.retryPrompt(check.problem))
                }
            }
        }
        progress("${model.name}'s puzzles didn't work out" + (lastProblem?.let { " (last: ${it.substringBefore('.')})" } ?: "") +
            ", so the engine is making one for it to present…")
        val generated = withContext(Dispatchers.Default) {
            val job = coroutineContext.job
            PuzzleGenerator(stop = { !job.isActive }).generate(number)
        } ?: throw AiException("No puzzle could be made this time. Please try again.")
        progress("${model.name} is naming the puzzle…")
        val named = try {
            AiText.parseTitleAndHint(model.chat(AiText.PRESENTER_SYSTEM, listOf(ChatMessage(false, AiText.presentPrompt(generated)))))
        } catch (_: AiException) {
            null
        }
        val start = generated.start()
        return Puzzle(id, named?.first ?: generated.title, named?.second ?: generated.hint, generated.solution) { position(start) }
    }
}
