package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTextTest {
    private fun check(text: String): DraftCheck = PuzzleDrafts.read(text, "test")

    private fun invalid(text: String): String = assertIs<DraftCheck.Invalid>(check(text)).problem

    @Test
    fun `the example puzzle given to designers is valid, with the built-in solution`() {
        val puzzle = assertIs<DraftCheck.Valid>(check(AiText.EXAMPLE_PUZZLE)).puzzle
        assertEquals("Into the past", puzzle.title)
        assertEquals(Puzzles.byId("rook-past")!!.solution, puzzle.solution)
        assertTrue(Puzzle.isCheckmatingTurn(puzzle.start().let { start ->
            var s = start
            for ((from, to) in puzzle.solution) s = s.apply(s.legalMovesFrom(from).first { it.to == to })
            s
        }))
    }

    /** "Crossing over", written by hand in the designers' format: moves between boards and timelines. */
    private val crossingOver = """TITLE: Crossing over
HINT: One move can play two boards.
BOARD:
.r....rk
......pp
........
........
........
....N...
......PP
......K.
HISTORY:
e3f5
b8b7
f5e3
L0 T2 b7 > L0 T1 b7
L-1 g1f1
L-1 b7c7
SOLUTION:
L-1 T3 f5 > L0 T3 f7"""

    @Test
    fun `a puzzle with time travel and a move between timelines replays and checks`() {
        val puzzle = assertIs<DraftCheck.Valid>(check(crossingOver)).puzzle
        assertEquals(Puzzles.byId("crossing-over")!!.solution, puzzle.solution)
        assertEquals(2, puzzle.start().timelines.size)
        // It survives being saved, as generated puzzles are.
        val restored = assertNotNull(PuzzleCodec.decode(PuzzleCodec.encode(puzzle)))
        assertEquals(puzzle.solution, restored.solution)
        assertEquals(puzzle.title, restored.title)
    }

    @Test
    fun `mistakes come back as explanations the designer can act on`() {
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("g1h1\n", "g1g3\n")), "isn't legal. The king on (L0 T1) g1 can go to:")
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("d7c6\n", "")), "the history must end with Black's turn")
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("....R.K.", "....R...")), "exactly one White king")
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("....R.K.", "....R.K")), "8 lines of 8 characters")
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("e8d7", "e7d7")), "there's no piece on (L0 T1) e7")
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("g1h1", "L+1 g1h1")), "there's no timeline L+1")
        // Without the rook there's no mate; the stated solution then can't be played either.
        assertContains(invalid(AiText.EXAMPLE_PUZZLE.replace("....R.K.", "......K.")), "White has no turn that checkmates")
        // Two queens and a rook against a lone king: far too many mates.
        val easy = AiText.EXAMPLE_PUZZLE.replace("ppp.....", "........").replace(".....PPP", "QQ...PPP")
        assertContains(invalid(easy), "different mating turns")
    }

    @Test
    fun `drafts are read leniently, but a missing section is reported`() {
        val decorated = AiText.EXAMPLE_PUZZLE.replace("TITLE:", "**TITLE:**").replace("BOARD:", "BOARD:\n```") + "\n```"
        assertIs<DraftCheck.Valid>(check(decorated))
        val noSolution = AiText.EXAMPLE_PUZZLE.substringBefore("SOLUTION:")
        assertTrue(PuzzleDrafts.parse(noSolution).isFailure)
    }

    @Test
    fun `the player chooses among legal turns by number`() {
        val state = GameState.initial()
        val choices = ComputerPlayer(thinkMillis = 500).shortlist(state, size = 6)
        assertEquals(6, choices.size)
        for (choice in choices) {
            var s = state
            for (move in choice.moves) s = s.apply(assertNotNull(s.legalMovesFrom(move.from).firstOrNull { it == move }))
            assertTrue(s.canSubmit)
        }
        val prompt = AiText.choosePrompt(state, choices)
        assertContains(prompt, "You play White.")
        assertContains(prompt, "6. (L0 T1)")
        assertContains(AiText.describe(state), "Board L0 T1 (latest of timeline L0; White must move here)")

        assertEquals(2 to "Off we go!", AiText.parseChoice("3\nOff we go!", 6))
        assertEquals(1 to "Here it comes.", AiText.parseChoice("**2**. Here it comes.", 6))
        assertEquals(0 to null, AiText.parseChoice("1", 6))
        assertNull(AiText.parseChoice("7\nToo far", 6))
        assertNull(AiText.parseChoice("I'd rather not.", 6))
    }

    @Test
    fun `a checkmate on offer is listed first`() {
        val puzzle = Puzzles.byId("rook-past")!!
        val first = ComputerPlayer(thinkMillis = 3_000).shortlist(puzzle.start(), size = 5).first()
        assertTrue(first.mates)
    }

    @Test
    fun `titles and hints are read from a reply and cleaned up`() {
        val (title, hint) = assertNotNull(AiText.parseTitleAndHint("Sure!\nTITLE: **The Long Way Round**\nHINT: Look\tback.\n"))
        assertEquals("The Long Way Round", title)
        assertEquals("Look back.", hint)
        assertNull(AiText.parseTitleAndHint("TITLE: Only a title"))
    }

    @Test
    fun `a game against the AI is saved as one`() {
        val session = GameSession(GameMode(computerSide = Side.BLACK, aiOpponent = true, timelineLimit = 4))
        assertEquals(true, assertNotNull(SavedGame.decode(SavedGame.encode(session))).mode.aiOpponent)
        val plain = GameSession(GameMode(computerSide = Side.BLACK))
        assertEquals(false, assertNotNull(SavedGame.decode(SavedGame.encode(plain))).mode.aiOpponent)
    }
}
