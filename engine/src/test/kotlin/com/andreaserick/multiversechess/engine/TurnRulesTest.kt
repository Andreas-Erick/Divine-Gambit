package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Rules that depend on how a turn's moves combine across boards, and on boards in the past. */
class TurnRulesTest {
    @Test
    fun `the order present boards are played in changes what a move does`() {
        val withRook = Board.parse("....k...", "........", "........", "........", "........", "........", "........", "R...K...")
        val without = Board.parse("....k...", "........", "........", "........", "........", "........", "........", "....K...")
        val state = GameState(
            mapOf(
                0 to Timeline(0, 0, listOf(withRook, withRook, withRook)),
                1 to Timeline(1, 1, listOf(without, without), parent = 0),
            ),
            Side.WHITE,
        )
        assertEquals(listOf(0, 1), state.mandatoryTimelines)

        // Sent to timeline +1 before it's played, the rook advances it...
        val jumped = state.travel(Pos.of(0, 2, "a1"), Pos.of(1, 2, "a1"))
        assertEquals(setOf(0, 1), jumped.timelines.keys)
        // ...but once +1 has been played, the same move branches from its past.
        val branched = state.move("e1", "d1", l = 1).travel(Pos.of(0, 2, "a1"), Pos.of(1, 2, "a1"))
        assertEquals(1, branched.timelines.getValue(2).parent)
        assertEquals(3, branched.timelines.getValue(2).start)

        // The turn search has to try both orders to see every legal turn.
        val turns = ArrayList<GameState>()
        TurnWalker(nodeBudget = 100_000, maxOptionalMoves = 0).walk(state) { turns += it; false }
        assertTrue(turns.any { it.timelines[2]?.parent == 1 }, "never branched from the played board")
        assertTrue(turns.any { it.timelines.size == 2 && it.boardAt(1, 3)!![Square.parse("a1")] != null }, "never jumped")
    }

    @Test
    fun `a check on two boards is escaped by moving the present back from the second`() {
        // A position where Black must answer threats on timelines 0 and +1. The only escape starts
        // on +1, the second present board: the king steps into timeline 0's past, the new timeline
        // pulls the present back, and neither threatened board has to be played.
        val state = PuzzleSetup().apply {
            board("....k...", "pp......", "........", "........", "........", "........", "......PP", "R......K")
            turns("a1a3", "a7a6")
            move(Pos.of(0, 2, "a3"), Pos.of(0, 0, "a3"))
            submit()
            move("e8d7", l = 1)
            submit()
            move("a3e3", l = 1)
            submit()
            move("e8d7")
            move("b7b6", l = 1)
            submit()
            move("h1g1")
            move("e3e8", l = 1)
            submit()
            move("d7c6")
            move("d7c6", l = 1)
            submit()
            move(Pos.of(1, 6, "e8"), Pos.of(0, 6, "e8"))
            submit()
        }.state
        assertEquals(listOf(0, 1), state.mandatoryTimelines)
        assertTrue(state.threats().isNotEmpty())

        val escaped = state.travel(Pos.of(1, 7, "c6"), Pos.of(0, 5, "b5"))
        assertTrue(escaped.canSubmit)
        assertFalse(escaped.isInCheck(Side.BLACK))
        assertIs<TurnSearchResult.Found>(TurnSearch().findLegalTurn(state))
        assertEquals(Outcome.Ongoing, state.evaluateOutcome())
    }

    @Test
    fun `a king left in the past can be captured through time`() {
        val start = GameState(
            mapOf(0 to Timeline(0, 0, listOf(Board.parse("....k...", "........", ".....n..", "........", "........", "........", "P.......", "....K...")))),
            Side.WHITE,
        )
        // The king steps to e2, then the knight lands on e4: from there it can jump one turn back
        // and two ranks down, onto the e2 square of the board where the king stood.
        val state = start.turns("e1e2", "f6e4")
        val threat = state.threats().single()
        assertEquals(Pos.of(0, 1, "e2"), threat.to)
        assertTrue(state.move("a2", "a3").isInCheck(Side.WHITE))
        // The past can't be changed and the knight can't be reached, so that's checkmate.
        assertEquals(Outcome.Checkmate(Side.BLACK), state.evaluateOutcome())
    }

    @Test
    fun `pieces can't pass through boards that don't exist`() {
        val rook = Board.parse("....k...", "........", "........", "........", "........", "........", "........", "R...K...")
        val empty = Board.parse("....k...", "........", "........", "........", "........", "........", "........", "....K...")
        // Timeline +1 starts at half-move 2, so it has no board one turn before its first.
        val state = GameState(
            mapOf(
                0 to Timeline(0, 0, listOf(empty, empty, empty, empty, empty)),
                1 to Timeline(1, 2, listOf(empty, empty, rook), parent = 0),
            ),
            Side.WHITE,
        )
        val moves = state.legalMovesFrom(Pos.of(1, 4, "a1"))
        assertTrue(moves.any { it.to == Pos.of(1, 2, "a1") })
        assertTrue(moves.none { it.to.l == 1 && it.to.t < 2 })
        // Along the timeline axis it reaches timeline 0, but there's nothing beyond +1.
        assertTrue(moves.any { it.to == Pos.of(0, 4, "a1") })
        assertTrue(moves.none { it.to.l == 2 })
    }

    @Test
    fun `an inactive timeline becomes active when the opponent catches up`() {
        val board = Board.standard()
        fun timelines(vararg ls: Int) = ls.associateWith { Timeline(it, 0, listOf(board), parent = if (it == 0) null else 0) }

        val ahead = GameState(timelines(0, 1, 2), Side.WHITE)
        assertTrue(ahead.isActive(1))
        assertFalse(ahead.isActive(2))

        val caughtUp = GameState(timelines(0, 1, 2, -1), Side.WHITE)
        assertTrue(caughtUp.isActive(2))
        assertTrue(caughtUp.isActive(-1))
    }
}
