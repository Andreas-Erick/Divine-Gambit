package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whose side this device is on, in each kind of game. */
class PerspectiveTest {
    private val puzzle = Puzzles.all.first()

    @Test
    fun `each mode knows its kind and the side played here`() {
        val modes = mapOf(
            GameMode() to (GameKind.PASS_AND_PLAY to null),
            GameMode(computerSide = Side.BLACK) to (GameKind.COMPUTER to Side.WHITE),
            GameMode(computerSide = Side.WHITE, aiOpponent = true) to (GameKind.AI to Side.BLACK),
            GameMode(onlineSide = Side.BLACK) to (GameKind.ONLINE to Side.BLACK),
            GameMode(puzzle = puzzle) to (GameKind.PUZZLE to null),
        )
        for ((mode, expected) in modes) {
            assertEquals(expected.first, mode.kind, "$mode")
            assertEquals(expected.second, mode.localSide, "$mode")
        }
        assertTrue(GameMode().savedOnDevice)
        assertTrue(GameMode(computerSide = Side.BLACK).savedOnDevice)
        assertFalse(GameMode(onlineSide = Side.WHITE).savedOnDevice)
        assertFalse(GameMode(puzzle = puzzle).savedOnDevice)
    }

    @Test
    fun `the player's side and turn, by mode`() {
        val shared = GameSession(GameMode())
        assertEquals(Side.WHITE, shared.playerSide)
        assertTrue(shared.isPlayersTurn)

        val vsComputer = GameSession(GameMode(computerSide = Side.WHITE))
        assertEquals(Side.BLACK, vsComputer.playerSide)
        assertTrue(vsComputer.isOpponentsTurn)
        assertFalse(vsComputer.isPlayersTurn)

        val online = GameSession(GameMode(onlineSide = Side.BLACK))
        assertEquals(Side.BLACK, online.playerSide)
        assertTrue(online.isOpponentsTurn)
    }

    @Test
    fun `resigning loses for the side played here, whoever is to move`() {
        val online = GameSession(GameMode(onlineSide = Side.BLACK)).resign()
        assertEquals(Outcome.Resignation(Side.WHITE), online.outcome)
        assertEquals(Side.WHITE, online.outcome.winnerOrNull)
        assertFalse(online.isPlayersTurn)
        assertEquals(Outcome.Resignation(Side.BLACK), GameSession(GameMode()).resign().outcome)
        assertNull(Outcome.Stalemate.winnerOrNull)
    }

    @Test
    fun `an unfinished game saved on the device can be continued`() {
        assertTrue(GameSession(GameMode()).isResumable)
        assertFalse(GameSession(GameMode()).resign().isResumable)
        assertFalse(GameSession(GameMode(onlineSide = Side.WHITE)).isResumable)
        assertFalse(GameSession(GameMode(puzzle = puzzle)).isResumable)
    }

    private fun solved(session: GameSession): GameSession {
        var s = session.restartPuzzle()
        for ((from, to) in puzzle.solution) s = s.tap(from).tap(to)
        return s.submit()
    }

    @Test
    fun `a puzzle counts as solved only when mated without looking`() {
        val start = GameSession(GameMode(puzzle = puzzle))
        var before = start
        for ((from, to) in puzzle.solution) before = before.tap(from).tap(to)
        val mated = before.submit()
        assertTrue(mated.solvesPuzzle(before))
        // Seeing the mate again later isn't solving it.
        assertFalse(mated.solvesPuzzle(mated))
        // With the solution shown, it doesn't count, even after a restart.
        val shown = start.showSolution()
        assertFalse(shown.submit().solvesPuzzle(shown))
        val again = solved(shown)
        assertFalse(again.solvesPuzzle(again.previous))
        assertFalse(GameSession(GameMode()).solvesPuzzle(null))
    }
}
