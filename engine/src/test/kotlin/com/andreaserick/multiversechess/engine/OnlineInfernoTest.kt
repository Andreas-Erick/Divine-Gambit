package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineInfernoTest {
    private val mode = GameMode(timelineLimit = 4, inferno = true)

    /** Plays [moves] on [session] and submits them, as the event a device would send. */
    private fun turn(session: GameSession, vararg moves: Pair<String, String>): OnlineEvent {
        var s = session
        val t = s.state.timelines.getValue(0).end
        for ((from, to) in moves) s = s.tap(Pos.of(0, t, from)).tap(Pos.of(0, t, to))
        assertEquals(moves.size, s.state.turnMoves.size, "every move was legal")
        val next = s.submit()
        return OnlineEvent.Turn(s.state.turnMoves.map { it.from to it.to }, next.outcome, next.undecided)
    }

    @Test
    fun `both devices see the same pieces taken, and the same piece returning`() {
        var white = GameSession(mode.copy(onlineSide = Side.WHITE))
        var black = GameSession(mode.copy(onlineSide = Side.BLACK))
        fun send(sender: Side, event: OnlineEvent) {
            val received = assertNotNull(OnlineEvent.decode(OnlineEvent.encode(event)))
            assertEquals(event, received)
            white = assertNotNull(white.applyEvent(received, sender), "white accepts $received")
            black = assertNotNull(black.applyEvent(received, sender), "black accepts $received")
        }
        send(Side.WHITE, turn(white, "e2" to "e4"))
        send(Side.BLACK, turn(black, "d7" to "d5"))
        send(Side.WHITE, turn(white, "e4" to "d5"))
        // The capture reached Black's device through the event, not by playing it there.
        assertEquals(listOf(Soul(PieceType.PAWN, Side.BLACK)), black.souls)
        assertEquals(white.souls, black.souls)

        // Black's pawn has passed its trial: Black's device frees it at the start of its turn.
        val freed = assertNotNull(black.free(0))
        val at = assertNotNull(freed.returned)
        // White's device can't free Black's piece, and Black can't claim another square.
        assertNull(white.free(0))
        assertNull(white.applyEvent(OnlineEvent.Free(0, at), Side.WHITE))
        assertNull(white.applyEvent(OnlineEvent.Free(0, at.copy(x = (at.x + 1) % 8)), Side.BLACK))
        assertNull(white.applyEvent(OnlineEvent.Free(5, at), Side.BLACK))
        send(Side.BLACK, OnlineEvent.Free(0, at))
        assertTrue(white.souls.single().freed)
        assertEquals(white.state.pieceAt(at), black.state.pieceAt(at))
        assertEquals(Piece(PieceType.PAWN, Side.BLACK, moved = true), white.state.pieceAt(at))
        // A piece is only freed once, and Black's turn goes on from there.
        assertNull(white.applyEvent(OnlineEvent.Free(0, at), Side.BLACK))
        send(Side.BLACK, turn(black, "g8" to "f6"))
        assertEquals(white.state.sideToMove, black.state.sideToMove)
        assertFalse(white.isRemoteTurn)
    }

    @Test
    fun `without the Inferno, nothing is taken into it and nothing can return`() {
        val plain = GameMode(timelineLimit = 4, onlineSide = Side.BLACK)
        var black = GameSession(plain)
        val white = GameSession(plain.copy(onlineSide = Side.WHITE))
        black = assertNotNull(black.applyEvent(turn(white, "e2" to "e4"), Side.WHITE))
        assertEquals(emptyList(), black.souls)
        assertNull(black.applyEvent(OnlineEvent.Free(0, Pos.of(0, 1, "a8")), Side.BLACK))
    }
}
