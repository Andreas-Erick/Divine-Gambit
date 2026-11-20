package com.andreaserick.multiversechess.ai

import com.andreaserick.multiversechess.await
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One message of a conversation with a language model: the user's, or the model's own reply. */
data class ChatMessage(val fromModel: Boolean, val text: String)

/** A language model the game can talk to. */
interface LanguageModel {
    /** What the game calls it: "Gemini", the model's name on a computer, or the phone's model. */
    val name: String

    /** The model's reply to the last of [messages], given [system] instructions. Throws [AiException] on failure. */
    suspend fun chat(system: String, messages: List<ChatMessage>): String
}

/** Talking to a model failed; [message] says why, for the player. */
class AiException(message: String, cause: Throwable? = null) : IOException(message, cause)

private val json = "application/json".toMediaType()

/** Language models can be slow: generous timeouts, especially for models on a computer or phone. */
internal val aiHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

/** Removes a reasoning model's thinking, which some servers include in the reply. */
internal fun withoutThinking(text: String): String =
    text.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "").trim()

private val TRANSIENT_HTTP_CODES = setOf(408, 429, 502, 503, 504)
private val VALID_MODEL_ID = Regex("""^[A-Za-z0-9._:-]{1,128}$""")

private fun buildRequest(url: String, configure: Request.Builder.() -> Unit): Request {
    val parsed = url.toHttpUrlOrNull()
        ?: throw AiException("That server address isn't valid (for example, http://192.168.1.20:1234).")
    return try {
        Request.Builder().url(parsed).apply(configure).build()
    } catch (_: IllegalArgumentException) {
        throw AiException("The server address or API key contains invalid characters.")
    }
}

private suspend fun send(makeRequest: () -> Request, describe: (Int, JSONObject?) -> String): JSONObject = withContext(Dispatchers.IO) {
    val request = makeRequest()
    val maxAttempts = if (request.method == "GET") 3 else 1
    var backoffMs = 500L
    var lastException: AiException? = null
    for (attempt in 1..maxAttempts) {
        try {
            val result = aiHttp.newCall(request).await().use { response ->
                val body = response.body.string()
                val parsed = try {
                    JSONObject(body)
                } catch (_: JSONException) {
                    null
                }
                if (!response.isSuccessful) {
                    val code = response.code
                    if (attempt < maxAttempts && code in TRANSIENT_HTTP_CODES) {
                        val retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
                        val waitMs = retryAfterSec?.times(1_000L)?.coerceIn(250L, 5_000L)
                            ?: (backoffMs + Random.nextLong(backoffMs / 3 + 1))
                        backoffMs = (backoffMs * 2).coerceAtMost(4_000L)
                        delay(waitMs)
                        return@use null
                    }
                    throw AiException(describe(code, parsed))
                }
                parsed ?: throw AiException("The model's answer couldn't be read.")
            }
            if (result != null) return@withContext result
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            lastException = AiException("Couldn't reach the model: ${e.message ?: "no connection"}.", e)
            if (attempt < maxAttempts) {
                delay(backoffMs + Random.nextLong(backoffMs / 3 + 1))
                backoffMs = (backoffMs * 2).coerceAtMost(4_000L)
            }
        }
    }
    throw lastException ?: AiException("Couldn't reach the model.")
}

/** Google's Gemini models, through the Gemini API with the player's API key. */
class GeminiModel(private val apiKey: String, private val model: String) : LanguageModel {
    override val name: String get() = "Gemini"

