package com.andreaserick.multiversechess

import com.andreaserick.multiversechess.online.Ticket
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONArray
import org.json.JSONObject

/** The app's rules for game codes and names against the cases the relay's tests check too (relay/test/vectors.json). */
class SharedRelayVectorsTest {
    private val vectors = JSONObject(File("../relay/test/vectors.json").readText())

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    @Test
    fun `game codes are read as the relay makes them`() {
        val codes = vectors.getJSONObject("codes")
        for (code in codes.getJSONArray("valid").strings()) assertEquals(code, Ticket.parseCode(code), code)
        for (code in codes.getJSONArray("invalid").strings()) assertNull(Ticket.parseCode(code), code)
    }

    @Test
    fun `player names follow the relay's rules`() {
        val names = vectors.getJSONObject("names")
        val valid = names.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val (name, expected) = valid.getJSONArray(i).strings()
            assertEquals(expected, Ticket.parseName(name), name)
        }
        for (name in names.getJSONArray("invalid").strings()) assertNull(Ticket.parseName(name), name)
    }
}
