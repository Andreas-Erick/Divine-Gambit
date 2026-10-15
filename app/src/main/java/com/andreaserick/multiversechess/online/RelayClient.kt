package com.andreaserick.multiversechess.online

import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Variant
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

/**
 * This player's seat in an online game, as handed out by the relay. [opponentName] is kept once
 * known, for the menu.
 */
data class Ticket(
    val code: String,
    val token: String,
    val side: Side,
    val variant: Variant,
    val timelineLimit: Int?,
    val opponentName: String? = null,
    /** Whether the game is played with the Inferno rule. */
    val inferno: Boolean = false,
) {
    val mode: GameMode get() = GameMode(variant = variant, timelineLimit = timelineLimit, onlineSide = side, inferno = inferno)

    /** The code as people read and type it: ABCDE-FGHJK. */
    val displayCode: String get() = displayCode(code)

    fun encode(): String = JSONObject()
        .put("code", code)
        .put("token", token)
        .put("side", side.name)
        .put("variant", variant.name)
        .put("limit", timelineLimit ?: JSONObject.NULL)
        .put("opponent", opponentName ?: JSONObject.NULL)
        .put("inferno", inferno)
        .toString()

    companion object {
        fun displayCode(code: String): String = code.chunked(5).joinToString("-")

        /**
         * The game code in [text]: typed in, with or without dashes and spaces, or at the end
         * of a pasted invitation link.
         */
        fun parseCode(text: String): String? =
            text.trim().substringAfterLast("/join/").trimEnd('/').filter { it.isLetterOrDigit() }.uppercase()
                .takeIf { it.length == CODE_LENGTH }

        /** Codes are long enough not to be guessed, since knowing one lets you take the free seat. */
        const val CODE_LENGTH = 10

        /** Longest name a player can give, as the relay allows. */
        const val MAX_NAME_LENGTH = 20

        /** [text] as a player's name: trimmed, 1 to [MAX_NAME_LENGTH] characters, nothing invisible; or null. */
        fun parseName(text: String): String? = text.trim().takeIf { name ->
            name.codePointCount(0, name.length) in 1..MAX_NAME_LENGTH &&
                name.codePoints().noneMatch { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
        }

        fun decode(text: String): Ticket? = runCatching {
            val json = JSONObject(text)
            Ticket(
                code = json.getString("code"),
                token = json.getString("token"),
                side = Side.valueOf(json.getString("side")),
                variant = Variant.valueOf(json.getString("variant")),
                timelineLimit = if (json.isNull("limit")) null else json.getInt("limit"),
                opponentName = if (json.isNull("opponent")) null else json.getString("opponent"),
                inferno = json.optBoolean("inferno", false),
            )
        }.getOrNull()
    }
}

/** A game someone was invited to, before they join it: who invites them, and as which side. */
data class Invitation(
    val code: String,
    val hostName: String,
    val hostSide: Side,
    val variant: Variant,
    val timelineLimit: Int?,
    val full: Boolean,
    val inferno: Boolean = false,
)

/** What the relay says on a game's connection; see relay/src/index.js. */
sealed interface RelayMessage {
    /**
     * The connection is open: every event so far, each with the side whose player sent it, and
     * who else is here. Events can be sent from now on.
     */
    class Hello(
        val connection: RelayConnection,
        val events: List<Pair<Side, String>>,
        val opponentJoined: Boolean,
        val opponentPresent: Boolean,
        /** The players' names, by side; a seat nobody has taken has none. */
        val names: Map<Side, String>,
    ) : RelayMessage

    /** An event was added by [sender]'s player; the relay vouches for who sent it. */
    class Event(val index: Int, val sender: Side, val event: String) : RelayMessage
    class Joined(val name: String) : RelayMessage
    class Presence(val opponentPresent: Boolean) : RelayMessage
}

class RelayConnection internal constructor(private val socket: WebSocket) {
    /** Offers [event] as event number [index]; it counts once the relay echoes it. */
    fun send(index: Int, event: String) {
        socket.send(JSONObject().put("type", "event").put("index", index).put("event", event).toString())
    }

    fun close() {
        socket.cancel()
    }
}

/** The relay refused: [status] 404 or 403 means the game doesn't exist (any more), 409 that it's full. */
class RelayException(message: String, val status: Int? = null) : IOException(message) {
    /** The game can't be reached by trying again. */
    val isFinal: Boolean get() = status == 403 || status == 404 || status == 409
}

class RelayClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Keeps the connection alive, and notices soon when it isn't.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** The link that invites a friend to the game [code]. */
    fun inviteLink(code: String): String = "$baseUrl/join/$code"

    /** Opens a new game with this player, called [name], on [side]; the friend gets the other seat by joining. */
    suspend fun create(variant: Variant, timelineLimit: Int?, side: Side, name: String, inferno: Boolean): Ticket {
        val body = JSONObject()
            .put("variant", variant.name)
            .put("limit", timelineLimit ?: JSONObject.NULL)
            .put("side", side.name)
            .put("name", name)
            .put("inferno", inferno)
        return ticket(request("/api/rooms", body.toString()))
    }

    /** What the game [code] invites its second player to. */
    suspend fun invitation(code: String): Invitation {
        val json = request("/api/rooms/$code", null)
        return try {
            Invitation(
                code = code,
                hostName = json.getString("hostName"),
                hostSide = Side.valueOf(json.getString("side")),
                variant = Variant.valueOf(json.getString("variant")),
                timelineLimit = if (json.isNull("limit")) null else json.getInt("limit"),
                full = json.getBoolean("full"),
                inferno = json.optBoolean("inferno", false),
            )
        } catch (e: Exception) {
            throw RelayException("Unexpected answer from the relay: ${e.message}")
        }
    }

    /** Takes the free seat in the game [code], as [name]. */
    suspend fun join(code: String, name: String): Ticket =
        ticket(request("/api/rooms/$code/join", JSONObject().put("name", name).toString()))

    /**
     * Connects to [ticket]'s game. The flow delivers the relay's messages, starting with
     * [RelayMessage.Hello], and fails with an [IOException] once the connection ends.
     */
    fun connect(ticket: Ticket): Flow<RelayMessage> = callbackFlow {
        val request = Request.Builder()
            .url("${baseUrl.replaceFirst("http", "ws")}/api/rooms/${ticket.code}/socket")
            // In a header rather than the address, which can end up in logs.
            .header("Authorization", "Bearer ${ticket.token}")
            .build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try {
                    parse(webSocket, JSONObject(text))
                } catch (_: Exception) {
                    null
                }
                message?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                close(RelayException(reason.ifBlank { "Connection closed" }, if (code == 4001) 404 else null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(if (response != null && response.code >= 400) RelayException("Refused", response.code) else IOException(t))
            }
        })
        awaitClose { socket.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun parse(socket: WebSocket, json: JSONObject): RelayMessage? = when (json.getString("type")) {
        "hello" -> {
            val events = json.getJSONArray("events")
            RelayMessage.Hello(
                RelayConnection(socket),
                List(events.length()) { events.getJSONObject(it).let { e -> Side.valueOf(e.getString("side")) to e.getString("event") } },
                opponentJoined = json.getBoolean("joined"),
                opponentPresent = json.getBoolean("opponent"),
                names = json.getJSONObject("names").let { names ->
                    Side.entries.mapNotNull { side -> names.optString(side.name).takeIf { it.isNotEmpty() && !names.isNull(side.name) }?.let { side to it } }.toMap()
                },
            )
        }
        "event" -> RelayMessage.Event(json.getInt("index"), Side.valueOf(json.getString("side")), json.getString("event"))
        "joined" -> RelayMessage.Joined(json.getString("name"))
        "presence" -> RelayMessage.Presence(json.getBoolean("opponent"))
        else -> null
    }

    /** POSTs [body] to [path], or GETs it without one. */
    private suspend fun request(path: String, body: String?): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }
            .build()
        http.newCall(request).execute().use { response ->
            val json = try {
                JSONObject(response.body.string())
            } catch (_: JSONException) {
                JSONObject()
            }
            if (!response.isSuccessful) throw RelayException(json.optString("error", "Refused"), response.code)
            json
        }
    }

    private fun ticket(json: JSONObject): Ticket = try {
        Ticket(
            code = json.getString("code"),
            token = json.getString("token"),
            side = Side.valueOf(json.getString("side")),
            variant = Variant.valueOf(json.getString("variant")),
            timelineLimit = if (json.isNull("limit")) null else json.getInt("limit"),
            inferno = json.optBoolean("inferno", false),
        )
    } catch (e: Exception) {
        throw RelayException("Unexpected answer from the relay: ${e.message}")
    }
}
