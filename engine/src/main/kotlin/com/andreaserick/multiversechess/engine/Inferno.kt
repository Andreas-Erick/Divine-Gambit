package com.andreaserick.multiversechess.engine

import kotlin.math.abs

/**
 * A piece taken in a game played with the Inferno rule ([GameMode.inferno]). Its owner wins it
 * back by solving [trials] puzzles; once [freed], it has returned to the board.
 */
data class Soul(val type: PieceType, val side: Side, val freed: Boolean = false) {
    val trials: Int get() = Inferno.trialsFor(type)
}

/**
 * The Inferno: taken pieces go there, and their owner can win them back by solving puzzles while
 * the game goes on. A freed piece returns at the start of its owner's turn, on a square where it
 * can't be taken straight away.
 */
object Inferno {
    /** How many puzzles win back a piece of [type]: the more it's worth, the more. */
    fun trialsFor(type: PieceType): Int = when {
        type.isPawnLike -> 1
        type.value >= 9 -> 4
        type.value >= 5 -> 3
        else -> 2
    }

    /**
     * Where [piece], of the side to move, returns to the board: an empty square on a board that
     * side plays now, in the present if possible, where the opponent couldn't take it if the
     * turn were passed, and from which it doesn't already attack an enemy royal piece. Squares
     * nearest the side's home ranks, then the middle files, come first. Null if there's none.
     */
    fun returnSquare(state: GameState, piece: Piece): Pos? {
        val side = piece.side
        if (state.sideToMove != side) return null
        val boards = (state.mandatoryTimelines + state.playableTimelines(side).map { it.index }).distinct()
        val squares = (0 until BOARD_SIZE).flatMap { x -> (0 until BOARD_SIZE).map { y -> Square(x, y) } }
            .filter { !piece.type.isPawnLike || (it.y != 0 && it.y != BOARD_SIZE - 1) }
            .sortedWith(compareBy({ abs(it.y - side.homeRank) }, { abs(it.x * 2 - (BOARD_SIZE - 1)) }, { it.x }))
        for (l in boards) {
            val t = state.timelines.getValue(l).end
            val board = state.timelines.getValue(l).last
            for (square in squares) {
                if (board[square] != null) continue
                val pos = Pos(l, t, square.x, square.y)
                if (isSafe(state.withPiece(pos, piece), pos)) return pos
            }
        }
        return null
    }

    private fun isSafe(placed: GameState, pos: Pos): Boolean {
        val side = placed.sideToMove
        val attacksRoyal = placed.generateMoves(pos, capturesOnly = true).any { placed.pieceAt(it.to)?.type?.isRoyal == true }
        if (attacksRoyal) return false
        // The side's boards as they'd be if it passed, so the opponent's reply can be seen.
        val passed = placed.timelines.toMutableMap()
        for (timeline in placed.playableTimelines(side)) passed[timeline.index] = timeline.appended(timeline.last.with(emptyList()))
        val after = GameState(passed, side, placed.turnMoves, placed.timelineLimit)
        val target = Pos(pos.l, pos.t + 1, pos.x, pos.y)
        return after.captures(side.opponent).none { it.to == target }
    }
}
