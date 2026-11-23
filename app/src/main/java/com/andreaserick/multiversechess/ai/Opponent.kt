package com.andreaserick.multiversechess.ai

import com.andreaserick.multiversechess.engine.ComputerPlayer
import com.andreaserick.multiversechess.engine.GameSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** The opponent's turn played: the game after it, and what the opponent said about it, if anything. */
class OpponentTurn(val next: GameSession, val remark: String?)

/** Who plays the computer's side of a game ([com.andreaserick.multiversechess.engine.GameMode.computerSide]). */
fun interface Opponent {
    /**
     * Plays the computer's turn in [game], which must be its turn. It can take a while; cancelling
     * the coroutine abandons it.
     */
    suspend fun play(game: GameSession): OpponentTurn

    companion object {
        /** The engine, searching on its own. */
        val engine: Opponent = Opponent { game ->
            val next = withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                game.applyComputerTurn(ComputerPlayer().chooseTurn(game.state) { !job.isActive })
            }
            OpponentTurn(next, null)
        }

        /**
         * [model] choosing among the engine's best turns ([AiGames.turn]). When the engine had to
         * step in, the game's message says why.
         */
        fun languageModel(model: LanguageModel): Opponent = Opponent { game ->
            val turn = AiGames.turn(model, game.state)
            val next = withContext(Dispatchers.Default) { game.applyComputerTurn(turn.moves) }
            OpponentTurn(
                if (turn.fallback != null) next.copy(message = turn.fallback) else next,
                turn.remark?.let { "${model.name}: “$it”" },
            )
        }

        /** Who plays the computer's side of [game]: the AI if the game is against it and one is set up, otherwise the engine. */
        fun of(game: GameSession, model: LanguageModel?): Opponent =
            if (game.mode.aiOpponent && model != null) languageModel(model) else engine
    }
}
