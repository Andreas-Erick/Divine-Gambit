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
    /**
     * The check after the last turn ran out of search before finding the side to move a legal
     * turn or proving there is none. [claimNoLegalTurn] settles it.
     */
    val undecided: Boolean = false,
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
        val (ended, undecided) = judge(next)
        return copy(
            state = next,
            selected = null,
            message = null,
            outcome = ended,
            undecided = undecided,
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

    /**
     * Plays a whole turn chosen by the computer. Null [moves] means it found none: the game ends
     * in checkmate or stalemate if there really is none, and otherwise the computer resigns.
     */
    fun applyComputerTurn(moves: List<Move>?): GameSession {
        if (moves == null) {
            if (TurnSearch(OUTCOME_BUDGET).findLegalTurn(state) == TurnSearchResult.NoLegalTurn) {
                return copy(outcome = endWithoutTurn(state), undecided = false)
            }
            return copy(
                outcome = Outcome.Resignation(state.sideToMove.opponent),
                undecided = false,
                message = "${state.sideToMove.displayName} couldn't find a legal turn, so it resigned.",
            )
        }
        var s = state
        val lines = moves.map { move -> logLine(s, move).also { s = s.apply(move) } }
        val next = s.submit()
        val (ended, undecided) = judge(next)
        return copy(
            state = next,
            selected = null,
            message = null,
            outcome = ended,
            undecided = undecided,
            log = log + lines,
            lastTurn = moves,
            previous = this,
        )
    }

    /**
     * Searches every turn the side to move could play from the start of this turn, however long it
     * takes (unless [stop] returns true). With none, the game ends in checkmate or stalemate;
     * otherwise the message shows how one legal turn begins.
     */
    fun claimNoLegalTurn(stop: () -> Boolean = { false }): GameSession {
        if (outcome != Outcome.Ongoing || isComputerTurn) return this
        val start = turnStart()
        return when (val result = TurnSearch(Int.MAX_VALUE, stop = stop).findLegalTurn(start)) {
            is TurnSearchResult.Found -> {
                val first = result.result.turnMoves.first()
                copy(undecided = false, message = "There is a legal turn. It can start with ${Notation.describe(start, first)}.")
            }
            TurnSearchResult.NoLegalTurn -> copy(outcome = endWithoutTurn(start), undecided = false, selected = null, message = null)
            TurnSearchResult.Inconclusive -> this
        }
    }

    /** The position at the start of the current turn, before any of its moves. */
    private fun turnStart(): GameState {
        var s = this
        while (s.state.turnMoves.isNotEmpty()) s = s.previous ?: break
        return s.state
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

    /**
     * Whether the game is over once [next] is reached, and whether the search ran out before it
     * could tell (in which case the game goes on, undecided).
     */
    private fun judge(next: GameState): Pair<Outcome, Boolean> = when (TurnSearch(OUTCOME_BUDGET).findLegalTurn(next)) {
        TurnSearchResult.NoLegalTurn -> endWithoutTurn(next) to false
        TurnSearchResult.Inconclusive -> Outcome.Ongoing to true
        is TurnSearchResult.Found -> Outcome.Ongoing to false
    }

    /** How the game ends when the side to move in [at] has no legal turn. */
    private fun endWithoutTurn(at: GameState): Outcome =
        if (at.threats().isNotEmpty()) Outcome.Checkmate(at.sideToMove.opponent) else Outcome.Stalemate

    /** Why the current turn can't be submitted, or null if it can. */
    private fun refusal(): String? = when {
        state.turnMoves.isEmpty() -> "Make a move first."
        !state.canSubmit -> "You still have to move on every board in the present (outlined in gold)."
        state.isInCheck(state.sideToMove) -> "Your king could be captured. Undo and try different moves."
        else -> null
    }

    private fun logLine(before: GameState, move: Move): String =
        "${before.sideToMove.displayName}: ${Notation.describe(before, move)}"

    companion object {
        /**
         * Positions searched after each turn for a legal reply. Generous, since it runs off the
         * main thread and usually finds one within a few positions.
         */
        const val OUTCOME_BUDGET = 40_000
    }
}
