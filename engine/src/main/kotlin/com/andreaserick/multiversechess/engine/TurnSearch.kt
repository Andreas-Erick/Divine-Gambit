package com.andreaserick.multiversechess.engine

sealed interface TurnSearchResult {
    /** [result] is the state after the legal turn's moves, ready to submit. */
    data class Found(val result: GameState) : TurnSearchResult
    data object NoLegalTurn : TurnSearchResult
    data object Inconclusive : TurnSearchResult
}

/**
 * Looks for any legal turn: one move on (or onto) each present board, plus up to
 * [maxOptionalMoves] extra moves elsewhere if the king still isn't safe.
 *
 * A full turn can combine many moves across many boards, so the search is capped at
 * [nodeBudget] positions and reports [TurnSearchResult.Inconclusive] if it runs out.
 */
class TurnSearch(private val nodeBudget: Int = 4_000, private val maxOptionalMoves: Int = 1) {
    private var nodes = 0

    fun findLegalTurn(start: GameState): TurnSearchResult {
        nodes = 0
        return try {
            search(start, maxOptionalMoves)?.let { TurnSearchResult.Found(it) } ?: TurnSearchResult.NoLegalTurn
        } catch (_: BudgetExceeded) {
            TurnSearchResult.Inconclusive
        }
    }

    private fun search(state: GameState, optionalLeft: Int): GameState? {
        if (++nodes > nodeBudget) throw BudgetExceeded()
        val mandatory = state.mandatoryTimelines
        if (mandatory.isEmpty()) {
            if (state.canSubmit && !state.isInCheck(state.sideToMove)) return state
            if (optionalLeft == 0) return null
            for (move in state.allMoves()) {
                search(state.apply(move), optionalLeft - 1)?.let { return it }
            }
            return null
        }
        for (move in state.movesAdvancing(mandatory.first())) {
            search(state.apply(move), optionalLeft)?.let { return it }
        }
        return null
    }

    private class BudgetExceeded : RuntimeException(null, null, false, false)
}
