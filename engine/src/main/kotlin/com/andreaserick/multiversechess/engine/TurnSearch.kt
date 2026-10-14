package com.andreaserick.multiversechess.engine

sealed interface TurnSearchResult {
    /** [result] is the state after the legal turn's moves, ready to submit. */
    data class Found(val result: GameState) : TurnSearchResult
    data object NoLegalTurn : TurnSearchResult
    data object Inconclusive : TurnSearchResult
}

/**
 * Looks for any legal turn: one move on (or onto) each present board, plus up to
 * [maxOptionalMoves] extra moves elsewhere.
 *
 * A full turn can combine many moves across many boards, so the search is capped at
 * [nodeBudget] positions and reports [TurnSearchResult.Inconclusive] if it runs out.
 */
class TurnSearch(private val nodeBudget: Int = 4_000, private val maxOptionalMoves: Int = 1) {
    fun findLegalTurn(start: GameState): TurnSearchResult = try {
        TurnWalker(nodeBudget, maxOptionalMoves).walk(start) { true }
            ?.let { TurnSearchResult.Found(it) }
            ?: TurnSearchResult.NoLegalTurn
    } catch (_: BudgetExceeded) {
        TurnSearchResult.Inconclusive
    }
}

internal class BudgetExceeded : RuntimeException(null, null, false, false)

/**
 * Walks the ways to complete a turn from a position and reports each legal one (submittable, with
 * no king left capturable).
 *
 * The present boards are tried in every order, because order changes what a move does: a piece
 * sent to another present board advances that timeline if the board hasn't been played yet, but
 * branches a new timeline from it if it has. Up to [maxOptionalMoves] moves on other boards may
 * come at any point. They are only added to a finished turn that would leave a king capturable,
 * unless [extendLegalTurns]. A position reached by different orders is explored once.
 *
 * [order] sorts each position's moves to choose which are tried first.
 * Throws [BudgetExceeded] after expanding [nodeBudget] positions.
 */
internal class TurnWalker(
    private val nodeBudget: Int,
    private val maxOptionalMoves: Int,
    private val extendLegalTurns: Boolean = false,
    private val order: (GameState, List<Move>) -> List<Move> = { _, moves -> moves },
) {
    private var nodes = 0

    /** Positions already explored, with the most optional moves they had left then. */
    private val seen = HashMap<Set<List<Any?>>, Int>()

    /** Legal turns already reported. */
    private val reported = HashSet<Set<List<Any?>>>()

    /**
     * Calls [visit] with each legal turn (the state after its moves) until it returns true, and
     * returns that state, or null once every turn has been visited.
     */
    fun walk(start: GameState, visit: (GameState) -> Boolean): GameState? = walk(start, maxOptionalMoves, visit)

    private fun walk(state: GameState, optionalLeft: Int, visit: (GameState) -> Boolean): GameState? {
        val key = state.turnKey()
        if ((seen[key] ?: -1) >= optionalLeft) return null
        seen[key] = optionalLeft
        if (++nodes > nodeBudget) throw BudgetExceeded()

        val mandatory = state.mandatoryTimelines
        if (mandatory.isEmpty() && state.canSubmit) {
            val legal = !state.isInCheck(state.sideToMove)
            if (legal && reported.add(key) && visit(state)) return state
            if (legal && !extendLegalTurns) return null
        }
        if (mandatory.isEmpty() && optionalLeft == 0) return null
        val required = mandatory.associateWith { state.timelines.getValue(it).end }
        val (advancing, optional) = state.allMoves().partition { move ->
            move.from.l in required || required[move.to.l] == move.to.t
        }
        for (move in order(state, advancing)) {
            walk(state.apply(move), optionalLeft, visit)?.let { return it }
        }
        if (optionalLeft > 0) {
            for (move in order(state, optional)) {
                walk(state.apply(move), optionalLeft - 1, visit)?.let { return it }
            }
        }
        return null
    }

    /**
     * Identifies a position within a turn. Each timeline gains at most one board per turn, so its
     * span and latest board determine it.
     */
    private fun GameState.turnKey(): Set<List<Any?>> =
        timelines.values.mapTo(HashSet()) { listOf(it.index, it.start, it.end, it.parent, it.last) }
}
