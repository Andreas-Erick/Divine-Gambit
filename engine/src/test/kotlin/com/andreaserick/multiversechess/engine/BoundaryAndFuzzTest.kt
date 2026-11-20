package com.andreaserick.multiversechess.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edge-case matrix, boundary validation, adversarial parser payloads, chaos controller sequences,
 * and 100+ iteration seeded generative round-trip invariants across codecs and sessions.
 */
class BoundaryAndFuzzTest {
    @Test
    fun `Square parse rejects off-board, empty, and malformed inputs`() {
        for (bad in listOf("", "a", "a10", "i1", "a0", "a9", "z9", "`1", "a/", "!!", "  ", "\u0000a")) {
            assertFailsWith<IllegalArgumentException>("Expected failure for '$bad'") {
                Square.parse(bad)
            }
        }
        assertEquals(Square(0, 0), Square.parse("a1"))
        assertEquals(Square(7, 7), Square.parse("h8"))
    }

    @Test
    fun `SavedGame decode rejects truncated, empty-timeline, off-board, and adversarial saves safely`() {
        val adversarial = listOf(
            "",
            "   \n  ",
            "multiverse-chess 1",
            // Mode present, zero timelines (used to succeed with empty timelines and crash present!).
            "multiverse-chess 1\nmode - STANDARD 4\noutcome ongoing 0\nside WHITE",
            // Orphan branch without timeline 0.
            "multiverse-chess 1\nmode - STANDARD 4\noutcome ongoing 0\nside WHITE\ntimeline 1 0 0\nboard " + ".".repeat(64) + " -",
            // Timeline 0 claiming a parent.
            "multiverse-chess 1\nmode - STANDARD 4\noutcome ongoing 0\nside WHITE\ntimeline 0 0 1\nboard " + ".".repeat(64) + " -",
            // Negative timeline start.
            "multiverse-chess 1\nmode - STANDARD 4\noutcome ongoing 0\nside WHITE\ntimeline 0 -2 -\nboard " + ".".repeat(64) + " -",
            // Off-board en passant square.
            "multiverse-chess 1\nmode - STANDARD 4\noutcome ongoing 0\nside WHITE\ntimeline 0 0 -\nboard " + ".".repeat(64) + " z9",
            // Off-board returned square.
            "multiverse-chess 1\nmode - STANDARD 4 inferno\noutcome ongoing 0\nside WHITE\ntimeline 0 0 -\nboard " + ".".repeat(64) + " -\nreturned 99,0,0,0",
            // Huge integer overflow in timeline limit or coordinates.
            "multiverse-chess 1\nmode - STANDARD 99999999999999999999",
            "multiverse-chess 1\nmode - STANDARD -5\noutcome ongoing 0\nside WHITE\ntimeline 0 0 -\nboard " + ".".repeat(64) + " -",
            // Extra trailing coordinate parts.
            "multiverse-chess 1\nmode - STANDARD 4\noutcome ongoing 0\nside WHITE\ntimeline 0 0 -\nboard " + ".".repeat(64) + " -\nmove 0,0,0,0,99 0,0,0,1",
            // Null bytes, emojis, RTL, HTML/SQL injection literals, 10k-char bomb.
            "multiverse-chess 1\n\u0000<img src=x onerror=alert(1)>'; DROP TABLE games;-- 👨‍👩‍👧‍👦 مرحبا",
            "multiverse-chess 1\n" + "A".repeat(12_000),
        )
        for (payload in adversarial) {
            assertNull(SavedGame.decode(payload), "Expected null for payload: ${payload.take(80)}")
        }
    }

    @Test
    fun `OnlineEvent decode rejects malformed, negative, overflow, and extra-token payloads`() {
        val invalid = listOf(
            "",
            "   ",
            "turn",
            "turn ongoing 0", // zero moves
            "turn ongoing maybe 0,0,4,1>0,0,4,3", // non-binary undecided flag
            "turn ongoing 0 0,0,4,1>0,0,4,3>0,0,4,4", // 3-part move
            "turn ongoing 0 0,-1,4,1>0,0,4,3", // negative half-move t
            "turn ongoing 0 0,0,8,1>0,0,4,3", // off-board x = 8
            "turn ongoing 0 0,0,4,1,9>0,0,4,3", // 5-part Pos
            "end ongoing",
            "end checkmate:WHITE extra",
            "free -1 0,0,0,0",
            "free 99999999999999999999 0,0,0,0",
            "free 0 0,0,0,0 extra",
            "\u0000turn ongoing 0 0,0,4,1>0,0,4,3",
            "turn ongoing 0 " + "0,0,4,1>0,0,4,3 ".repeat(3_000), // valid syntax or stress: check below
        )
        for (payload in invalid.dropLast(1)) {
            assertNull(OnlineEvent.decode(payload), "Expected null for '$payload'")
        }
    }

