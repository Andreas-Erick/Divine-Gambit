package com.andreaserick.multiversechess.engine

import kotlin.math.abs
import kotlin.math.sign

/** An immutable 8x8 chess board. */
class Board private constructor(
    private val cells: Array<Piece?>,
    /** Square a pawn skipped over with a double step on the previous half-move, if any. */
    val enPassant: Square?,
) {
    operator fun get(x: Int, y: Int): Piece? = cells[y * BOARD_SIZE + x]

    operator fun get(square: Square): Piece? = get(square.x, square.y)

    /** Returns a copy with [changes] applied and the en passant square set to [enPassant]. */
    fun with(changes: List<Pair<Square, Piece?>>, enPassant: Square? = null): Board {
        val copy = cells.copyOf()
        for ((square, piece) in changes) copy[square.y * BOARD_SIZE + square.x] = piece
        return Board(copy, enPassant)
    }

    /** Calls [action] with the file, rank and piece of each occupied square, without making a list. */
    fun forEachPiece(action: (x: Int, y: Int, piece: Piece) -> Unit) {
        for (i in cells.indices) cells[i]?.let { action(i % BOARD_SIZE, i / BOARD_SIZE, it) }
    }

    fun pieces(): List<Pair<Square, Piece>> = buildList {
        for (i in cells.indices) {
            cells[i]?.let { add(Square(i % BOARD_SIZE, i / BOARD_SIZE) to it) }
        }
    }

    /** Whether a piece of side [by] attacks [target] with an ordinary (same-board) move. */
    fun isAttackedSpatially(target: Square, by: Side): Boolean {
        for ((from, piece) in pieces()) {
            if (piece.side != by) continue
            val dx = target.x - from.x
            val dy = target.y - from.y
            if (dx == 0 && dy == 0) continue
            val attacks = when (piece.type) {
                PieceType.PAWN, PieceType.BRAWN -> dy == by.rankForward && abs(dx) == 1
                PieceType.KNIGHT -> (abs(dx) == 1 && abs(dy) == 2) || (abs(dx) == 2 && abs(dy) == 1)
                PieceType.KING, PieceType.COMMON_KING -> abs(dx) <= 1 && abs(dy) <= 1
                PieceType.ROOK -> (dx == 0 || dy == 0) && pathClear(from, dx, dy)
                PieceType.BISHOP -> abs(dx) == abs(dy) && pathClear(from, dx, dy)
                PieceType.QUEEN, PieceType.PRINCESS, PieceType.ROYAL_QUEEN ->
                    (dx == 0 || dy == 0 || abs(dx) == abs(dy)) && pathClear(from, dx, dy)
                // They always change time or timeline, so they never attack their own board.
                PieceType.UNICORN, PieceType.DRAGON -> false
            }
            if (attacks) return true
        }
        return false
    }

    private fun pathClear(from: Square, dx: Int, dy: Int): Boolean {
        val sx = dx.sign
        val sy = dy.sign
        val steps = maxOf(abs(dx), abs(dy))
        for (k in 1 until steps) {
            if (get(from.x + sx * k, from.y + sy * k) != null) return false
        }
        return true
    }

    /** A 64-bit fingerprint of the board (FNV-1a over its squares), to tell positions apart cheaply. */
    internal fun fingerprint(): Long {
        var h = -3750763034362895579L
        fun mix(v: Int) {
            h = (h xor v.toLong()) * 1099511628211L
        }
        for (piece in cells) mix(if (piece == null) 0 else (piece.type.ordinal + 1) * 4 + piece.side.ordinal * 2 + if (piece.moved) 1 else 0)
        mix(enPassant?.let { it.y * BOARD_SIZE + it.x + 1 } ?: 0)
        return h
    }

    override fun equals(other: Any?): Boolean =
        other is Board && cells.contentEquals(other.cells) && enPassant == other.enPassant

    override fun hashCode(): Int = 31 * cells.contentHashCode() + (enPassant?.hashCode() ?: 0)

    override fun toString(): String = (BOARD_SIZE - 1 downTo 0).joinToString("\n") { y ->
        (0 until BOARD_SIZE).joinToString("") { x ->
            val p = get(x, y)
            when {
                p == null -> "."
                p.side == Side.WHITE -> p.type.letter.toString()
                else -> p.type.letter.lowercase()
            }
        }
    }

    companion object {
        fun standard(): Board = Variant.STANDARD.board()

        /**
         * Builds a board from eight rows listed from rank 8 down to rank 1. Upper case is White,
         * lower case is Black and `.` is empty (see [PieceType.letter]). Pawns and brawns off their
         * starting rank are marked as moved.
         */
        fun parse(vararg rows: String, enPassant: Square? = null): Board {
            require(rows.size == BOARD_SIZE && rows.all { it.length == BOARD_SIZE }) { "Expected 8 rows of 8" }
            val cells = arrayOfNulls<Piece>(BOARD_SIZE * BOARD_SIZE)
            rows.forEachIndexed { r, row ->
                val y = BOARD_SIZE - 1 - r
                row.forEachIndexed { x, c ->
                    if (c != '.') {
                        val side = if (c.isUpperCase()) Side.WHITE else Side.BLACK
                        val type = PieceType.entries.first { it.letter == c.uppercaseChar() }
                        val pawnHome = if (side == Side.WHITE) 1 else BOARD_SIZE - 2
                        cells[y * BOARD_SIZE + x] = Piece(type, side, moved = type.isPawnLike && y != pawnHome)
                    }
                }
            }
            return Board(cells, enPassant)
        }
    }
}
