package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plays an ordinary move on the latest board of timeline [l]. */
fun GameState.move(from: String, to: String, l: Int = 0): GameState {
    val t = timelines.getValue(l).end
    return travel(Pos.of(l, t, from), Pos.of(l, t, to))
}

/** Plays any move between two points in the multiverse. */
fun GameState.travel(from: Pos, to: Pos): GameState {
    val move = legalMovesFrom(from).singleOrNull { it.to == to } ?: error("Illegal: $from -> $to")
    return apply(move)
}

/** Plays a sequence of ordinary single-timeline turns given as "e2e4" strings. */
fun GameState.turns(vararg moves: String): GameState =
    moves.fold(this) { s, m -> s.move(m.substring(0, 2), m.substring(2, 4)).submit() }

class GameStateTest {
    @Test
    fun `opening position has twenty moves`() {
        val state = GameState.initial()
        assertEquals(20, state.allMoves().size)
        assertEquals(0, state.present)
        assertEquals(listOf(0), state.mandatoryTimelines)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `ordinary move advances the timeline and passes the turn`() {
        val afterMove = GameState.initial().move("e2", "e4")
        assertTrue(afterMove.canSubmit)
        val state = afterMove.submit()
        assertEquals(Side.BLACK, state.sideToMove)
        assertEquals(1, state.present)
        val board = state.boardAt(0, 1)!!
        assertEquals(PieceType.PAWN, board[Square.parse("e4")]?.type)
        assertNull(board[Square.parse("e2")])
        assertEquals(Square.parse("e3"), board.enPassant)
        assertEquals(20, state.allMoves().size)
    }

    @Test
    fun `knight travelling back in time creates a new active timeline`() {
        val start = GameState.initial().turns("e2e4", "e7e5")
        assertEquals(2, start.present)
        // Ng1 goes one turn back (to the board at half-move 0) and two ranks forward: g3.
        val state = start.travel(Pos.of(0, 2, "g1"), Pos.of(0, 0, "g3"))

        assertEquals(setOf(0, 1), state.timelines.keys)
        val branch = state.timelines.getValue(1)
        assertEquals(1, branch.start)
        assertEquals(0, branch.parent)
        assertEquals(PieceType.KNIGHT, branch.last[Square.parse("g3")]?.type)
        assertNull(state.boardAt(0, 3)!![Square.parse("g1")])
        assertTrue(state.isActive(1))
        // The present moved back to the new timeline, where Black is to move.
        assertEquals(1, state.present)
        assertTrue(state.canSubmit)
        assertEquals(listOf(1), state.submit().mandatoryTimelines)
    }

    @Test
    fun `a second timeline by the same player is inactive`() {
        val branched = GameState.initial().turns("e2e4", "e7e5")
            .travel(Pos.of(0, 2, "g1"), Pos.of(0, 0, "g3")).submit()
        val white = branched.move("a7", "a6", l = 1).submit()
        assertEquals(listOf(1), white.mandatoryTimelines)

        // The g3 knight on timeline +1 jumps to the past of timeline 0, creating timeline +2.
        val state = white.travel(Pos.of(1, 2, "g3"), Pos.of(0, 2, "g5"))
        assertEquals(setOf(0, 1, 2), state.timelines.keys)
        assertFalse(state.isActive(2))
        // The inactive timeline doesn't hold back the present.
        assertEquals(3, state.present)
        assertTrue(state.canSubmit)
    }

    @Test
    fun `jumping to the end of another timeline advances both without branching`() {
        val state = GameState(
            mapOf(
                0 to Timeline(0, 0, listOf(Board.standard())),
                1 to Timeline(1, 0, listOf(Board.standard()), parent = 0),
            ),
            Side.WHITE,
        )
        assertEquals(listOf(0, 1), state.mandatoryTimelines)
        val jumped = state.travel(Pos.of(1, 0, "g1"), Pos.of(0, 0, "g3"))
        assertEquals(setOf(0, 1), jumped.timelines.keys)
        assertEquals(1, jumped.timelines.getValue(0).end)
        assertEquals(1, jumped.timelines.getValue(1).end)
        assertEquals(PieceType.KNIGHT, jumped.boardAt(0, 1)!![Square.parse("g3")]?.type)
        assertNull(jumped.boardAt(1, 1)!![Square.parse("g1")])
        // One move played both present boards.
        assertTrue(jumped.canSubmit)
    }

    @Test
    fun `fools mate is escaped by the king travelling back in time`() {
        val state = GameState.initial().turns("f2f3", "e7e5", "g2g4", "d8h4")
        assertTrue(state.threats().isNotEmpty())
        // One turn ago f2 was empty and the queen was still on d8: the king can hide there.
        val escaped = state.travel(Pos.of(0, 4, "e1"), Pos.of(0, 2, "f2"))
        assertTrue(escaped.canSubmit)
        assertFalse(escaped.isInCheck(Side.WHITE))
        assertEquals(Outcome.Ongoing, state.evaluateOutcome())
    }

    @Test
    fun `back rank mate with no history is checkmate`() {
        val board = Board.parse(
            "......k.",
            "........",
            "........",
            "........",
            "........",
            "........",
            "......PP",
            "r......K",
        )
        val state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE)
        assertEquals(Outcome.Checkmate(Side.BLACK), state.evaluateOutcome())
    }