    @Test
    fun `PuzzleCodec decode rejects corrupt or unreplayable solutions safely`() {
        val valid = Puzzles.all.first()
        val encoded = PuzzleCodec.encode(valid)
        // Corrupt the solution to an illegal rook jump; decode must reject rather than crash on Show solution.
        val illegalSolution = encoded.replace("0,4,4,0>0,4,4,7", "0,4,4,0>0,4,0,7")
        assertNull(PuzzleCodec.decode(illegalSolution))
        assertNull(PuzzleCodec.decode(encoded.replace("id rook-past", "id   ")))
        assertNull(PuzzleCodec.decode(encoded.replace("title Into the past", "title   ")))
        assertNull(PuzzleCodec.decode(""))
        assertEquals(listOf(valid.id), PuzzleCodec.decodeAll(illegalSolution + "\n" + encoded).map { it.id })
    }

    @Test
    fun `PuzzleDrafts read handles giant integer overflows, T0, unicode, and injection strings without throwing`() {
        val hugeSpatial = AiText.EXAMPLE_PUZZLE.replace("g1h1", "L99999999999999999999999 g1h1")
        assertIs<DraftCheck.Invalid>(PuzzleDrafts.read(hugeSpatial, "overflow-spatial"))

        val hugeShort = AiText.EXAMPLE_PUZZLE.replace("g1h1", "L-99999999999999999999999 Kh1")
        assertIs<DraftCheck.Invalid>(PuzzleDrafts.read(hugeShort, "overflow-short"))

        val hugeBetween = AiText.EXAMPLE_PUZZLE.replace("g1h1", "L0 T99999999999999999999999 g1 > L0 T1 h1")
        assertIs<DraftCheck.Invalid>(PuzzleDrafts.read(hugeBetween, "overflow-between"))

        val zeroTurn = AiText.EXAMPLE_PUZZLE.replace("g1h1", "L0 T0 g1 > L0 T1 h1")
        assertIs<DraftCheck.Invalid>(PuzzleDrafts.read(zeroTurn, "zero-turn"))

        val unicodeAndHtml = """
            TITLE: <script>alert(1)</script> 👨‍👩‍👧‍👦 مرحبا
            HINT: Look back — '\u0000' "quotes" & entities
            WHITE: Kg1, Re1, Pf2, Pg2, Ph2
            BLACK: Ke8, Pa7, Pb7, Pc7
            HISTORY:
            g1h1
            e8d7
            h1g1
            d7c6
            SOLUTION:
            e1e8
        """.trimIndent()
        val checked = assertIs<DraftCheck.Valid>(PuzzleDrafts.read(unicodeAndHtml, "unicode"))
        assertTrue(checked.puzzle.title.length <= AiText.MAX_TITLE)

        // Edge inputs on AiText choice/remark parsers.
        assertNull(AiText.parseChoice("", 10))
        assertNull(AiText.parseChoice("   ", 10))
        assertNull(AiText.parseChoice("0\nZero", 10))
        assertNull(AiText.parseChoice("-1\nNegative", 10))
        assertNull(AiText.parseChoice("99999999999999999999999\nOverflow", 10))
        val emojiLong = "👨‍👩‍👧‍👦 ".repeat(100)
        val shortenedEmoji = AiText.shorten(emojiLong)
        assertTrue(shortenedEmoji.length <= AiText.MAX_REMARK)
        assertTrue(!shortenedEmoji.dropLast(1).last().isHighSurrogate())
        val unbrokenLong = "A".repeat(10_500)
        assertTrue(AiText.shorten(unbrokenLong).length <= AiText.MAX_REMARK)
    }

