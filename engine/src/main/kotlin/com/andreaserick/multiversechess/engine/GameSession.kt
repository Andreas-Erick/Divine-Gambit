package com.andreaserick.multiversechess.engine

/** [computerSide] is the side the computer plays, or null for two humans on one device. */
data class GameMode(val computerSide: Side? = null, val variant: Variant = Variant.STANDARD)

/**
 * Everything the UI needs for one game: the position, the selection, the move log and an undo
 * chain. Immutable; every action returns a new session.
 */
data class GameSession(
    val mode: GameMode,
    val state: GameState = GameState.initial(mode.variant),
    val selected: Pos? = null,
    val outcome: Outcome = Outcome.Ongoing,
    /** Explanation of why the last action was refused, if it was. */
    val message: String? = null,
    val log: List<String> = emptyList(),
    /** Moves of the most recently submitted turn. */
    val lastTurn: List<Move> = emptyList(),
    val previous: GameSession? = null,
) {
    val isComputerTurn: Boolean get() = outcome == Outcome.Ongoing && mode.computerSide == state.sideToMove

    /** Legal moves of the selected piece. */
    val targets: List<Move> get() = selected?.let { state.legalMovesFrom(it) } ?: emptyList()

    fun tap(pos: Pos): GameSession {
        if (outcome != Outcome.Ongoing || isComputerTurn) return this
        selected?.let { sel ->
            state.legalMovesFrom(sel).firstOrNull { it.to == pos }?.let { return play(it) }
        }
        val piece = state.pieceAt(pos)
        val selectable = piece != null && piece.side == state.sideToMove && state.isPlayable(pos.l, pos.t)
        return copy(selected = if (selectable && pos != selected) pos else null, message = null)
    }

    fun play(move: Move): GameSession = copy(
        state = state.apply(move),
        selected = null,
        message = null,
        log = log + logLine(state, move),
        previous = this,
    )

    fun submit(): GameSession {
        if (outcome != Outcome.Ongoing) return this
        if (!state.canSubmit) {
            val reason = if (state.turnMoves.isEmpty()) {
                "Make a move first."
            } else {
                "You still have to move on every board in the present (outlined in gold)."
            }
            return copy(message = reason)
        }
        if (state.isInCheck(state.sideToMove)) {
            return copy(message = "Your king could be captured. Undo and try different moves.")
        }
        val next = state.submit()
        return copy(
            state = next,
            selected = null,
            message = null,
            outcome = next.evaluateOutcome(),
            lastTurn = state.turnMoves,
            previous = this,
        )
    }

    /** Plays a whole turn chosen by the computer; null [moves] means it had no legal turn. */
    fun applyComputerTurn(moves: List<Move>?): GameSession {
        if (moves == null) {
            val ended = if (state.threats().isNotEmpty()) Outcome.Checkmate(state.sideToMove.opponent) else Outcome.Stalemate
            return copy(outcome = ended)
        }
        var s = state
        val lines = moves.map { move -> logLine(s, move).also { s = s.apply(move) } }
        val next = s.submit()
        return copy(
            state = next,
            selected = null,
            message = null,
            outcome = next.evaluateOutcome(),
            log = log + lines,
            lastTurn = moves,
            previous = this,
        )
    }

    /** Steps back one action; against the computer, also skips back past the computer's turn. */
    fun undo(): GameSession {
        var s = previous ?: return this
        while (s.isComputerTurn) s = s.previous ?: break
        return s.copy(selected = null, message = null)
    }

    fun resign(): GameSession {
        if (outcome != Outcome.Ongoing) return this
        val loser = if (mode.computerSide != null) mode.computerSide.opponent else state.sideToMove
        return copy(outcome = Outcome.Resignation(loser.opponent), selected = null, previous = this)
    }

    private fun logLine(before: GameState, move: Move): String =
        "${before.sideToMove.displayName}: ${Notation.describe(before, move)}"
}
