package com.andreaserick.multiversechess.engine

import kotlin.random.Random

/**
 * The computer opponent. It looks two turns ahead: it collects up to [candidates] legal turns
 * (searching at most [nodeBudget] positions), scores each, and for the [deepened] most promising
 * assumes the opponent answers with whichever of up to [replies] turns is worst for it. It plays a
 * checkmate whenever it sees one, and steers clear of turns that let the opponent mate it. It
 * stops looking after about [thinkMillis] and plays the best turn found by then.
 */
class ComputerPlayer(
    private val random: Random = Random.Default,
    private val thinkMillis: Long = 2_000,
    private val candidates: Int = 120,
    private val nodeBudget: Int = 4_000,
    private val deepened: Int = 8,
    private val replies: Int = 200,
    private val replyBudget: Int = 4_000,
) {
    private class Candidate(val moves: List<Move>, val after: GameState, val score: Double)

    /**
     * Moves for the side to move in [state], or null if it found no legal turn (there may be none).
     * [stop] cancels a long search; the result is then meaningless.
     */
    fun chooseTurn(state: GameState, stop: () -> Boolean = { false }): List<Move>? {
        val me = state.sideToMove
        val deadline = System.nanoTime() + thinkMillis * 1_000_000
        val late = { stop() || System.nanoTime() > deadline }
        val found = ArrayList<Candidate>()
        try {
            TurnWalker(nodeBudget, maxOptionalMoves = 1, order = ::ordered, stop = late).walk(state) { turn ->
                val after = turn.submit()
                val branches = after.timelines.size - state.timelines.size
                found += Candidate(turn.turnMoves, after, score(after, me) - BRANCH_COST * branches)
                found.size >= candidates
            }
        } catch (_: BudgetExceeded) {
            // Work with the turns found so far.
        }
        if (found.isEmpty()) {
            // Nothing within the budget: look much harder for any legal turn at all.
            val fallback = TurnSearch(FALLBACK_BUDGET, stop = stop).findLegalTurn(state)
            return (fallback as? TurnSearchResult.Found)?.result?.turnMoves
        }

        // Take any checkmate on offer.
        for (candidate in found) {
            if (late()) break
            if (candidate.after.threats().isNotEmpty() && isMated(candidate.after, MATE_BUDGET, late)) return candidate.moves
        }

        // Look at the opponent's best answer to the most promising turns, and beyond them for as
        // long as every turn looked at so far lets the opponent mate.
        var best = found.maxBy { it.score }
        var bestScore = Double.NEGATIVE_INFINITY
        for ((i, candidate) in found.sortedByDescending { it.score }.withIndex()) {
            if (late() || (i >= deepened && bestScore > -MATE / 2)) break
            val score = worstReply(candidate.after, me, late) - BRANCH_COST * (candidate.after.timelines.size - state.timelines.size) +
                random.nextDouble() * 0.05
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best.moves
    }

    /**
     * How good [after] (the opponent to move) is for [me] once the opponent has answered as well
     * as it can, among the replies looked at.
     */
    private fun worstReply(after: GameState, me: Side, stop: () -> Boolean): Double {
        var worst = Double.POSITIVE_INFINITY
        var seen = 0
        try {
            TurnWalker(replyBudget, maxOptionalMoves = 1, order = ::ordered, stop = stop).walk(after) { reply ->
                val back = reply.submit()
                val score = if (back.threats().isNotEmpty() && isMated(back, REPLY_MATE_BUDGET, stop)) -MATE else score(back, me)
                if (score < worst) worst = score
                ++seen >= replies
            }
        } catch (_: BudgetExceeded) {
            // Judge by the replies seen.
        }
        if (seen > 0) return worst
        // The opponent has no reply in sight: checkmate, stalemate, or just a hard position to search.
        return when (TurnSearch(replyBudget, stop = stop).findLegalTurn(after)) {
            TurnSearchResult.NoLegalTurn -> if (after.threats().isNotEmpty()) MATE else 0.0
            else -> score(after, me)
        }
    }

    /** Whether the side to move in [state] has no legal turn, as far as [budget] positions (and [stop]) allow. */
    private fun isMated(state: GameState, budget: Int, stop: () -> Boolean): Boolean =
        TurnSearch(budget, stop = stop).findLegalTurn(state) == TurnSearchResult.NoLegalTurn

    /**
     * How good [state] (at the start of a turn) is for [me]: material and position on the
     * timelines' latest boards, averaged so that copying a board into a new timeline gains nothing
     * by itself, plus the best capture available to the side to move.
     */
    private fun score(state: GameState, me: Side): Double {
        var total = 0.0
        var weights = 0.0
        for (timeline in state.timelines.values) {
            val weight = if (state.isActive(timeline.index)) 1.0 else 0.3
            weights += weight
            for ((square, piece) in timeline.last.pieces()) {
                val value = piece.type.value + positional(piece, square)
                total += weight * if (piece.side == me) value else -value
            }
        }
        val mover = state.sideToMove
        val capture = bestCaptureValue(state, mover)
        var score = total / weights + if (mover == me) CAPTURE_HOPE * capture else -CAPTURE_FEAR * capture
        if (mover != me && state.threats().isNotEmpty()) score += CHECK_BONUS
        return score
    }

    /** A small bonus for pieces near the centre and pawns that have advanced. */
    private fun positional(piece: Piece, square: Square): Double {
        val centre = 3.5 - maxOf(kotlin.math.abs(square.x - 3.5), kotlin.math.abs(square.y - 3.5))
        return when {
            piece.type.isPawnLike -> 0.05 * kotlin.math.abs(square.y - (BOARD_SIZE - 1 - piece.side.promotionRank))
            piece.type == PieceType.KING -> 0.0
            else -> 0.04 * centre
        }
    }

    /** Captures of valuable pieces first, otherwise random order. */
    private fun ordered(state: GameState, moves: List<Move>): List<Move> =
        moves.shuffled(random).sortedByDescending { state.pieceAt(it.to)?.type?.value ?: 0 }

    /** Value of the best piece [attacker] can capture on its next turn. */
    private fun bestCaptureValue(state: GameState, attacker: Side): Int {
        var best = 0
        for (timeline in state.playableTimelines(attacker)) {
            for ((square, piece) in timeline.last.pieces()) {
                if (piece.side != attacker) continue
                for (move in state.generateMoves(Pos(timeline.index, timeline.end, square.x, square.y), capturesOnly = true)) {
                    val value = state.pieceAt(move.to)?.type?.value ?: 1
                    if (value > best) best = value
                }
            }
        }
        return best
    }

    private companion object {
        const val MATE = 1_000.0
        const val FALLBACK_BUDGET = 200_000
        const val MATE_BUDGET = 5_000
        const val REPLY_MATE_BUDGET = 3_000

        /** Each new timeline makes the game harder to keep track of, so branching needs a reason. */
        const val BRANCH_COST = 0.3

        /** The side to move will probably take something, but not certainly. */
        const val CAPTURE_HOPE = 0.5
        const val CAPTURE_FEAR = 0.7
        const val CHECK_BONUS = 0.3
    }
}
