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
    /**
     * A quick depth-first search usually finds a legal turn within a few positions. If it can't
     * settle the question, a thorough search follows that checks every move at each position before
     * going deeper, and carries the moves found losing down to the positions below.
     */
    fun findLegalTurn(start: GameState): TurnSearchResult {
        val quick = search(start, minOf(nodeBudget, QUICK_BUDGET), thorough = false)
        if (quick != TurnSearchResult.Inconclusive || nodeBudget <= QUICK_BUDGET) return quick
        if (start.atTimelineLimit(start.sideToMove) && start.turnMoves.isEmpty()) {
            return try {
                FixedBoardsSearch(nodeBudget, stop).find(start)?.let { TurnSearchResult.Found(it) } ?: TurnSearchResult.NoLegalTurn
            } catch (_: BudgetExceeded) {
                TurnSearchResult.Inconclusive
            }
        }
        return search(start, nodeBudget, thorough = true)
    }

    private fun search(start: GameState, budget: Int, thorough: Boolean): TurnSearchResult = try {
        TurnWalker(budget, maxOptionalMoves, onlyUsefulOptional = true, inheritRefutations = thorough, stop = stop)
            .walk(start) { true }
            ?.let { TurnSearchResult.Found(it) }
            ?: TurnSearchResult.NoLegalTurn
    } catch (_: BudgetExceeded) {
        TurnSearchResult.Inconclusive
    }

    private companion object {
        const val QUICK_BUDGET = 2_000
    }
}

internal class BudgetExceeded : RuntimeException(null, null, false, false)

/**
 * Finds a legal turn when the side to move can't create timelines (it has reached the
 * [GameState.timelineLimit]). Then order no longer matters: landing on a board that was already
 * played would branch, so such moves simply aren't available, and a move elsewhere can't help (its
 * only use would be to enable a branch). A legal turn is just one move playing each present board
 * (from it, or jumping onto it), with no royal piece left capturable.
 *
 * That makes it a constraint problem, solved by playing the board with the fewest safe moves
 * first, rechecking the others after each move, and backing off as soon as one has none left.
 * Moves found losing stay losing below (threats only grow), so they're never checked twice.
 * Every move checked counts towards [nodeBudget].
 */
internal class FixedBoardsSearch(private val nodeBudget: Int, private val stop: () -> Boolean) {
    private var nodes = 0
    /** Sets of moves already explored: here the same moves in any order give the same position. */
    private val seen = HashSet<Set<Move>>()

    /** A legal turn from [start] (at the beginning of a turn), or null if there is none. */
    fun find(start: GameState): GameState? = search(start, start.mandatoryTimelines.toSet(), emptySet())

    private fun search(state: GameState, open: Set<Int>, refutedAbove: Set<Move>): GameState? {
        if (open.isEmpty()) return state
        if (seen.size < MAX_REMEMBERED && !seen.add(state.turnMoves.toSet())) return null
        val all = state.allMoves()
        val refuted = HashSet<Move>(refutedAbove)
        val safe = HashSet<Move>()
        // For each board still to play, the moves that play it without leaving a royal piece capturable.
        val options = open.associateWith { l ->
            val end = state.timelines.getValue(l).end
            all.filter { move -> (move.from.l == l || (move.to.l == l && move.to.t == end && !move.isSpatial)) && !move.branches(state) }
                .filter { move ->
                    when (move) {
                        in safe -> true
                        in refuted -> false
                        else -> isSafe(state, move).also { if (it) safe += move else refuted += move }
                    }
                }
        }
        val moves = options.values.minBy { it.size }
        if (moves.isEmpty()) return null
        for (move in moves) {
            val played = setOf(move.from.l, move.to.l)
            search(state.apply(move), open - played, refuted.filterTo(HashSet()) { it.from.l !in played && it.to.l !in played })
                ?.let { return it }
        }
        return null
    }