    @Test
    fun `no legal turn without check is stalemate`() {
        val board = Board.parse(
            "........",
            "........",
            "........",
            "........",
            "........",
            "......k.",
            ".....q..",
            ".......K",
        )
        val state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE)
        assertTrue(state.threats().isEmpty())
        assertEquals(Outcome.Stalemate, state.evaluateOutcome())
    }

    @Test
    fun `cannot submit a turn that leaves the king capturable`() {
        val state = GameState.initial().turns("e2e4", "f7f5", "d1h5")
        // Black is in check from Qh5; moving the a-pawn doesn't help.
        assertTrue(state.threats().isNotEmpty())
        val bad = state.move("a7", "a6")
        assertTrue(bad.canSubmit)
        assertTrue(bad.isInCheck(Side.BLACK))
        val good = state.move("g7", "g6")
        assertFalse(good.isInCheck(Side.BLACK))
        assertEquals(Outcome.Ongoing, state.evaluateOutcome())
    }

    @Test
    fun `castling moves king and rook`() {
        val state = GameState.initial().turns("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6")
        val castled = state.move("e1", "g1")
        val board = castled.boardAt(0, castled.timelines.getValue(0).end)!!
        assertEquals(PieceType.KING, board[Square.parse("g1")]?.type)
        assertEquals(PieceType.ROOK, board[Square.parse("f1")]?.type)
        assertNull(board[Square.parse("h1")])
    }

    @Test
    fun `castling through an attacked square is not allowed`() {
        val board = Board.parse(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "......r.",
            "........",
            "....K..R",
        )
        val state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE)
        val kingMoves = state.legalMovesFrom(Pos.of(0, 0, "e1"))
        assertTrue(kingMoves.none { it.castleRook != null })
    }

    @Test
    fun `en passant captures the passed pawn`() {
        val state = GameState.initial().turns("e2e4", "a7a6", "e4e5", "d7d5")
        val captured = state.move("e5", "d6")
        val board = captured.boardAt(0, captured.timelines.getValue(0).end)!!
        assertEquals(PieceType.PAWN, board[Square.parse("d6")]?.type)
        assertNull(board[Square.parse("d5")])
    }

    @Test
    fun `pawn promotes to a queen`() {
        val board = Board.parse(
            "....k...",
            "P.......",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K...",
        )
        val state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE).move("a7", "a8")
        assertEquals(PieceType.QUEEN, state.boardAt(0, 1)!![Square.parse("a8")]?.type)
    }

    @Test
    fun `rooks and bishops move through time`() {
        val board = Board.parse(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "R.B.K...",
        )
        // Five identical boards at half-moves 0..4; White is to move on the last one.
        val state = GameState(mapOf(0 to Timeline(0, 0, List(5) { board })), Side.WHITE)
        // The rook's own past self blocks it on a1 one turn ago, so it can't move along time alone.
        val rook = state.legalMovesFrom(Pos.of(0, 4, "a1"))
        assertTrue(rook.none { it.to.t != 4 })
        assertNotNull(rook.firstOrNull { it.to == Pos.of(0, 4, "a8") })
        // The bishop moves diagonally through rank and time: one turn back and one rank up, or two and two.
        val bishop = state.legalMovesFrom(Pos.of(0, 4, "c1"))
        assertNotNull(bishop.firstOrNull { it.to == Pos.of(0, 2, "c2") })
        assertNotNull(bishop.firstOrNull { it.to == Pos.of(0, 0, "c3") })
    }

    @Test
    fun `pawn moves forward across timelines`() {
        val board = Board.parse(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "...P....",
            "....K...",
        )
        // Two timelines (0 and -1) at the same half-move; White pawns advance toward -L.
        val state = GameState(
            mapOf(0 to Timeline(0, 0, listOf(board)), -1 to Timeline(-1, 0, listOf(Board.parse(
                "....k...", "........", "........", "........", "........", "........", "........", "....K...",
            )))),
            Side.WHITE,
        )
        val moves = state.legalMovesFrom(Pos.of(0, 0, "d2"))
        assertNotNull(moves.firstOrNull { it.to == Pos.of(-1, 0, "d2") })
        assertTrue(moves.none { it.to.l == 1 })
    }
}
