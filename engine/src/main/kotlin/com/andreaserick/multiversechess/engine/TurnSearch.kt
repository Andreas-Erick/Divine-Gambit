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
 * [nodeBudget] positions and reports [TurnSearchResult.Inconclusive] if it runs out, or if
 * [stop] returns true (checked at every position, so a long search can be cancelled).
 */
class TurnSearch(
    private val nodeBudget: Int = 4_000,
    private val maxOptionalMoves: Int = 1,
    private val stop: () -> Boolean = { false },
) {
    fun findLegalTurn(start: GameState): TurnSearchResult = try {
        TurnWalker(nodeBudget, maxOptionalMoves, stop = stop).walk(start) { true }
            ?.let { TurnSearchResult.Found(it) }
            ?: TurnSearchResult.NoLegalTurn
    } catch (_: BudgetExceeded) {
        TurnSearchResult.Inconclusive
    }
}

internal class BudgetExceeded : RuntimeException(null, null, false, false)

/** Positions a [TurnWalker] remembers at most, so even an unlimited search has bounded memory. */
private const val MAX_REMEMBERED = 200_000

/**
 * Walks the ways to complete a turn from a position and reports each legal one (submittable, with
 * no king left capturable).
 *
 * The present boards are tried in every order, because order changes what a move does: a piece
 * sent to another present board advances that timeline if the board hasn't been played yet, but
 * branches a new timeline from it if it has. Up to [maxOptionalMoves] moves on other boards may
 * come at any point, but aren't added to a finished legal turn unless [extendLegalTurns]. A
 * position reached by different orders is explored once.
 *
 * A partial turn that already leaves a royal piece capturable is abandoned: further moves can't
 * help. A threat comes from a board the opponent can play, which this turn can't touch, against a
 * board that already exists, which can't change; and new boards only ever extend a line of attack,
 * since before they existed it ended there.
 *
 * [order] sorts each position's moves to choose which are tried first.
 * Throws [BudgetExceeded] after expanding [nodeBudget] positions, or once [stop] returns true.
 */
internal class TurnWalker(
    private val nodeBudget: Int,
    private val maxOptionalMoves: Int,
    private val extendLegalTurns: Boolean = false,
    private val order: (GameState, List<Move>) -> List<Move> = { _, moves -> moves },
    private val stop: () -> Boolean = { false },
) {
    private var nodes = 0

    /** Positions already explored, with the most optional moves they had left then. */
    private val seen = HashMap<List<Long>, Int>()

    /** Legal turns already reported. */
    private val reported = HashSet<List<Long>>()

    /** The timelines at the start of the turn. */
    private var origin: Map<Int, Timeline> = emptyMap()

    /**
     * Calls [visit] with each legal turn (the state after its moves) until it returns true, and
     * returns that state, or null once every turn has been visited.
     */
    fun walk(start: GameState, visit: (GameState) -> Boolean): GameState? {
        origin = start.timelines
        return walk(start, maxOptionalMoves, visit)
    }

    private fun walk(state: GameState, optionalLeft: Int, visit: (GameState) -> Boolean): GameState? {
        val key = state.turnKey()
        if ((seen[key] ?: -1) >= optionalLeft) return null
        // Remembering positions only saves time; past a point, stop to keep memory bounded.
        if (seen.size < MAX_REMEMBERED) seen[key] = optionalLeft
        if (++nodes > nodeBudget || stop()) throw BudgetExceeded()
        if (state.turnMoves.isNotEmpty() && state.isInCheck(state.sideToMove)) return null

        val mandatory = state.mandatoryTimelines
        if (mandatory.isEmpty() && state.canSubmit) {
            if (reported.add(key) && visit(state)) return state
            if (!extendLegalTurns) return null
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
     * Identifies a position within a turn by the timelines its moves have touched: each gains at
     * most one board per turn, so its index, end and latest board determine it. Boards are kept
     * as fingerprints, so remembering many positions stays cheap however big the multiverse is.
     */
    private fun GameState.turnKey(): List<Long> = buildList {
        for (l in timelineIndices) {
            val timeline = timelines.getValue(l)
            if (origin[l] === timeline) continue
            add(l.toLong())
            add(timeline.end.toLong())
            add(timeline.last.fingerprint())
        }
    }
}
