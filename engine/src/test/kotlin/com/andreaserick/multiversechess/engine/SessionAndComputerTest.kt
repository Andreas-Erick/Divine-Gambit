package com.andreaserick.multiversechess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAndComputerTest {
    @Test
    fun `tap selects then moves, submit passes the turn, undo goes back`() {
        val start = GameSession(GameMode())
        val selected = start.tap(Pos.of(0, 0, "e2"))
        assertEquals(Pos.of(0, 0, "e2"), selected.selected)
        assertTrue(selected.targets.any { it.to == Pos.of(0, 0, "e4") })

        val moved = selected.tap(Pos.of(0, 0, "e4"))
        assertNull(moved.selected)
        assertEquals(listOf("White: (L0 T1) e2-e4"), moved.log)

        val submitted = moved.submit()
        assertEquals(Side.BLACK, submitted.state.sideToMove)
        assertEquals(1, submitted.lastTurn.size)

        assertEquals(Side.WHITE, submitted.undo().state.sideToMove)
        assertEquals(start.state.timelines, submitted.undo().undo().undo().state.timelines)
    }

    @Test
    fun `submit is refused until every present board is played`() {
        val session = GameSession(GameMode()).submit()
        assertNotNull(session.message)
        assertEquals(Side.WHITE, session.state.sideToMove)
    }

    @Test
    fun `computer finds a legal turn and takes a free queen`() {
        // 1. e4 d5 2. Qg4?? hangs the queen to Bc8xg4.
        val state = GameState.initial().turns("e2e4", "d7d5", "d1g4")
        val moves = assertNotNull(ComputerPlayer(Random(1)).chooseTurn(state))
        assertEquals(1, moves.size)
        assertEquals(Pos.of(0, 3, "g4"), moves.single().to)
    }

    @Test
    fun `computer plays a full game segment without errors`() {
        var session = GameSession(GameMode(computerSide = Side.BLACK))
        val white = ComputerPlayer(Random(7))
        val black = ComputerPlayer(Random(8))
        repeat(12) {
            if (session.outcome != Outcome.Ongoing) return
            val player = if (session.state.sideToMove == Side.WHITE) white else black
            session = session.applyComputerTurn(player.chooseTurn(session.state))
        }
        assertTrue(session.log.isNotEmpty())
    }

    @Test
    fun `against the computer undo returns to the human's turn`() {
        val human = GameSession(GameMode(computerSide = Side.BLACK))
            .tap(Pos.of(0, 0, "e2")).tap(Pos.of(0, 0, "e4")).submit()
        assertTrue(human.isComputerTurn)
        val afterComputer = human.applyComputerTurn(ComputerPlayer(Random(3)).chooseTurn(human.state))
        val undone = afterComputer.undo()
        assertEquals(Side.WHITE, undone.state.sideToMove)
        assertEquals(1, undone.state.turnMoves.size)
    }

    @Test
    fun `after checkmate the loser's moves can be tried but not submitted`() {
        val puzzle = Puzzles.all.first()
        var session = GameSession(GameMode(puzzle = puzzle))
        for ((from, to) in puzzle.solution) session = session.tap(from).tap(to)
        session = session.submit()
        assertEquals(Outcome.Checkmate(Side.WHITE), session.outcome)
        assertEquals(Side.BLACK, session.state.sideToMove)

        val move = session.state.allMoves().first()
        val tried = session.tap(move.from).tap(move.to)
        assertEquals(listOf(move), tried.state.turnMoves)
        val refused = tried.submit()
        assertEquals(Outcome.Checkmate(Side.WHITE), refused.outcome)
        assertEquals(tried.state, refused.state)
        assertNotNull(refused.message)
        assertEquals(session.state, refused.undo().state)
    }

    @Test
    fun `resigning ends the game`() {
        val session = GameSession(GameMode(computerSide = Side.WHITE)).resign()
        assertEquals(Side.WHITE, assertIs<Outcome.Resignation>(session.outcome).winner)
    }
}
