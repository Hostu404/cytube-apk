package com.cytube.mobile.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedLookupsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** Until [condition] holds, giving the callers started meanwhile their turn. */
    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) yield()
    }

    @Test
    fun `callers asking for the same thing at once share one lookup`() = runBlocking {
        val lookups = SharedLookups<String, Int>(scope)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val asked = AtomicInteger()
        val answers = (1..5).map {
            async {
                asked.incrementAndGet()
                lookups.get("yt:abc") { calls.incrementAndGet(); gate.await(); 42 }
            }
        }
        until { asked.get() == 5 && calls.get() == 1 }
        gate.complete(Unit)
        assertEquals(List(5) { 42 }, answers.awaitAll())
        assertEquals(1, calls.get())
        until { lookups.size == 0 }
    }

    @Test
    fun `different things are looked up separately`() = runBlocking {
        val lookups = SharedLookups<String, String>(scope)
        val calls = AtomicInteger()
        val a = async { lookups.get("a") { calls.incrementAndGet(); "A" } }
        val b = async { lookups.get("b") { calls.incrementAndGet(); "B" } }
        assertEquals("A", a.await())
        assertEquals("B", b.await())
        assertEquals(2, calls.get())
    }

    @Test
    fun `once a lookup is over, the next call looks again`() = runBlocking {
        val lookups = SharedLookups<String, Int>(scope)
        val calls = AtomicInteger()
        assertEquals(1, lookups.get("a") { calls.incrementAndGet() })
        until { lookups.size == 0 }
        assertEquals(2, lookups.get("a") { calls.incrementAndGet() })
    }

    @Test
    fun `one caller giving up doesn't stop the lookup for the others`() = runBlocking {
        val lookups = SharedLookups<String, Int>(scope)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val first = async { lookups.get("a") { calls.incrementAndGet(); gate.await(); 7 } }
        until { calls.get() == 1 }
        val second = async { lookups.get("a") { calls.incrementAndGet(); -1 } }
        yield()
        first.cancel()
        gate.complete(Unit)
        assertEquals(7, second.await())
        assertEquals(1, calls.get())
        assertTrue(first.isCancelled)
    }

    @Test
    fun `after forget, the next call starts a lookup of its own`() = runBlocking {
        val lookups = SharedLookups<String, Int>(scope)
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val old = async { lookups.get("a") { calls.incrementAndGet(); gate.await(); 1 } }
        until { calls.get() == 1 }
        lookups.forget("a")
        assertEquals(2, lookups.get("a") { calls.incrementAndGet(); 2 })
        gate.complete(Unit)
        assertEquals(1, old.await())
    }

    @Test
    fun `a lookup that fails fails for everyone waiting on it, and the next call tries again`() = runBlocking {
        val lookups = SharedLookups<String, Int>(scope)
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val waiting = (1..2).map {
            async {
                runCatching { lookups.get("a") { calls.incrementAndGet(); gate.await(); error("offline") } }
            }
        }
        until { calls.get() == 1 }
        yield()
        gate.complete(Unit)
        for (result in waiting.awaitAll()) {
            assertEquals("offline", result.exceptionOrNull()?.message)
        }
        assertEquals(1, calls.get())
        until { lookups.size == 0 }
        assertEquals(5, lookups.get("a") { calls.incrementAndGet(); 5 })
    }
}
