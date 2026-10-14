package com.andreaserick.multiversechess.engine

/**
 * [computerSide] is the side the computer plays, or null for two humans on one device.
 * With a [puzzle], the game starts from the puzzle's position and ends once it is solved.
 */
data class GameMode(
    val computerSide: Side? = null,
    val variant: Variant = Variant.STANDARD,
    val puzzle: Puzzle? = null,
)

/**
 * Everything the UI needs for one game: the position, the selection, the move log and an undo
 * chain. Immutable; every action returns a new session.
 */
data class GameSession(
    val mode: GameMode,
    val state: GameState = mode.puzzle?.start() ?: GameState.initial(mode.variant),
    val selected: Pos? = null,
    val outcome: Outcome = Outcome.Ongoing,
    /** Explanation of why the last action was refused, if it was. */
    val message: String? = null,
    val log: List<String> = emptyList(),
    /** Moves of the most recently submitted turn. */
    val lastTurn: List<Move> = emptyList(),
    /** Whether the puzzle's solution has been revealed in this attempt. */
    val solutionShown: Boolean = false,
    val previous: GameSession? = null,
) {
    val isComputerTurn: Boolean get() = outcome == Outcome.Ongoing && mode.computerSide == state.sideToMove

    /**
     * After checkmate or stalemate, moves can still be tried on the losing side's behalf to see
     * why every turn fails; they just can't be submitted.
     */
    val isExploring: Boolean get() = outcome is Outcome.Checkmate || outcome == Outcome.Stalemate

    /** Legal moves of the selected piece. */
    val targets: List<Move> get() = selected?.let { state.legalMovesFrom(it) } ?: emptyList()

    fun tap(pos: Pos): GameSession {
        if ((outcome != Outcome.Ongoing && !isExploring) || isComputerTurn) return this
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
        if (outcome != Outcome.Ongoing) {
            if (!isExploring) return this
            return copy(message = refusal() ?: "The game is over.")
        }
        refusal()?.let { return copy(message = it) }
        mode.puzzle?.let { return submitPuzzle(it) }
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

    /**
     * A puzzle turn either checkmates, which solves the puzzle, or is taken back with an
     * example of how the opponent escapes. Undo returns to the attempt.
     */
    private fun submitPuzzle(puzzle: Puzzle): GameSession {
        if (puzzle.isSolvedBy(state)) {
            return copy(
                state = state.submit(),
                selected = null,
                message = null,
                outcome = Outcome.Checkmate(state.sideToMove),
                lastTurn = state.turnMoves,
                previous = this,
            )
        }
        val after = state.submit()
        val escape = (TurnSearch(Puzzle.VERIFY_BUDGET, maxOptionalMoves = 2).findLegalTurn(after) as? TurnSearchResult.Found)
            ?.result?.turnMoves?.firstOrNull()
        val reason = if (escape != null) {
            "Not checkmate: ${after.sideToMove.displayName} escapes with ${Notation.describe(after, escape)}."
        } else {
            "That isn't checkmate."
        }
        return copy(
            state = puzzle.start(),
            selected = null,
            message = "$reason Try again.",
            lastTurn = emptyList(),
            previous = this,
        )
    }

    /** Plays the puzzle's solution, ready to submit. */
    fun showSolution(): GameSession {
        val puzzle = mode.puzzle ?: return this
        if (outcome != Outcome.Ongoing) return this
        var s = puzzle.start()
        val lines = puzzle.solution.map { (from, to) ->
            val move = s.legalMovesFrom(from).first { it.to == to }
            logLine(s, move).also { s = s.apply(move) }
        }
        return copy(
            state = s,
            selected = null,
            message = "This is the solution. Tap Submit to play it.",
            log = log + lines,
            solutionShown = true,
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
        if (outcome != Outcome.Ongoing || mode.puzzle != null) return this
        val loser = if (mode.computerSide != null) mode.computerSide.opponent else state.sideToMove
        return copy(outcome = Outcome.Resignation(loser.opponent), selected = null, previous = this)
    }

    /** Why the current turn can't be submitted, or null if it can. */
    private fun refusal(): String? = when {
        state.turnMoves.isEmpty() -> "Make a move first."
        !state.canSubmit -> "You still have to move on every board in the present (outlined in gold)."
        state.isInCheck(state.sideToMove) -> "Your king could be captured. Undo and try different moves."
        else -> null
    }

    private fun logLine(before: GameState, move: Move): String =
        "${before.sideToMove.displayName}: ${Notation.describe(before, move)}"
}
