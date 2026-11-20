package com.andreaserick.multiversechess

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Structured logging and bounded crash-breadcrumb ring buffer for stateful/networked paths.
 *
 * Secret-like context keys and Bearer tokens are redacted before leaving this class, and
 * hot-loop/per-frame callers must never log here.
 */
object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Record(
        val timestamp: String,
        val level: Level,
        val domain: String,
        val event: String,
        val correlationId: String,
        val durationMs: Long? = null,
        val context: Map<String, String> = emptyMap(),
    ) {
        fun format(): String = buildString {
            append('{')
            append("\"timestamp\":\"").append(escape(timestamp)).append("\",")
            append("\"level\":\"").append(level.name).append("\",")
            append("\"domain\":\"").append(escape(domain)).append("\",")
            append("\"event\":\"").append(escape(event)).append("\",")
            append("\"correlationId\":\"").append(escape(correlationId)).append('"')
            if (durationMs != null) append(",\"durationMs\":").append(durationMs)
            if (context.isNotEmpty()) {
                append(",\"context\":{")
                context.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append('"').append(escape(k)).append("\":\"").append(escape(v)).append('"')
                }
                append('}')
            }
            append('}')
        }
    }

    private const val TAG = "MultiverseChess"
    internal const val MAX_BREADCRUMBS = 150
    private val SENSITIVE_KEY = Regex("(?i)(token|key|secret|password|auth|authorization|cookie)")
    private val BEARER_VALUE = Regex("(?i)Bearer\\s+\\S+")

    @Volatile
    var minLevel: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO

    /** Optional sink override so JVM unit tests can assert on emitted records without Android Log. */
    @Volatile
    internal var sink: ((Record, Throwable?) -> Unit)? = ::androidSink

    private val lock = Any()
    private val ring = ArrayDeque<Record>(MAX_BREADCRUMBS)
    private var crashHookInstalled = false

    fun newCorrelationId(): String = UUID.randomUUID().toString().substring(0, 12)

    fun debug(domain: String, event: String, correlationId: String = "-", durationMs: Long? = null, context: Map<String, Any?> = emptyMap()) =
        emit(Level.DEBUG, domain, event, correlationId, durationMs, context, null)

    fun info(domain: String, event: String, correlationId: String = "-", durationMs: Long? = null, context: Map<String, Any?> = emptyMap()) =
        emit(Level.INFO, domain, event, correlationId, durationMs, context, null)

    fun warn(
        domain: String,
        event: String,
        correlationId: String = "-",
        durationMs: Long? = null,
        context: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    ) = emit(Level.WARN, domain, event, correlationId, durationMs, context, cause)

    fun error(
        domain: String,
        event: String,
        correlationId: String = "-",
        durationMs: Long? = null,
        context: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    ) = emit(Level.ERROR, domain, event, correlationId, durationMs, context, cause)

    fun breadcrumbs(): List<Record> = synchronized(lock) { ring.toList() }

    internal fun clearBreadcrumbs() = synchronized(lock) { ring.clear() }

    /** Installs a process-wide fault trap once, persisting redacted breadcrumbs before delegating. */
    fun installCrashTrap(filesDir: File) {
        synchronized(lock) {
            if (crashHookInstalled) return
            crashHookInstalled = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = buildString {
                    appendLine("Unhandled fault on thread ${thread.name} at ${isoNow()}: ${throwable.javaClass.name}: ${redactValue("message", throwable.message.orEmpty())}")
                    for (entry in breadcrumbs()) appendLine(entry.format())
                }
                File(filesDir, "last_crash.log").writeText(report)
                emit(Level.ERROR, "crash", "uncaught_exception", context = mapOf("thread" to thread.name, "type" to throwable.javaClass.simpleName), cause = throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun emit(
        level: Level,
        domain: String,
        event: String,
        correlationId: String = "-",
        durationMs: Long? = null,
        context: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    ) {
        val redacted = LinkedHashMap<String, String>(context.size)
        for ((k, v) in context) {
            if (v != null) redacted[k] = redactValue(k, v.toString())
        }
        val record = Record(isoNow(), level, domain, event, correlationId, durationMs, redacted)
        synchronized(lock) {
            if (ring.size >= MAX_BREADCRUMBS) ring.removeFirst()
            ring.addLast(record)
        }
        if (level.ordinal >= minLevel.ordinal) {
            sink?.invoke(record, cause)
        }
    }

    internal fun redactValue(key: String, value: String): String {
        if (SENSITIVE_KEY.containsMatchIn(key)) return "[REDACTED]"
        return value.replace(BEARER_VALUE, "Bearer [REDACTED]").take(300)
    }

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append(' ') else append(c)
            }
        }
    }

    private fun androidSink(record: Record, cause: Throwable?) {
        val line = record.format()
        when (record.level) {
            Level.DEBUG -> Log.d(TAG, line, cause)
            Level.INFO -> Log.i(TAG, line, cause)
            Level.WARN -> Log.w(TAG, line, cause)
            Level.ERROR -> Log.e(TAG, line, cause)
        }
    }
}