    override suspend fun chat(system: String, messages: List<ChatMessage>): String {
        if (!VALID_MODEL_ID.matches(model)) throw AiException("Invalid Gemini model name.")
        // Validate endpoint and API key header before serializing prompt JSON.
        val endpoint = "$BASE/models/$model:generateContent"
        buildRequest(endpoint) { header("x-goog-api-key", apiKey) }
        val contents = JSONArray()
        for (message in messages) {
            contents.put(JSONObject().put("role", if (message.fromModel) "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", message.text))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.8))
        val reply = send(
            makeRequest = {
                buildRequest(endpoint) {
                    header("x-goog-api-key", apiKey)
                    post(body.toString().toRequestBody(json))
                }
            },
            describe = ::describeError,
        )
        val parts = reply.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException("Gemini didn't answer" + (reply.optJSONObject("promptFeedback")?.optString("blockReason")?.let { " ($it)" } ?: "") + ".")
        // Thinking models send their thoughts as parts of their own.
        val text = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
        return withoutThinking(text).ifBlank { throw AiException("Gemini's answer was empty.") }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        /** The Gemini models [apiKey] can use to write text, newest first. */
        suspend fun models(apiKey: String): List<String> {
            val list = send(
                makeRequest = { buildRequest("$BASE/models?pageSize=200") { header("x-goog-api-key", apiKey); get() } },
                describe = ::describeError,
            ).optJSONArray("models") ?: JSONArray()
            return (0 until list.length()).map { list.getJSONObject(it) }
                .filter { model ->
                    val methods = model.optJSONArray("supportedGenerationMethods")
                    (0 until (methods?.length() ?: 0)).any { methods!!.getString(it) == "generateContent" }
                }
                .map { it.getString("name").removePrefix("models/") }
                .filter { it.startsWith("gemini") && listOf("embedding", "tts", "image", "audio", "live", "robotics").none(it::contains) }
                .sortedWith(compareByDescending<String> { version(it) }.thenBy { it.contains("lite") }.thenBy { it.length })
        }

        /** The model to suggest: the newest "flash" model, quick and cheap enough for a game. */
        fun suggested(models: List<String>): String? =
            models.firstOrNull { it.contains("flash") && !it.contains("lite") && !it.contains("preview") && !it.contains("exp") }
                ?: models.firstOrNull { it.contains("flash") } ?: models.firstOrNull()

