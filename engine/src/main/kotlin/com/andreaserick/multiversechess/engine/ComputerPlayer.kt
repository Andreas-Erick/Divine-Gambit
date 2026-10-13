package com.andreaserick.multiversechess.engine

import kotlin.random.Random

/**
 * A simple computer opponent. It enumerates legal turns (capped by [nodeBudget] and
 * [maxCandidates]) and picks the one with the best material balance after accounting for the
 * opponent's best immediate capture. It prefers not to create new timelines needlessly.
 */
class ComputerPlayer(
    private val random: Random = Random.Default,
    private val nodeBudget: Int = 3_000,
    private val maxCandidates: Int = 250,
) {
    /** Moves for the side to move in [state], or null if it has no legal turn. */
    fun chooseTurn(state: GameState): List<Move>? {
        val me = state.sideToMove
        var best: GameState? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var nodes = 0
        var candidates = 0

        fun exhausted() = nodes >= nodeBudget || candidates >= maxCandidates

        fun consider(candidate: GameState) {
            candidates++
            val score = evaluate(candidate, me, state) + random.nextDouble() * 0.05
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        fun search(s: GameState, optionalLeft: Int) {
            if (exhausted()) return
            nodes++
            val mandatory = s.mandatoryTimelines
            if (mandatory.isEmpty()) {
                if (!s.isInCheck(me)) {
                    consider(s)
                } else if (optionalLeft > 0) {
                    for (move in ordered(s, s.allMoves())) {
                        search(s.apply(move), optionalLeft - 1)
                        if (exhausted()) return
                    }
                }
                return
            }
            for (move in ordered(s, s.movesAdvancing(mandatory.first()))) {
                search(s.apply(move), optionalLeft)
                if (exhausted()) return
            }
        }

        search(state, optionalLeft = 1)
        best?.let { return it.turnMoves }
        val fallback = TurnSearch(nodeBudget * 5).findLegalTurn(state)
        return (fallback as? TurnSearchResult.Found)?.result?.turnMoves
    }

    /** Captures of valuable pieces first, otherwise random order. */
    private fun ordered(state: GameState, moves: List<Move>): List<Move> =
        moves.shuffled(random).sortedByDescending { state.pieceAt(it.to)?.type?.value ?: 0 }

    private fun evaluate(candidate: GameState, me: Side, before: GameState): Double {
        val after = candidate.submit()
        var score = material(after, me).toDouble()
        score -= 0.7 * bestCaptureValue(after, me.opponent)
        val newTimelines = after.timelines.size - before.timelines.size
        score -= 0.4 * newTimelines
        if (after.threats().isNotEmpty()) {
            score += 0.3
            if (TurnSearch(nodeBudget = 300).findLegalTurn(after) == TurnSearchResult.NoLegalTurn) score += 1_000.0
        }
        return score
    }

    /** Material on the latest board of every timeline, from [me]'s point of view. */
    private fun material(state: GameState, me: Side): Int {
        var total = 0
        for (timeline in state.timelines.values) {
            for ((_, piece) in timeline.last.pieces()) {
                total += if (piece.side == me) piece.type.value else -piece.type.value
            }
        }
        return total
    }

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
}