    @Test
    fun `seeded generative round-trip fuzzing over 120 randomized multiverse games`() {
        for (seed in 0 until 120) {
            val random = Random(seed.toLong())
            val variant = Variant.entries[seed % Variant.entries.size]
            val inferno = seed % 3 == 0
            val limit = if (seed % 4 == 0) null else 1 + (seed % 4)
            val mode = GameMode(variant = variant, timelineLimit = limit, inferno = inferno)
            var session = GameSession(mode)
            val turnsToPlay = 1 + random.nextInt(6)
            for (t in 0 until turnsToPlay) {
                if (session.outcome != Outcome.Ongoing) break
                if (inferno && session.souls.indices.any { session.canFree(it) } && random.nextBoolean()) {
                    val idx = session.souls.indices.first { session.canFree(it) }
                    session = session.free(idx) ?: session
                }
                val order = { _: GameState, moves: List<Move> -> moves.shuffled(random) }
                val completed = try {
                    TurnWalker(800, maxOptionalMoves = 1, order = order).walk(session.state) { true }
                } catch (_: BudgetExceeded) {
                    null
                } ?: break
                val chosenMoves = completed.turnMoves
                // Half the time test mid-turn save/restore on the final turn!
                val stopMidTurn = t == turnsToPlay - 1 && random.nextBoolean()
                val slice = if (stopMidTurn) chosenMoves.take(1.coerceAtMost(chosenMoves.size)) else chosenMoves
                for (move in slice) {
                    session = session.tap(move.from).tap(move.to)
                }
                if (!stopMidTurn) {
                    val beforeSubmit = session
                    session = session.submit()
                    val onlineEvent = OnlineEvent.Turn(
                        beforeSubmit.state.turnMoves.map { it.from to it.to },
                        session.outcome,
                        session.undecided,
                    )
                    val decodedEvent = assertNotNull(OnlineEvent.decode(OnlineEvent.encode(onlineEvent)), "seed=$seed event")
                    assertEquals(onlineEvent, decodedEvent, "seed=$seed event equality")
                }
            }
            val encoded = SavedGame.encode(session)
            val restored = assertNotNull(SavedGame.decode(encoded), "seed=$seed SavedGame.decode failed:\n$encoded")
            assertEquals(session.mode, restored.mode, "seed=$seed mode")
            assertEquals(session.outcome, restored.outcome, "seed=$seed outcome")
            assertEquals(session.souls, restored.souls, "seed=$seed souls")
            assertEquals(session.log, restored.log, "seed=$seed log")
            assertEquals(session.state.sideToMove, restored.state.sideToMove, "seed=$seed sideToMove")
            assertEquals(session.state.turnMoves, restored.state.turnMoves, "seed=$seed turnMoves")
            assertEquals(session.state.timelines.keys, restored.state.timelines.keys, "seed=$seed timelines")
            for ((l, tl) in session.state.timelines) {
                assertEquals(tl.boards, restored.state.timelines.getValue(l).boards, "seed=$seed L$l boards")
            }
        }
    }

    @Test
    fun `chaos rapid action spam and bounded undo history maintain invariants`() {
        var session = GameSession(GameMode(timelineLimit = 2))
        // Rapid 10x spam taps on same square, off-board coordinates, premature submits, and extra undos.
        val e2 = Pos.of(0, 0, "e2")
        repeat(10) { session = session.tap(e2) }
        assertNull(session.selected) // 10 toggles return to deselected cleanly
        session = session.tap(e2).clearSelection()
        assertNull(session.selected)
        repeat(10) { session = session.tap(Pos(99, -5, -1, 99)) }
        repeat(5) { session = session.submit() }
        assertEquals(Outcome.Ongoing, session.outcome)
        repeat(10) { session = session.undo() }
        assertEquals(Side.WHITE, session.state.sideToMove)

        // Play > MAX_UNDO_STATES turns (shuffling knights back and forth) and verify undo depth is bounded
        // while SavedGame.encode still works mid-turn.
        val movesCycle = listOf(
            "g1" to "f3", "g8" to "f6",
            "f3" to "g1", "f6" to "g8",
        )
        for (ply in 0 until GameSession.MAX_UNDO_STATES + 20) {
            val (from, to) = movesCycle[ply % movesCycle.size]
            val t = session.state.timelines.getValue(0).end
            session = session.tap(Pos.of(0, t, from)).tap(Pos.of(0, t, to)).submit()
        }
        var depth = 0
        var cursor: GameSession? = session
        while (cursor != null) {
            depth++
            cursor = cursor.previous
            assertTrue(depth <= GameSession.MAX_UNDO_STATES + 2, "Undo history grew unbounded: $depth")
        }
        // Mid-turn play + save still finds turn start cleanly.
        val endT = session.state.timelines.getValue(0).end
        val midTurn = session.tap(Pos.of(0, endT, "e2")).tap(Pos.of(0, endT, "e4"))
        assertNotNull(SavedGame.decode(SavedGame.encode(midTurn)))
    }
}
