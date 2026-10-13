package com.andreaserick.multiversechess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FairyPiecesTest {
    private val kingsOnly = arrayOf("....k...", "........", "........", "........", "........", "........", "........", "....K...")

    /** One timeline with [count] copies of [board], White to move on the last one. */
    private fun history(board: Board, count: Int): GameState =
        GameState(mapOf(0 to Timeline(0, 0, List(count) { board })), Side.WHITE)

    private fun GameState.destinations(from: Pos): Set<Pos> = legalMovesFrom(from).map { it.to }.toSet()

    @Test
    fun `unmoved pawn can step two timelines forward`() {
        val withPawn = Board.parse("....k...", "........", "........", "........", "........", "........", "...P....", "....K...")
        val empty = Board.parse(*kingsOnly)
        val state = GameState(
            mapOf(
                0 to Timeline(0, 0, listOf(withPawn)),
                -1 to Timeline(-1, 0, listOf(empty)),
                -2 to Timeline(-2, 0, listOf(empty)),
            ),
            Side.WHITE,
        )
        val moves = state.destinations(Pos.of(0, 0, "d2"))
        assertTrue(Pos.of(-1, 0, "d2") in moves)
        assertTrue(Pos.of(-2, 0, "d2") in moves)

        // Blocked when the square on the timeline in between is occupied.
        val knight = empty.with(listOf(Square.parse("d2") to Piece(PieceType.KNIGHT, Side.BLACK)))
        val blocked = GameState(state.timelines + (-1 to Timeline(-1, 0, listOf(knight))), Side.WHITE)
        assertTrue(Pos.of(-2, 0, "d2") !in blocked.destinations(Pos.of(0, 0, "d2")))

        // A pawn that has moved only steps one timeline.
        val movedPawn = withPawn.with(listOf(Square.parse("d2") to Piece(PieceType.PAWN, Side.WHITE, moved = true)))
        val moved = GameState(state.timelines + (0 to Timeline(0, 0, listOf(movedPawn))), Side.WHITE)
        val movedMoves = moved.destinations(Pos.of(0, 0, "d2"))
        assertTrue(Pos.of(-1, 0, "d2") in movedMoves)
        assertTrue(Pos.of(-2, 0, "d2") !in movedMoves)
    }

    @Test
    fun `princess moves like a rook or bishop but not a knight`() {
        val board = Board.parse("....k...", "........", "........", "........", "...S....", "........", "........", "....K...")
        val moves = history(board, 1).destinations(Pos.of(0, 0, "d4"))
        assertTrue(Pos.of(0, 0, "d8") in moves)
        assertTrue(Pos.of(0, 0, "h8") in moves)
        assertTrue(Pos.of(0, 0, "a4") in moves)
        assertTrue(Pos.of(0, 0, "e6") !in moves)
        // Rook and bishop lines on an empty-ish board: 14 + 13.
        assertEquals(27, moves.size)
    }

    @Test
    fun `unicorn only moves along three dimensions`() {
        val board = Board.parse("....k...", "........", "........", "........", "...U....", "........", "........", "....K...")
        assertTrue(history(board, 1).legalMovesFrom(Pos.of(0, 0, "d4")).isEmpty())

        val state = history(board, 5)
        val moves = state.destinations(Pos.of(0, 4, "d4"))
        // One turn back plus one file and one rank, or two turns back plus two of each.
        assertTrue(Pos.of(0, 2, "e5") in moves)
        assertTrue(Pos.of(0, 0, "b2") in moves)
        assertTrue(moves.none { it.t == 4 })
        assertTrue(Pos.of(0, 2, "d5") !in moves)
    }

    @Test
    fun `dragon needs all four dimensions`() {
        val board = Board.parse("....k...", "........", "........", "........", "...D....", "........", "........", "....K...")
        val single = history(board, 3)
        assertTrue(single.legalMovesFrom(Pos.of(0, 2, "d4")).isEmpty())

        val other = Board.parse(*kingsOnly)
        val state = GameState(
            mapOf(0 to Timeline(0, 0, List(3) { board }), 1 to Timeline(1, 0, List(3) { other }, parent = 0)),
            Side.WHITE,
        )
        val moves = state.destinations(Pos.of(0, 2, "d4"))
        assertEquals(setOf(Pos.of(1, 0, "c3"), Pos.of(1, 0, "c5"), Pos.of(1, 0, "e3"), Pos.of(1, 0, "e5")), moves)
    }

    @Test
    fun `brawn captures diagonally between rank and time but a pawn cannot`() {
        val target = Piece(PieceType.KNIGHT, Side.BLACK)
        fun state(type: PieceType): GameState {
            val now = Board.parse(*kingsOnly).with(listOf(Square.parse("d4") to Piece(type, Side.WHITE, moved = true)))
            val past = Board.parse(*kingsOnly).with(listOf(Square.parse("d5") to target))
            return GameState(mapOf(0 to Timeline(0, 0, listOf(past, past, now))), Side.WHITE)
        }
        assertTrue(Pos.of(0, 0, "d5") in state(PieceType.BRAWN).destinations(Pos.of(0, 2, "d4")))
        assertTrue(Pos.of(0, 0, "d5") !in state(PieceType.PAWN).destinations(Pos.of(0, 2, "d4")))
        // Brawns still move like pawns otherwise.
        assertTrue(Pos.of(0, 2, "d5") in state(PieceType.BRAWN).destinations(Pos.of(0, 2, "d4")))
    }

    @Test
    fun `brawn promotes and can be captured en passant`() {
        val state = GameState.initial(Variant.BRAWNS).turns("e2e4", "a7a6", "e4e5", "d7d5")
        val captured = state.move("e5", "d6")
        val board = captured.boardAt(0, captured.timelines.getValue(0).end)!!
        assertEquals(PieceType.BRAWN, board[Square.parse("d6")]?.type)
        assertNull(board[Square.parse("d5")])
    }

    @Test
    fun `variants set up the expected pieces`() {
        for (variant in Variant.entries) {
            val board = variant.board()
            assertEquals(PieceType.KING, board[Square.parse("e1")]?.type, variant.name)
            assertEquals(Side.BLACK, board[Square.parse("e8")]?.side, variant.name)
            assertEquals(32, board.pieces().size, variant.name)
            assertTrue(GameState.initial(variant).allMoves().isNotEmpty(), variant.name)
        }
        assertEquals(PieceType.PRINCESS, Variant.PRINCESSES.board()[Square.parse("d8")]?.type)
        assertEquals(
            listOf(PieceType.UNICORN, PieceType.PRINCESS, PieceType.DRAGON, PieceType.BRAWN),
            Variant.MENAGERIE.fairyPieces,
        )
        assertEquals(emptyList(), Variant.STANDARD.fairyPieces)
    }

    @Test
    fun `computer plays the menagerie without errors`() {
        var session = GameSession(GameMode(computerSide = Side.BLACK, variant = Variant.MENAGERIE))
        val player = ComputerPlayer(Random(11))
        repeat(10) {
            if (session.outcome != Outcome.Ongoing) return
            session = session.applyComputerTurn(assertNotNull(player.chooseTurn(session.state)))
        }
        assertTrue(session.log.isNotEmpty())
    }
}
