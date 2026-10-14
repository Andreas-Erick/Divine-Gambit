package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SavedGameTest {
    private fun assertSamePosition(expected: GameState, actual: GameState) {
        assertEquals(expected.sideToMove, actual.sideToMove)
        assertEquals(expected.turnMoves, actual.turnMoves)
        assertEquals(expected.timelineLimit, actual.timelineLimit)
        assertEquals(expected.timelines.keys, actual.timelines.keys)
        for ((l, timeline) in expected.timelines) {
            val other = actual.timelines.getValue(l)
            assertEquals(timeline.start, other.start, "L$l")
            assertEquals(timeline.parent, other.parent, "L$l")
            assertEquals(timeline.boards, other.boards, "L$l")
        }
    }

    @Test
    fun `a game in the middle of a turn comes back the same, and its moves can still be undone`() {
        var session = GameSession(GameMode(computerSide = Side.BLACK, variant = Variant.BRAWNS, timelineLimit = 4))
        fun play(from: Pos, to: Pos) {
            session = session.tap(from).tap(to)
        }
        play(Pos.of(0, 0, "e2"), Pos.of(0, 0, "e4"))
        session = session.submit()
        session = session.applyComputerTurn(listOf(session.state.legalMovesFrom(Pos.of(0, 1, "e7")).first { it.to == Pos.of(0, 1, "e5") }))
        play(Pos.of(0, 2, "g1"), Pos.of(0, 2, "f3"))
        session = session.submit()
        session = session.applyComputerTurn(listOf(session.state.legalMovesFrom(Pos.of(0, 3, "b8")).first { it.to == Pos.of(0, 3, "c6") }))
        // The knight travels one turn back, branching timeline +1; the turn isn't submitted yet.
        play(Pos.of(0, 4, "f3"), Pos.of(0, 2, "f5"))
        assertEquals(1, session.state.turnMoves.size)

        val text = SavedGame.encode(session)
        val restored = assertNotNull(SavedGame.decode(text))
        assertEquals(session.mode, restored.mode)
        assertSamePosition(session.state, restored.state)
        assertEquals(session.log, restored.log)
        assertEquals(session.outcome, restored.outcome)
        assertEquals(session.lastTurn.map { it.from to it.to }, restored.lastTurn.map { it.from to it.to })

        // Undo goes back to the start of the turn, and no further.
        val undone = restored.undo()
        assertEquals(emptyList(), undone.state.turnMoves)
        assertEquals(setOf(0), undone.state.timelines.keys)
        assertNull(undone.previous)
        // And saving the restored game gives the same text.
        assertEquals(text, SavedGame.encode(restored))
    }

    @Test
    fun `a finished game keeps its outcome`() {
        val board = Board.parse("......k.", "........", "........", "........", "........", "........", "......PP", "r......K")
        val mated = GameSession(GameMode(), state = GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE)).claimNoLegalTurn()
        val restored = assertNotNull(SavedGame.decode(SavedGame.encode(mated)))
        assertEquals(Outcome.Checkmate(Side.BLACK), restored.outcome)
    }

    @Test
    fun `anything else is rejected`() {
        assertNull(SavedGame.decode(""))
        assertNull(SavedGame.decode("multiverse-chess 1\nmode X Y Z"))
        assertNull(SavedGame.decode("something else entirely"))
    }
}
