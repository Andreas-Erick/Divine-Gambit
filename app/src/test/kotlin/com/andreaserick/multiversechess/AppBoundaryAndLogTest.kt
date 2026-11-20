package com.andreaserick.multiversechess

import com.andreaserick.multiversechess.ai.AiException
import com.andreaserick.multiversechess.ai.ChatMessage
import com.andreaserick.multiversechess.ai.GeminiModel
import com.andreaserick.multiversechess.ai.ServerModel
import com.andreaserick.multiversechess.ai.withoutThinking
import com.andreaserick.multiversechess.online.Ticket
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AppBoundaryAndLogTest {
    @Test
    fun `Ticket parseCode handles plain codes, hyphenated codes, full URLs, and rejects garbage`() {
        assertEquals("ABCDEFGHJK", Ticket.parseCode("abcde-fghjk"))
        assertEquals("23456789AB", Ticket.parseCode("  https://multiverse-chess.workers.dev/join/23456-789ab/  "))
        assertEquals("ABCDE-FGHJK", Ticket.displayCode("ABCDEFGHJK"))
        assertNull(Ticket.parseCode(""))
        assertNull(Ticket.parseCode("   "))
        assertNull(Ticket.parseCode("SHORT"))
        assertNull(Ticket.parseCode("TOOLONGCODE123"))
        assertNull(Ticket.parseCode("https://example.com/join/" + "A".repeat(10_000)))
    }

    @Test
    fun `Ticket parseName handles Unicode emojis and RTL while rejecting control, format, empty, and overlong inputs`() {
        assertEquals("Andreas", Ticket.parseName("  Andreas  "))
        assertEquals("مرحبا", Ticket.parseName("مرحبا"))
        // 4-codepoint family emoji x 3 = 12 codepoints (21 UTF-16 chars > 20, yet <= 20 codepoints!).
        val families = "👨‍👩‍👧‍👦👨‍👩‍👧‍👦"
        assertEquals(families, Ticket.parseName(families))
        assertEquals("A".repeat(20), Ticket.parseName("A".repeat(20)))

        // Rejects empty, whitespace, >20 codepoints, null bytes, BiDi overrides, and zero-width format chars.
        assertNull(Ticket.parseName(""))
        assertNull(Ticket.parseName("    "))
        assertNull(Ticket.parseName("A".repeat(21)))
        assertNull(Ticket.parseName("bad\u0000name"))
        assertNull(Ticket.parseName("bad\nname"))
        assertNull(Ticket.parseName("bad\u200Bname")) // Cf zero-width space
        assertNull(Ticket.parseName("\u202Eevil")) // Cf right-to-left override
    }

    @Test
    fun `LanguageModel helpers normalize URLs, strip thinking blocks, and turn bad URLs or keys into AiException`() {
        assertEquals("http://192.168.1.20:1234/v1", ServerModel.apiBase("  192.168.1.20:1234/ "))
        assertEquals("https://llm.example.com/v1", ServerModel.apiBase("https://llm.example.com/v1/"))
        assertEquals("Deepseek 14B", ServerModel.shortName("deepseek-r1-distill-qwen-14b"))
        assertEquals("Llama3.2 3B", ServerModel.shortName("llama3.2:3b"))
        assertEquals("Your model", ServerModel.shortName("///"))

        assertEquals(
            "gemini-2.5-flash",
            GeminiModel.suggested(listOf("gemini-2.5-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-lite")),
        )
        assertEquals("4\nBold move!", withoutThinking("<think>\nLet me reason...\n</think>\n4\nBold move!"))

        // Malformed URLs, bad ports, path-injection model IDs, and non-ASCII header chars throw AiException, never IllegalArgumentException.
        runBlocking {
            for (badUrl in listOf("", "   ", "http://", "http://[::1", "192.168.1.20:99999", "ftp://example.com")) {
                assertFailsWith<AiException>("Expected AiException for URL '$badUrl'") {
                    ServerModel.models(badUrl, null)
                }
            }
            assertFailsWith<AiException> {
                ServerModel("http://127.0.0.1:1234", "llama", "bad\nheader\u0000key")
                    .chat("sys", listOf(ChatMessage(false, "hi")))
            }
            assertFailsWith<AiException> {
                GeminiModel("valid-key", "../../evil?x=1")
                    .chat("sys", listOf(ChatMessage(false, "hi")))
            }
            assertFailsWith<AiException> {
                GeminiModel("bad\nkey\u2603", "gemini-2.5-flash")
                    .chat("sys", listOf(ChatMessage(false, "hi")))
            }
        }
    }

    @Test
    fun `AppLog redacts secrets and bounds breadcrumb ring buffer`() {
        val captured = ArrayList<AppLog.Record>()
        val previousSink = AppLog.sink
        val previousLevel = AppLog.minLevel
        AppLog.clearBreadcrumbs()
        AppLog.minLevel = AppLog.Level.DEBUG
        AppLog.sink = { record, _ -> captured += record }
        try {
            AppLog.info(
                domain = "online",
                event = "connect_attempt",
                correlationId = "cid-123",
                durationMs = 42,
                context = mapOf(
                    "code" to "ABCDEFGHJK",
                    "token" to "super-secret-uuid",
                    "apiKey" to "AIzaSecretKey",
                    "header" to "Authorization: Bearer secret-jwt-123",
                ),
            )
            val json = captured.single().format()
            assertContains(json, "\"event\":\"connect_attempt\"")
            assertContains(json, "\"correlationId\":\"cid-123\"")
            assertContains(json, "\"durationMs\":42")
            assertFalse("super-secret-uuid" in json)
            assertFalse("AIzaSecretKey" in json)
            assertFalse("secret-jwt-123" in json)
            assertContains(json, "[REDACTED]")

            // Emit 220 more events and verify bounded ring buffer eviction at MAX_BREADCRUMBS.
            repeat(220) { i ->
                AppLog.debug("test", "step_$i", correlationId = "c-$i")
            }
            val crumbs = AppLog.breadcrumbs()
            assertEquals(AppLog.MAX_BREADCRUMBS, crumbs.size)
            assertEquals("step_70", crumbs.first().event)
            assertEquals("step_219", crumbs.last().event)
        } finally {
            AppLog.clearBreadcrumbs()
            AppLog.sink = previousSink
            AppLog.minLevel = previousLevel
        }
    }
}
