package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PuzzlesTest {
    private fun Puzzle.solved(): GameState =
        solution.fold(start()) { s, (from, to) -> s.apply(s.legalMovesFrom(from).single { it.to == to }) }

    @Test
    fun `puzzle ids are unique`() {
        assertEquals(Puzzles.all.size, Puzzles.all.map { it.id }.toSet().size)
    }

    @Test
    fun `every puzzle starts with White to move and is not already decided`() {
        for (puzzle in Puzzles.all) {
            val start = puzzle.start()
            assertEquals(Side.WHITE, start.sideToMove, puzzle.id)
            assertTrue(start.threats().isEmpty(), "${puzzle.id}: White starts in check")
            assertEquals(Outcome.Ongoing, start.evaluateOutcome(), puzzle.id)
        }
    }

    @Test
    fun `every solution checkmates`() {
        for (puzzle in Puzzles.all) {
            assertTrue(puzzle.isSolvedBy(puzzle.solved()), "${puzzle.id}: solution is not mate")
        }
    }

    @Test
    fun `every puzzle has at most two mating turns`() {
        for (puzzle in Puzzles.all) {
            val mates = PuzzleSolver.mates(puzzle.start())
            assertTrue(mates.any { it.toSet() == puzzle.solved().turnMoves.toSet() }, "${puzzle.id}: solver missed the solution")
            assertTrue(mates.size <= 2, "${puzzle.id}: ${mates.size} mating turns")
        }
    }

    @Test
    fun `a wrong attempt is taken back with an explanation`() {
        val puzzle = Puzzles.byId("rook-past")!!
        var session = GameSession(GameMode(puzzle = puzzle))
        session = session.tap(Pos.of(0, 4, "e1")).tap(Pos.of(0, 4, "e7")).submit()
        assertEquals(Outcome.Ongoing, session.outcome)
        assertTrue(session.message!!.startsWith("Not checkmate"), session.message)
        assertEquals(puzzle.start().timelines.mapValues { it.value.boards }, session.state.timelines.mapValues { it.value.boards })
        // Undo returns to the attempt.
        assertEquals(1, session.undo().state.turnMoves.size)
    }

    @Test
    fun `playing the solution solves the puzzle`() {
        val puzzle = Puzzles.all.first()
        val session = GameSession(GameMode(puzzle = puzzle)).showSolution()
        assertNotNull(session.message)
        assertEquals(Outcome.Checkmate(Side.WHITE), session.submit().outcome)
        assertEquals(session, session.resign())
    }
}
