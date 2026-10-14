package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OnlineGameTest {
    private val mode = GameMode(variant = Variant.STANDARD, timelineLimit = 4)

    private fun assertSamePosition(expected: GameState, actual: GameState) {
        assertEquals(expected.sideToMove, actual.sideToMove)
        assertEquals(expected.timelines.keys, actual.timelines.keys)
        for ((l, timeline) in expected.timelines) assertEquals(timeline.boards, actual.timelines.getValue(l).boards, "L$l")
    }

    /** Plays [moves] on [session] and submits them as a turn would be sent: the event to replay. */
    private fun turn(session: GameSession, vararg moves: Pair<Pos, Pos>): OnlineEvent {
        var s = session
        for ((from, to) in moves) s = s.tap(from).tap(to)
        assertEquals(moves.size, s.state.turnMoves.size, "every move was legal")
        val next = s.submit()
        assertTrue(next.state.sideToMove != s.state.sideToMove, "the turn was submitted")
        return OnlineEvent.Turn(s.state.turnMoves.map { it.from to it.to }, next.outcome, next.undecided)
    }

    @Test
    fun `both devices replay the same events to the same game`() {
        var white = GameSession(mode.copy(onlineSide = Side.WHITE))
        var black = GameSession(mode.copy(onlineSide = Side.BLACK))
        assertFalse(white.isRemoteTurn)
        assertTrue(black.isRemoteTurn)
        // Black can't move during White's turn.
        assertSame(black, black.tap(Pos.of(0, 0, "e2")))

        val events = ArrayList<Pair<Side, OnlineEvent>>()
        fun send(sender: Side, event: OnlineEvent) {
            // Through the relay and back, as text.
            val received = assertNotNull(OnlineEvent.decode(OnlineEvent.encode(event)))
            assertEquals(event, received)
            events += sender to received
            white = assertNotNull(white.applyEvent(received, sender))
            black = assertNotNull(black.applyEvent(received, sender))
        }
        send(Side.WHITE, turn(white, Pos.of(0, 0, "g1") to Pos.of(0, 0, "f3")))
        send(Side.BLACK, turn(black, Pos.of(0, 1, "b8") to Pos.of(0, 1, "c6")))
        // The knight travels one turn back, branching timeline +1.
        send(Side.WHITE, turn(white, Pos.of(0, 2, "f3") to Pos.of(0, 0, "f5")))
        assertEquals(2, white.state.timelines.size)
        assertSamePosition(white.state, black.state)
        assertEquals(white.log, black.log)
        assertEquals(white.lastTurn, black.lastTurn)

        // A device that joins late (or comes back) replays the whole list.
        var late = GameSession(mode.copy(onlineSide = Side.BLACK))
        for ((sender, event) in events) late = assertNotNull(late.applyEvent(event, sender))
        assertSamePosition(black.state, late.state)
    }

    @Test
    fun `moves not yet submitted are undone freely, but undo can't go back past an event`() {
        var white = GameSession(mode.copy(onlineSide = Side.WHITE))
        val event = turn(white, Pos.of(0, 0, "e2") to Pos.of(0, 0, "e4"))
        white = assertNotNull(white.applyEvent(event, Side.WHITE))
        assertNull(white.previous)
        assertSame(white, white.undo())

        var black = GameSession(mode.copy(onlineSide = Side.BLACK))
        black = assertNotNull(black.applyEvent(event, Side.WHITE))
        black = black.tap(Pos.of(0, 1, "e7")).tap(Pos.of(0, 1, "e5"))
        assertEquals(1, black.state.turnMoves.size)
        assertEquals(0, black.undo().state.turnMoves.size)
    }

    @Test
    fun `an event that can't happen is refused`() {
        val white = GameSession(mode.copy(onlineSide = Side.WHITE))
        // A black piece on White's turn, an impossible move, and no move at all.
        assertNull(white.applyEvent(OnlineEvent.Turn(listOf(Pos.of(0, 0, "e7") to Pos.of(0, 0, "e5")), Outcome.Ongoing, false), Side.WHITE))
        assertNull(white.applyEvent(OnlineEvent.Turn(listOf(Pos.of(0, 0, "e2") to Pos.of(0, 0, "e5")), Outcome.Ongoing, false), Side.WHITE))
        assertNull(white.applyEvent(OnlineEvent.Turn(emptyList(), Outcome.Ongoing, false), Side.WHITE))
        assertNull(OnlineEvent.decode("turn nonsense"))
        assertNull(OnlineEvent.decode("end ongoing"))
        assertNull(OnlineEvent.decode("hello"))
    }

    @Test
    fun `a resignation ends the game on both devices, setting aside moves in progress`() {
        val white = GameSession(mode.copy(onlineSide = Side.WHITE))
        val resigned = white.resign()
        assertEquals(Outcome.Resignation(Side.BLACK), resigned.outcome)
        val event = OnlineEvent.End(resigned.outcome)
        assertEquals(event, OnlineEvent.decode(OnlineEvent.encode(event)))

        // White had a move in progress when its own resignation came back from the relay.
        val midTurn = white.tap(Pos.of(0, 0, "e2")).tap(Pos.of(0, 0, "e4"))
        val ended = assertNotNull(midTurn.applyEvent(event, Side.WHITE))
        assertEquals(Outcome.Resignation(Side.BLACK), ended.outcome)
        assertTrue(ended.state.turnMoves.isEmpty())
        assertFalse(ended.isRemoteTurn)

        val black = assertNotNull(GameSession(mode.copy(onlineSide = Side.BLACK)).applyEvent(event, Side.WHITE))
        assertEquals(ended.outcome, black.outcome)
    }

    @Test
    fun `a player can only send what's theirs to send`() {
        val black = GameSession(mode.copy(onlineSide = Side.BLACK))
        val e4 = listOf(Pos.of(0, 0, "e2") to Pos.of(0, 0, "e4"))
        // Black's app playing White's turn.
        assertNull(black.applyEvent(OnlineEvent.Turn(e4, Outcome.Ongoing, false), Side.BLACK))
        assertNotNull(black.applyEvent(OnlineEvent.Turn(e4, Outcome.Ongoing, false), Side.WHITE))
        // A turn that claims to win for the other side, or to end by resignation.
        assertNull(black.applyEvent(OnlineEvent.Turn(e4, Outcome.Checkmate(Side.BLACK), false), Side.WHITE))
        assertNull(black.applyEvent(OnlineEvent.Turn(e4, Outcome.Resignation(Side.WHITE), false), Side.WHITE))
        // Resigning on the other player's behalf.
        assertNull(black.applyEvent(OnlineEvent.End(Outcome.Resignation(Side.WHITE)), Side.WHITE))
        assertNotNull(black.applyEvent(OnlineEvent.End(Outcome.Resignation(Side.WHITE)), Side.BLACK))
        // Only the side to move can say it has no legal turn, and only to its own loss.
        assertNull(black.applyEvent(OnlineEvent.End(Outcome.Stalemate), Side.BLACK))
        assertNull(black.applyEvent(OnlineEvent.End(Outcome.Checkmate(Side.WHITE)), Side.WHITE))
        assertNotNull(black.applyEvent(OnlineEvent.End(Outcome.Checkmate(Side.BLACK)), Side.WHITE))
    }

    @Test
    fun `a claimed checkmate or stalemate is checked`() {
        // A real checkmate: a puzzle's solution.
        val puzzle = Puzzles.all.first()
        var s = puzzle.start()
        for ((from, to) in puzzle.solution) s = s.apply(s.legalMovesFrom(from).first { it.to == to })
        val mated = GameSession(GameMode(), state = s.submit(), outcome = Outcome.Checkmate(s.sideToMove))
        assertTrue(mated.confirmsOutcome())
        // The same position called a stalemate: a king is attacked, so it isn't one.
        assertFalse(mated.copy(outcome = Outcome.Stalemate).confirmsOutcome())

        // At the start of a game, White has plenty of legal turns and no king is attacked.
        val start = GameSession(mode)
        assertFalse(start.copy(outcome = Outcome.Stalemate).confirmsOutcome())
        assertFalse(start.copy(outcome = Outcome.Checkmate(Side.BLACK)).confirmsOutcome())
        assertTrue(start.copy(outcome = Outcome.Resignation(Side.BLACK)).confirmsOutcome())
    }
}
