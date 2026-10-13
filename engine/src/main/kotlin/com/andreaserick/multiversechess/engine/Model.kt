package com.andreaserick.multiversechess.engine

const val BOARD_SIZE = 8

enum class Side {
    WHITE, BLACK;

    val opponent: Side get() = if (this == WHITE) BLACK else WHITE

    /** Direction along the rank axis in which this side's pawns advance. */
    val rankForward: Int get() = if (this == WHITE) 1 else -1

    /**
     * Direction along the timeline axis in which this side's pawns advance.
     *
     * White creates timelines with positive indices and Black with negative ones,
     * so "forward" for a pawn points toward the timelines the opponent creates.
     */
    val timelineForward: Int get() = if (this == WHITE) -1 else 1

    /** Rank the king and rooks start on. */
    val homeRank: Int get() = if (this == WHITE) 0 else BOARD_SIZE - 1

    /** Rank a pawn promotes on. */
    val promotionRank: Int get() = if (this == WHITE) BOARD_SIZE - 1 else 0

    val displayName: String get() = if (this == WHITE) "White" else "Black"

    companion object {
        /** The side that moves on a board at half-move index [t]. */
        fun toMoveAt(t: Int): Side = if (Math.floorMod(t, 2) == 0) WHITE else BLACK
    }
}

/**
 * [letter] is the piece's letter in notation and in [Board.parse] (upper case White, lower case Black).
 * The last four are fairy pieces used by some variants; their letters follow 5D chess notation.
 */
enum class PieceType(val letter: Char, val value: Int, val displayName: String) {
    KING('K', 0, "King"),
    QUEEN('Q', 9, "Queen"),
    ROOK('R', 5, "Rook"),
    BISHOP('B', 3, "Bishop"),
    KNIGHT('N', 3, "Knight"),
    PAWN('P', 1, "Pawn"),

    /** Rook plus bishop: any distance along one or two dimensions. */
    PRINCESS('S', 7, "Princess"),

    /** Equal distance along exactly three dimensions, so it always leaves its board. */
    UNICORN('U', 3, "Unicorn"),

    /** Equal distance along all four dimensions. */
    DRAGON('D', 2, "Dragon"),

    /** A pawn that can also capture diagonally between a forward axis and any other dimension. */
    BRAWN('W', 2, "Brawn");

    /** Moves forward, double-steps, captures en passant and promotes like a pawn. */
    val isPawnLike: Boolean get() = this == PAWN || this == BRAWN

    val isFairy: Boolean get() = ordinal > PAWN.ordinal
}

data class Piece(val type: PieceType, val side: Side, val moved: Boolean = false)

data class Square(val x: Int, val y: Int) {
    override fun toString(): String = "${'a' + x}${y + 1}"

    companion object {
        fun parse(name: String): Square = Square(name[0] - 'a', name[1] - '1')

        fun isOnBoard(x: Int, y: Int): Boolean = x in 0 until BOARD_SIZE && y in 0 until BOARD_SIZE
    }
}

/** Identifies one board in the multiverse: timeline [l] at half-move [t]. */
data class BoardId(val l: Int, val t: Int)

/**
 * A point in the multiverse: timeline [l], half-move [t], file [x] and rank [y].
 *
 * Half-move `t` is even on boards where White is to move and odd where Black is.
 * One step along the "time" axis for a piece is a full turn, i.e. two half-moves.
 */
data class Pos(val l: Int, val t: Int, val x: Int, val y: Int) {
    val square: Square get() = Square(x, y)
    val boardId: BoardId get() = BoardId(l, t)

    companion object {
        fun of(l: Int, t: Int, square: String): Pos = Square.parse(square).let { Pos(l, t, it.x, it.y) }
    }
}

data class Move(
    val from: Pos,
    val to: Pos,
    val promotion: PieceType? = null,
    /** Rook start and end squares when this move is a castle. */
    val castleRook: Pair<Square, Square>? = null,
    /** Square of the pawn captured en passant. */
    val enPassantCapture: Square? = null,
    val doubleStep: Boolean = false,
) {
    /** True when the piece stays on its own board (ordinary chess move). */
    val isSpatial: Boolean get() = from.l == to.l && from.t == to.t
}

sealed interface Outcome {
    data object Ongoing : Outcome
    data class Checkmate(val winner: Side) : Outcome
    data object Stalemate : Outcome
    data class Resignation(val winner: Side) : Outcome
}
