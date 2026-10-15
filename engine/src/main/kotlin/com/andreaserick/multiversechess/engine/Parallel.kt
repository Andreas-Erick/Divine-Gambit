package com.andreaserick.multiversechess.engine

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Work the engine splits across threads. Searches share nothing but immutable positions, so they
 * run side by side safely; each keeps its own memory of positions seen and its own random numbers.
 */
object Parallel {
    /**
     * Threads to use: one fewer than the processor has, so the app stays responsive, and at most
     * four, the fast cores of most phones (the rest are slow and save battery).
     */
    val threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    /** For the computer's searches: a few threads kept for the app's lifetime. */
    private val pool by lazy {
        Executors.newFixedThreadPool(threads) { task -> Thread(task, "engine search").apply { isDaemon = true } }
    }

    /** [transform] applied to each of [items], on up to [threads] threads; the results in order. */
    internal fun <T, R> map(items: List<T>, threads: Int, transform: (T) -> R): List<R> {
        if (threads <= 1 || items.size <= 1) return items.map(transform)
        return await(items.map { item -> pool.submit(Callable<R> { transform(item) }) })
    }

    /**
     * Runs [tasks] attempts at once, each on a thread of its own, and returns the first result
     * found (or null if none finds one). Each attempt gets its index and a function that turns
     * true once another has succeeded, so it can stop.
     */
    internal fun <R : Any> first(tasks: Int, attempt: (index: Int, done: () -> Boolean) -> R?): R? {
        val found = AtomicReference<R?>()
        val executor = Executors.newFixedThreadPool(tasks) { task -> Thread(task, "engine attempt").apply { isDaemon = true } }
        try {
            await((0 until tasks).map { i ->
                executor.submit(Callable<Boolean> { attempt(i) { found.get() != null }?.let { found.compareAndSet(null, it) } ?: false })
            })
        } finally {
            executor.shutdown()
        }
        return found.get()
    }

    private fun <R> await(futures: List<Future<R>>): List<R> = futures.map { future ->
        try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
