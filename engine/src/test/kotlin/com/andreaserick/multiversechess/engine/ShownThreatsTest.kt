package com.andreaserick.multiversechess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShownThreatsTest {
    /** Every threat shown runs from an attacking piece to a royal piece, both on boards that exist. */
    private fun assertShownOnBoards(state: GameState, where: String) {
        val threats = state.threats()
        val shown = state.shownThreats()
        assertEquals(threats.size, shown.size)
        for (threat in shown) {
            val attacker = assertNotNull(state.pieceAt(threat.from), "$where: $threat starts on no piece")
            val target = assertNotNull(state.pieceAt(threat.to), "$where: $threat ends on no piece")
            assertEquals(state.sideToMove.opponent, attacker.side, "$where: $threat")
            assertTrue(target.type.isRoyal && target.side == state.sideToMove, "$where: $threat")
        }
    }

    @Test
    fun `the checkmates of the puzzles are shown on the boards there are`() {
        for (puzzle in Puzzles.all) {
            var state = puzzle.start()
            for ((from, to) in puzzle.solution) state = state.apply(state.legalMovesFrom(from).first { it.to == to })
            val mated = state.submit()
            assertTrue(mated.threats().isNotEmpty(), puzzle.id)
            assertShownOnBoards(mated, puzzle.id)
        }
    }

    @Test
    fun `generated puzzles' checkmates too`() {
        for (seed in 0 until 12) {
            val puzzle = PuzzleGenerator(Random(seed)).generate(1) ?: continue
            var state = puzzle.start()
            for ((from, to) in puzzle.solution) state = state.apply(state.legalMovesFrom(from).first { it.to == to })
            assertShownOnBoards(state.submit(), "generated $seed")
        }
    }

    @Test
    fun `and every check along random games`() {
        var checks = 0
        for (game in 0 until 30) {
            val random = Random(game)
            var state = GameState.initial(Variant.entries[game % Variant.entries.size], timelineLimit = if (game % 2 == 0) null else 4)
            for (turn in 0 until 30) {
                if (state.threats().isNotEmpty()) checks++
                assertShownOnBoards(state, "game $game turn $turn")
                val order = { _: GameState, moves: List<Move> ->
                    val (travel, rest) = moves.shuffled(random).partition { !it.isSpatial }
                    if (random.nextInt(3) == 0) travel + rest else rest + travel
                }
                state = (try { TurnWalker(3_000, 1, order = order).walk(state) { true } } catch (_: BudgetExceeded) { null })?.submit() ?: break
            }
        }
        assertTrue(checks > 20, "only $checks positions in check")
    }
}
