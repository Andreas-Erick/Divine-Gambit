package com.andreaserick.multiversechess

import com.andreaserick.multiversechess.ai.AiException
import com.andreaserick.multiversechess.ai.AiGames
import com.andreaserick.multiversechess.ai.ChatMessage
import com.andreaserick.multiversechess.ai.LanguageModel
import com.andreaserick.multiversechess.ai.Opponent
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.Side
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/** The AI's turns, with a language model that answers as each test needs. */
class OpponentTest {
    private class FakeModel(private val answer: suspend (List<ChatMessage>) -> String) : LanguageModel {
        override val name = "Fake"
        var calls = 0

        override suspend fun chat(system: String, messages: List<ChatMessage>): String {
            calls++
            return answer(messages)
        }
    }

    /** The computer plays White, so it's its turn from the start. */
    private val game = GameSession(GameMode(computerSide = Side.WHITE, aiOpponent = true))

    @Test
    fun `the model's choice is played, with its remark`() = runBlocking {
        val model = FakeModel { "2\nA quiet developing move." }
        val turn = AiGames.turn(model, game.state)
        assertNotNull(turn.moves)
        assertNull(turn.fallback)
        assertEquals("A quiet developing move.", turn.remark)
        assertEquals(1, model.calls)
    }

    @Test
    fun `an answer without a choice is asked again, then the engine plays`() = runBlocking {
        val model = FakeModel { "I like this position a lot." }
        val turn = AiGames.turn(model, game.state)
        assertEquals(2, model.calls)
        assertNotNull(turn.moves)
        assertContains(turn.fallback.orEmpty(), "didn't pick one of the turns")
    }

    @Test
    fun `asking again can still get a choice`() = runBlocking {
        val model = FakeModel { messages -> if (messages.size == 1) "Hmm." else "3" }
        val turn = AiGames.turn(model, game.state)
        assertEquals(2, model.calls)
        assertNull(turn.fallback)
    }

    @Test
    fun `a model that fails leaves the turn to the engine, saying why`() = runBlocking {
        val turn = AiGames.turn(FakeModel { throw AiException("The server is down.") }, game.state)
        assertNotNull(turn.moves)
        assertEquals("The server is down. The engine played instead.", turn.fallback)
    }

    @Test
    fun `a model that takes too long leaves the turn to the engine`() = runBlocking {
        val turn = AiGames.turn(FakeModel { awaitCancellation() }, game.state, timeoutMillis = 50)
        assertNotNull(turn.moves)
        assertContains(turn.fallback.orEmpty(), "took too long")
    }

    @Test
    fun `the AI opponent plays the turn, and its fallback becomes the game's message`() = runBlocking {
        val played = Opponent.languageModel(FakeModel { throw AiException("No key.") }).play(game)
        assertEquals(Side.BLACK, played.next.state.sideToMove)
        assertEquals("No key. The engine played instead.", played.next.message)
        assertNull(played.remark)

        val chatty = Opponent.languageModel(FakeModel { "1\nLet's begin." }).play(game)
        assertEquals("Fake: “Let's begin.”", chatty.remark)
        assertNull(chatty.next.message)
    }

    @Test
    fun `the engine plays unless the game is against an AI that is set up`() {
        val model = FakeModel { "1" }
        assertSame(Opponent.engine, Opponent.of(game, null))
        assertSame(Opponent.engine, Opponent.of(GameSession(GameMode(computerSide = Side.WHITE)), model))
        assert(Opponent.of(game, model) !== Opponent.engine)
    }
}
