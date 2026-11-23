package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfernoProgressTest {
    private fun GameSession.move(from: String, to: String): GameSession {
        val t = state.timelines.getValue(0).end
        return tap(Pos.of(0, t, from)).tap(Pos.of(0, t, to))
    }

    /** 1. e4 d5 2. exd5: Black's pawn is in the Inferno, and it's Black's turn. */
    private fun blackPawnTaken(mode: GameMode = GameMode(inferno = true)): GameSession =
        GameSession(mode).move("e2", "e4").submit().move("d7", "d5").submit().move("e4", "d5").submit()

    @Test
    fun `only the souls of the side at the device wait for trials`() {
        val game = blackPawnTaken()
        val progress = InfernoProgress()
        assertEquals(listOf(WaitingSoul(0, Soul(PieceType.PAWN, Side.BLACK), 0)), progress.waiting(game))
        // Against the computer playing Black, White's player has nothing in the Inferno.
        val vsComputer = GameSession(GameMode(computerSide = Side.BLACK, inferno = true), state = game.state, souls = game.souls)
        assertEquals(emptyList(), progress.souls(vsComputer))
        assertEquals("None of your pieces are waiting for a trial.", progress.closedReason(vsComputer))
    }

    @Test
    fun `passing a pawn's trial frees it, and it returns at the start of the turn`() {
        val game = blackPawnTaken()
        assertNull(InfernoProgress().closedReason(game))
        val passed = assertNotNull(InfernoProgress().pass(game, chosen = null))
        assertTrue(passed.soul.free)
        assertEquals(emptyList(), passed.progress.waiting(game))
        assertEquals(0, passed.progress.readyToFree(game))
        // Once a move of the turn is made, it has to wait for the next one.
        assertNull(passed.progress.readyToFree(game.move("d8", "d5")))
        assertNull(InfernoProgress().readyToFree(game))
    }

    @Test
    fun `the trials count for the chosen soul, or the one needing the most`() {
        val souls = listOf(Soul(PieceType.PAWN, Side.WHITE), Soul(PieceType.ROOK, Side.WHITE), Soul(PieceType.KNIGHT, Side.WHITE))
        val game = GameSession(GameMode(inferno = true), souls = souls)
        val progress = InfernoProgress()
        assertEquals(1, progress.trialsFor(game, null))
        assertEquals(2, progress.trialsFor(game, 2))
        assertEquals(1, progress.trialsFor(game, 7))
        val passed = assertNotNull(progress.pass(game, 2))
        assertEquals(WaitingSoul(2, souls[2], 1), passed.soul)
        assertFalse(passed.soul.free)
    }

    @Test
    fun `time runs on the player's own turn only, and a new turn brings more`() {
        val game = blackPawnTaken()
        var progress = InfernoProgress()
        assertTrue(progress.clockRuns(game))
        repeat(InfernoProgress.SECONDS_PER_TURN) { progress = progress.tick() }
        assertTrue(progress.timeUp)
        assertEquals(0, progress.tick().secondsLeft)
        assertEquals("Closed until you've played your turn.", progress.closedReason(game))
        // Moves within the turn don't bring more time; submitting it does.
        val moved = game.move("d8", "d5")
        assertTrue(progress.follow(game, moved).timeUp)
        assertEquals(InfernoProgress.SECONDS_PER_TURN, progress.follow(moved, moved.submit()).secondsLeft)

        val online = GameSession(GameMode(onlineSide = Side.WHITE, inferno = true), state = game.state, souls = game.souls)
        assertFalse(InfernoProgress().clockRuns(online), "it's the friend's turn")
    }

    @Test
    fun `undo keeps the trials passed, unless it takes the soul back out`() {
        val game = blackPawnTaken()
        val progress = assertNotNull(InfernoProgress().pass(game, null)).progress
        // Undo back past the capture: the pawn isn't in the Inferno any more.
        val before = game.undo().undo()
        assertEquals(0, before.souls.size)
        assertEquals(emptyMap(), progress.follow(game, before).solved)
        // Undo of a move within Black's turn keeps it.
        val moved = game.move("d8", "d5")
        assertEquals(progress.solved, progress.follow(moved, moved.undo()).solved)
    }

    @Test
    fun `progress is saved with the game, and reads back`() {
        val game = blackPawnTaken()
        val progress = InfernoProgress(mapOf(0 to 1))
        assertEquals(progress, InfernoProgress.decode(progress.encode()))
        assertNull(InfernoProgress.decode("0 -1"))
        assertNull(InfernoProgress.decode("zero one"))

        val saved = assertNotNull(SavedGame.load(SavedGame.encode(game, progress)))
        assertEquals(progress.solved, saved.trials.solved)
        assertEquals(game.souls, saved.game.souls)
        // Saves from before trials were kept with the game read back with none.
        assertEquals(emptyMap(), assertNotNull(SavedGame.load(SavedGame.encode(game))).trials.solved)
        // Trials for a soul the game doesn't have are dropped.
        assertEquals(emptyMap(), assertNotNull(SavedGame.load(SavedGame.encode(game, InfernoProgress(mapOf(5 to 2))))).trials.solved)
    }

    @Test
    fun `the Inferno is closed once the game is over`() {
        val game = blackPawnTaken().resign()
        assertEquals("The game is over.", InfernoProgress().closedReason(game))
        assertEquals("No game.", InfernoProgress().closedReason(null))
        assertEquals(emptyList(), InfernoProgress().souls(blackPawnTaken(GameMode())))
    }
}