        private fun version(name: String): Double = Regex("""gemini-(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        private fun describeError(code: Int, body: JSONObject?): String {
            val message = body?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            return when (code) {
                400, 401, 403 -> "Gemini refused the API key" + (message?.let { ": $it" } ?: ".")
                429 -> "Gemini says you've reached your usage limit for now. Try again later."
                else -> "Gemini answered with an error ($code)" + (message?.let { ": $it" } ?: ".")
            }
        }
    }
}

/**
 * A model served from a computer, such as LM Studio or Ollama, through their OpenAI-compatible API
 * at [baseUrl] (e.g. http://192.168.1.20:1234).
 */
class ServerModel(baseUrl: String, private val model: String, private val apiKey: String?) : LanguageModel {
    private val api = apiBase(baseUrl)

    override val name: String get() = shortName(model)

    override suspend fun chat(system: String, messages: List<ChatMessage>): String {
        val endpoint = "$api/chat/completions"
        // Validate URL and authorization header before serializing prompt JSON.
        buildRequest(endpoint) { authorized(apiKey) }
        val list = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (message in messages) list.put(JSONObject().put("role", if (message.fromModel) "assistant" else "user").put("content", message.text))
        val body = JSONObject().put("model", model).put("messages", list).put("temperature", 0.8).put("stream", false)
        val reply = send(
            makeRequest = { buildRequest(endpoint) { authorized(apiKey); post(body.toString().toRequestBody(json)) } },
            describe = ::describeError,
        )
        val text = reply.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        return withoutThinking(text.orEmpty()).ifBlank { throw AiException("The model's answer was empty.") }
    }

    companion object {
        /** The models the server at [baseUrl] offers. */
        suspend fun models(baseUrl: String, apiKey: String?): List<String> {
            val data = send(
                makeRequest = { buildRequest("${apiBase(baseUrl)}/models") { authorized(apiKey); get() } },
                describe = ::describeError,
            ).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).map { data.getJSONObject(it).getString("id") }.filter { !it.contains("embed") }
        }

        /**
         * A short name for the model [id] to show in the game: its family and size, such as
         * "Deepseek 14B" for "deepseek-r1-distill-qwen-14b" or "Llama3.2 3B" for "llama3.2:3b".
         */
        fun shortName(id: String): String {
            val words = id.substringAfterLast('/').split('-', '_', ':', ' ').filter { it.isNotBlank() }
            val family = words.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: return "Your model"
            val size = words.drop(1).lastOrNull { it.matches(Regex("""(?i)\d+(\.\d+)?[bm]""")) }?.uppercase()
            return listOfNotNull(family, size).joinToString(" ")
        }

        /** [url] as the API's base, ending in /v1. */
        fun apiBase(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            return if (withScheme.endsWith("/v1")) withScheme else "$withScheme/v1"
        }

        private fun Request.Builder.authorized(key: String?) = apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }

        private fun describeError(code: Int, body: JSONObject?): String {
            val message = body?.optJSONObject("error")?.optString("message") ?: body?.optString("error")
            return when (code) {
                401, 403 -> "The server refused the API key."
                404 -> "The server doesn't know that model" + (message?.let { ": $it" } ?: ".")
                else -> "The server answered with an error ($code)" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
            }
        }
    }
}

/**
 * A model that runs on the phone, with Google's LiteRT-LM runtime, from a .litertlm [file]. The
 * model is loaded once (which takes a while) and kept while the app runs. [freeMemory] says how
 * many bytes the phone can spare right now, before Android starts closing apps.
 */
class OnDeviceModel(
    private val file: File,
    override val name: String,
    private val cacheDir: File,
    private val freeMemory: () -> Long,
) : LanguageModel {
    override suspend fun chat(system: String, messages: List<ChatMessage>): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val engine = engineFor(file, cacheDir, freeMemory)
            val history = messages.dropLast(1).map { if (it.fromModel) Message.model(it.text) else Message.user(it.text) }
            try {
                // Thinking out loud first makes a phone's model far too slow for a game; answers are short.
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = history,
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
                engine.createConversation(config).use { conversation ->
                    val reply = conversation.sendMessage(messages.last().text)
                    val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    withoutThinking(text).ifBlank { throw AiException("The model's answer was empty.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                throw e
            } catch (e: Exception) {
                throw AiException("The model on this phone failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    companion object {
        /** Enough for a puzzle; a turn's answer is much shorter. */
        private const val MAX_OUTPUT_TOKENS = 700

        /** One model at a time: they take a lot of memory. */
        private val lock = Mutex()
        private var loaded: Pair<File, Engine>? = null

        /**
         * Memory a model needs to load and run, besides its file: loading one with too little free
         * makes Android close the app outright, so it's checked first.
         */
        private fun memoryNeeded(file: File): Long = file.length() + file.length() / 2 + 300_000_000L

        private fun engineFor(file: File, cacheDir: File, freeMemory: () -> Long): Engine {
            loaded?.let { (current, engine) -> if (current == file) return engine else engine.close() }
            loaded = null
            if (!file.exists()) throw AiException("The model file is missing; download it again in AI settings.")
            val needed = memoryNeeded(file)
            val free = freeMemory()
            if (free < needed) {
                throw AiException(
                    "There isn't enough free memory to run the model on this phone (it needs about " +
                        "${needed / 1_000_000} MB, ${free / 1_000_000} MB is free). Close other apps, or choose a smaller model.",
                )
            }
            val engine = try {
                Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = cacheDir.absolutePath))
                    .also { it.initialize() }
            } catch (e: Exception) {
                throw AiException("The model couldn't be loaded: ${e.message ?: e.javaClass.simpleName}. Is there enough free memory?", e)
            }
            loaded = file to engine
            return engine
        }

        /** Unloads the model, freeing its memory (when it's deleted, or another is chosen). */
        suspend fun release() = lock.withLock {
            loaded?.second?.close()
            loaded = null
        }

        /** Non-suspending best-effort cleanup when the owning ViewModel is cleared. */
        fun closeIdle() {
            if (!lock.tryLock()) return
            try {
                loaded?.second?.close()
                loaded = null
            } finally {
                lock.unlock()
            }
        }
    }
}