    private fun isSafe(state: GameState, move: Move): Boolean {
        if (++nodes > nodeBudget || stop()) throw BudgetExceeded()
        return !state.apply(move).isInCheck(state.sideToMove)
    }
}

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
 * With [onlyUsefulOptional], for finding whether any legal turn exists, moves on other boards are
 * only tried if they could make one possible. Such a move can't change which boards must be played
 * (unless it branches), can't remove a threat, and can't open a path for a later move, since the
 * mover's pieces only travel through boards with the mover to move. What it can do is branch a new
 * timeline, which may pull the present back; or leave behind a timeline's latest board that
 * another move lands on, which then branches instead of continuing that timeline. Other moves
 * elsewhere are skipped.
 *
 * With [inheritRefutations], each position first checks all its moves and only then goes deeper,
 * passing the moves that left a royal piece capturable down to the positions below, which skip
 * them. That's sound because threats only grow as a turn goes on, as long as the move does the same
 * thing below: its destination must still be the same kind of board (an earlier move can turn a
 * timeline's latest board into the past, so landing there branches instead of continuing it), and
 * if it branches, no other branch may come first (that would change its new timeline's number).
 * A board that every move leaves in check then costs one look instead of one per combination of
 * moves on the other boards.
 *
 * [order] sorts each position's moves to choose which are tried first.
 * Throws [BudgetExceeded] after expanding [nodeBudget] positions (each move checked counts as one,
 * in the thorough mode), or once [stop] returns true.
 */
internal class TurnWalker(
    private val nodeBudget: Int,
    private val maxOptionalMoves: Int,
    private val extendLegalTurns: Boolean = false,
    private val onlyUsefulOptional: Boolean = false,
    private val inheritRefutations: Boolean = false,
    private val order: (GameState, List<Move>) -> List<Move> = { _, moves -> moves },
    private val stop: () -> Boolean = { false },
) {
    /** Positions expanded so far. */
    internal var nodes = 0
        private set

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
        return walk(start, maxOptionalMoves, visit, emptySet())
    }

    private fun walk(state: GameState, optionalLeft: Int, visit: (GameState) -> Boolean, refutedAbove: Set<Move>): GameState? {
        val key = state.turnKey()
        if ((seen[key] ?: -1) >= optionalLeft) return null
        // Remembering positions only saves time; past a point, stop to keep memory bounded.
        if (seen.size < MAX_REMEMBERED) seen[key] = optionalLeft
        count()
        // In the thorough mode, the position above already checked this one.
        if (!inheritRefutations && state.turnMoves.isNotEmpty() && state.isInCheck(state.sideToMove)) return null

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
        val tried = when {
            optionalLeft == 0 -> emptyList()
            onlyUsefulOptional -> usefulOptional(state, advancing + optional, optional)
            else -> optional
        }
        if (!inheritRefutations) {
            for (move in order(state, advancing)) {
                walk(state.apply(move), optionalLeft, visit, emptySet())?.let { return it }
            }
            for (move in order(state, tried)) {
                walk(state.apply(move), optionalLeft - 1, visit, emptySet())?.let { return it }
            }
            return null
        }

        // Thorough: find every move that leaves a royal piece capturable before going deeper.
        val refuted = HashSet<Move>()
        fun survivors(moves: List<Move>) = moves.filter { move ->
            val loses = move in refutedAbove || run {
                count()
                state.apply(move).isInCheck(state.sideToMove)
            }
            if (loses) refuted += move
            !loses
        }
        val advancingSurvivors = survivors(order(state, advancing))
        val optionalSurvivors = survivors(order(state, tried))
        for (move in advancingSurvivors) {
            walk(state.apply(move), optionalLeft, visit, stillRefuted(state, refuted, move))?.let { return it }
        }
        for (move in optionalSurvivors) {
            walk(state.apply(move), optionalLeft - 1, visit, stillRefuted(state, refuted, move))?.let { return it }
        }
        return null
    }

    private fun count() {
        if (++nodes > nodeBudget || stop()) throw BudgetExceeded()
    }

    /** The moves of [refuted] that still do the same thing, and so still lose, after [played] in [state]. */
    private fun stillRefuted(state: GameState, refuted: Set<Move>, played: Move): Set<Move> {
        fun continues(move: Move) = move.to.l != move.from.l && state.timelines.getValue(move.to.l).end == move.to.t
        val advanced = if (continues(played)) setOf(played.from.l, played.to.l) else setOf(played.from.l)
        val branched = played.branches(state)
        return refuted.filterTo(HashSet()) { move -> move.to.l !in advanced && !(branched && move.branches(state)) }
    }

    /** The moves in [optional] that could help complete a legal turn (see [onlyUsefulOptional]). */
    private fun usefulOptional(state: GameState, all: List<Move>, optional: List<Move>): List<Move> {
        fun continues(move: Move) = move.to.l != move.from.l && state.timelines.getValue(move.to.l).end == move.to.t

        // For each timeline, the timelines with a move landing on its latest board.
        val landers = HashMap<Int, MutableSet<Int>>()
        for (move in all) if (continues(move)) landers.getOrPut(move.to.l) { HashSet() } += move.from.l
        return optional.filter { move ->
            if (move.branches(state)) return@filter true
            // It advances its own timeline (and a jump, the one it lands on): useful only if a move
            // from a board still playable afterwards lands on a latest board it leaves behind.
            val advanced = if (move.isSpatial) setOf(move.from.l) else setOf(move.from.l, move.to.l)
            advanced.any { l -> landers[l]?.any { it !in advanced } == true }
        }
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
