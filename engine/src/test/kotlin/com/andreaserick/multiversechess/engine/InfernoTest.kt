package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfernoTest {
    private fun GameSession.move(from: String, to: String): GameSession {
        val t = state.timelines.getValue(0).end
        return tap(Pos.of(0, t, from)).tap(Pos.of(0, t, to))
    }

    /** 1. e4 d5 2. exd5, with the capture made but not yet submitted. */
    private fun pawnTaken(mode: GameMode): GameSession =
        GameSession(mode).move("e2", "e4").submit().move("d7", "d5").submit().move("e4", "d5")

    @Test
    fun `a taken piece goes to the Inferno, and undo takes it back out`() {
        val session = pawnTaken(GameMode(inferno = true))
        assertEquals(listOf(Soul(PieceType.PAWN, Side.BLACK)), session.souls)
        assertEquals(emptyList(), session.undo().souls)
        assertEquals(emptyList(), pawnTaken(GameMode()).souls)
    }

    @Test
    fun `the computer's captures send pieces to the Inferno too`() {
        val session = GameSession(GameMode(computerSide = Side.BLACK, inferno = true)).move("e2", "e4").submit()
        val take = { s: GameSession -> s.applyComputerTurn(listOf(s.state.legalMovesFrom(Pos.of(0, 1, "d7")).first { it.to == Pos.of(0, 1, "d5") })) }
        val replied = take(session).move("e4", "d5").submit()
        val captured = replied.applyComputerTurn(listOf(replied.state.legalMovesFrom(Pos.of(0, 3, "d8")).first { it.to == Pos.of(0, 3, "d5") }))
        assertEquals(listOf(Soul(PieceType.PAWN, Side.BLACK), Soul(PieceType.PAWN, Side.WHITE)), captured.souls)
    }

    @Test
    fun `more valuable pieces need more trials`() {
        assertEquals(1, Inferno.trialsFor(PieceType.PAWN))
        assertEquals(1, Inferno.trialsFor(PieceType.BRAWN))
        assertEquals(2, Inferno.trialsFor(PieceType.KNIGHT))
        assertEquals(2, Inferno.trialsFor(PieceType.BISHOP))
        assertEquals(3, Inferno.trialsFor(PieceType.ROOK))
        assertEquals(4, Inferno.trialsFor(PieceType.QUEEN))
    }

    /** White's rook was taken; Black's rook watches the a-file and its bishop the h5-d1 diagonal. */
    private fun rookInHell(): GameSession {
        val board = Board.parse(
            "r...k...",
            "........",
            "........",
            ".......b",
            "........",
            "........",
            "PPP..PPP",
            "....K...",
        )
        return GameSession(
            GameMode(inferno = true),
            state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE),
            souls = listOf(Soul(PieceType.ROOK, Side.WHITE)),
        )
    }

    @Test
    fun `a freed piece returns near home, on a square where it can't be taken`() {
        val freed = assertNotNull(rookInHell().free(0))
        // d1 is the bishop's and a1 the rook's; c1 is the safe square nearest the middle.
        val square = Pos.of(0, 0, "c1")
        assertEquals(square, freed.returned)
        assertEquals(Piece(PieceType.ROOK, Side.WHITE, moved = true), freed.state.pieceAt(square))
        assertTrue(freed.souls.single().freed)
        assertTrue(freed.log.last().contains("Rook returns from the Inferno"))
        // It counts as part of the turn's start: the turn goes on from there.
        assertEquals(emptyList(), freed.state.turnMoves)
        assertNull(freed.free(0))
    }

    @Test
    fun `a piece only returns at the start of its own side's turn`() {
        val session = rookInHell()
        assertNull(session.move("a2", "a3").free(0))
        assertNull(session.copy(souls = listOf(Soul(PieceType.ROOK, Side.BLACK))).free(0))
        assertNull(session.copy(mode = GameMode(computerSide = Side.WHITE, inferno = true)).free(0))
    }

    @Test
    fun `a freed piece never lands where it attacks a king`() {
        // Every square from which a returning queen wouldn't attack Black's king is covered.
        val board = Board.parse(
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "K......k",
        )
        val session = GameSession(
            GameMode(inferno = true),
            state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE),
            souls = listOf(Soul(PieceType.QUEEN, Side.WHITE)),
        )
        val freed = assertNotNull(session.free(0))
        val pos = assertNotNull(freed.returned)
        assertTrue(freed.state.kingCaptures(Side.WHITE).isEmpty(), "Queen on ${pos.square} attacks the king")
    }

    @Test
    fun `the Inferno is saved with the game, without counting this turn's captures twice`() {
        val session = pawnTaken(GameMode(inferno = true))
        val restored = assertNotNull(SavedGame.decode(SavedGame.encode(session)))
        assertTrue(restored.mode.inferno)
        assertEquals(session.souls, restored.souls)

        val freed = assertNotNull(rookInHell().free(0))
        val back = assertNotNull(SavedGame.decode(SavedGame.encode(freed)))
        assertEquals(freed.souls, back.souls)
        assertEquals(freed.returned, back.returned)
        assertEquals(freed.state.pieceAt(Pos.of(0, 0, "c1")), back.state.pieceAt(Pos.of(0, 0, "c1")))
    }
}
