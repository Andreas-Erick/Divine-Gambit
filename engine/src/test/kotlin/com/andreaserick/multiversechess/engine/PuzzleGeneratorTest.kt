package com.andreaserick.multiversechess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PuzzleGeneratorTest {
    private fun Puzzle.solved(): GameState =
        solution.fold(start()) { s, (from, to) -> s.apply(s.legalMovesFrom(from).first { it.to == to }) }

    @Test
    fun `generated puzzles are mates in one with few solutions, and survive saving`() {
        val out = java.io.File("build/generated-puzzles.txt").apply { writeText("") }
        for (seed in 0 until 4) {
            val started = System.nanoTime()
            val puzzle = assertNotNull(PuzzleGenerator(Random(seed)).generate(seed + 1), "seed $seed")
            val ms = (System.nanoTime() - started) / 1_000_000
            val start = puzzle.start()
            out.appendText("seed $seed: ${ms} ms, ${start.timelines.size} timelines, ${puzzle.side} to move, hint: ${puzzle.hint}\n")
            assertTrue(start.threats().isEmpty())
            assertTrue(puzzle.isSolvedBy(puzzle.solved()), "seed $seed: solution isn't mate")
            assertTrue(PuzzleSolver.mates(start).size in 1..2, "seed $seed")

            val restored = assertNotNull(PuzzleCodec.decode(PuzzleCodec.encode(puzzle)))
            assertEquals(puzzle.id, restored.id)
            assertEquals(puzzle.solution, restored.solution)
            assertTrue(restored.isSolvedBy(restored.solved()))
        }
    }

    @Test
    fun `restarting a puzzle goes back to its start`() {
        val puzzle = Puzzles.all.first()
        val (from, to) = puzzle.solution.first()
        val moved = GameSession(GameMode(puzzle = puzzle)).tap(from).tap(to)
        val restarted = moved.restartPuzzle()
        assertEquals(emptyList(), restarted.state.turnMoves)
        assertEquals(moved.state.turnMoves, restarted.undo().state.turnMoves)
    }

    @Test
    fun `several puzzles round-trip together`() {
        val text = PuzzleCodec.encodeAll(Puzzles.all.take(3))
        assertEquals(Puzzles.all.take(3).map { it.id }, PuzzleCodec.decodeAll(text).map { it.id })
    }
}
