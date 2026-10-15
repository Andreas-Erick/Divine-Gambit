package com.andreaserick.multiversechess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalCheckTest {
    /**
     * Plays random games in every variant, preferring moves through time and across timelines so
     * the multiverse grows, and at each position compares the quick check after every move (and
     * after pairs of moves) with the full one.
     */
    @Test
    fun `the quick check after a move agrees with the full check`() {
        var compared = 0
        var checks = 0
        for (variant in Variant.entries) {
            for (game in 0 until 6) {
                val random = Random(variant.ordinal * 100 + game)
                var state = GameState.initial(variant, timelineLimit = if (game % 2 == 0) null else 4)
                for (turn in 0 until 30) {
                    val inCheck = state.isInCheck(state.sideToMove)
                    val moves = state.allMoves()
                    for (move in moves.shuffled(random).take(60)) {
                        val child = state.apply(move)
                        val full = child.isInCheck(child.sideToMove)
                        assertEquals(full, child.isInCheckAfter(state, inCheck), "$variant game $game turn $turn: $move")
                        compared++
                        if (full) checks++
                        // And two moves on, from the first.
                        if (!full) {
                            child.allMoves().shuffled(random).firstOrNull()?.let { second ->
                                val grandchild = child.apply(second)
                                assertEquals(grandchild.isInCheck(grandchild.sideToMove), grandchild.isInCheckAfter(state, inCheck), "$variant: $move then $second")
                                compared++
                            }
                        }
                    }
                    state = randomTurn(state, random) ?: break
                }
            }
        }
        // The positions must include plenty of checks, or the comparison proves little.
        assertTrue(checks > 500, "only $checks checks among $compared")
        println("Compared $compared positions, $checks of them in check")
    }

    /** A random legal turn, leaning toward travel through time and across timelines. */
    private fun randomTurn(state: GameState, random: Random): GameState? {
        val order = { _: GameState, moves: List<Move> ->
            val (travel, rest) = moves.shuffled(random).partition { !it.isSpatial }
            if (random.nextInt(3) == 0) travel + rest else rest + travel
        }
        return try {
            TurnWalker(3_000, maxOptionalMoves = 1, order = order).walk(state) { true }?.submit()
        } catch (_: BudgetExceeded) {
            null
        }
    }
}
