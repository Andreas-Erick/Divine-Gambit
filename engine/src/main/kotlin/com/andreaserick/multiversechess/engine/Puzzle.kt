package com.andreaserick.multiversechess.engine

/**
 * A "mate in one turn" puzzle: White to move, and some turn leaves Black with no legal turn
 * while a king is threatened. [solution] is one such turn, as (from, to) pairs.
 */
class Puzzle(
    val id: String,
    val title: String,
    val hint: String,
    val solution: List<Pair<Pos, Pos>>,
    private val setup: PuzzleSetup.() -> Unit,
) {
    /** The position the puzzle starts from. */
    fun start(): GameState = PuzzleSetup().apply(setup).state.also {
        check(it.turnMoves.isEmpty()) { "Puzzle $id must start at the beginning of a turn" }
    }

    /** True if submitting [state] (a completed turn) checkmates the opponent. */
    fun isSolvedBy(state: GameState): Boolean = isCheckmatingTurn(state)

    companion object {
        /** Node budget used when judging whether a submitted puzzle turn is mate. */
        const val VERIFY_BUDGET = 60_000

        fun isCheckmatingTurn(state: GameState, budget: Int = VERIFY_BUDGET): Boolean {
            if (!state.canSubmit || state.isInCheck(state.sideToMove)) return false
            val mover = state.sideToMove
            return state.submit().evaluateOutcome(budget, maxOptionalMoves = 2) == Outcome.Checkmate(mover)
        }
    }
}

/** Builds a puzzle position by laying out a board and replaying a short history onto it. */
class PuzzleSetup {
    lateinit var state: GameState
        private set

    /** Starts from one board (rows from rank 8 down to rank 1), White to move. */
    fun board(vararg rows: String) {
        state = GameState(mapOf(0 to Timeline(0, 0, listOf(Board.parse(*rows)))), Side.WHITE)
    }

    /** Plays a move from [from] to [to], which must be legal. */
    fun move(from: Pos, to: Pos) {
        val move = state.legalMovesFrom(from).firstOrNull { it.to == to }
            ?: error("Illegal puzzle setup move $from -> $to")
        state = state.apply(move)
    }

    /** Plays an ordinary move such as "e2e4" on the latest board of timeline [l]. */
    fun move(squares: String, l: Int = 0) {
        val t = state.timelines.getValue(l).end
        move(Pos.of(l, t, squares.substring(0, 2)), Pos.of(l, t, squares.substring(2, 4)))
    }

    fun submit() {
        check(state.canSubmit && !state.isInCheck(state.sideToMove)) { "Puzzle setup turn can't be submitted" }
        state = state.submit()
    }

    /** Plays whole single-timeline turns, e.g. `turns("e1e2", "e8d8")`. */
    fun turns(vararg moves: String) {
        for (m in moves) {
            move(m)
            submit()
        }
    }
}

/** Finds every turn that checkmates. Exhaustive within [maxOptionalMoves]; meant for small puzzles. */
object PuzzleSolver {
    fun mates(start: GameState, maxOptionalMoves: Int = 1, budget: Int = Puzzle.VERIFY_BUDGET): List<List<Move>> {
        val found = ArrayList<List<Move>>()
        TurnWalker(Int.MAX_VALUE, maxOptionalMoves, extendLegalTurns = true).walk(start) { state ->
            if (Puzzle.isCheckmatingTurn(state, budget)) found += state.turnMoves
            false
        }
        return found
    }
}
