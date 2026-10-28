package io.github.notisift.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchingModelTest {
    /** Echoes each question's instructions back as the answer, so results can be traced to requests. */
    private class EchoModel(val error: Exception? = null) : DecisionModel {
        override val id = "echo"
        val calls = mutableListOf<List<DecisionRequest>>()
        override suspend fun decide(request: DecisionRequest) = decideAll(listOf(request)).single()
        override suspend fun decideAll(requests: List<DecisionRequest>): List<DecisionResult> {
            calls += requests
            error?.let { throw it }
            return requests.map { DecisionResult(it.instructions, 0.9) }
        }
    }

    private fun ask(text: String) = listOf("action", "group").map { q ->
        DecisionRequest(mapOf("title" to text), listOf("a", "b"), mapOf(DecisionParams.INSTRUCTIONS to q))
    }

    @Test fun `a lone notification goes through unchanged`() = runTest {
        val inner = EchoModel()
        val out = BatchingModel(inner, scope = backgroundScope).decideAll(ask("Sale"))
        assertEquals(1, inner.calls.size)
        assertEquals(ask("Sale"), inner.calls.single())
        assertEquals(listOf("action", "group"), out.map { it.decision })
    }

    @Test fun `a burst becomes one call and answers go back to the right caller`() = runTest {
        val inner = EchoModel()
        val model = BatchingModel(inner, scope = backgroundScope)
        val out = listOf("A", "B", "C").map { async { model.decideAll(ask(it)) } }.awaitAll()
        assertEquals(1, inner.calls.size)
        val merged = inner.calls.single()
        assertEquals(6, merged.size)
        assertTrue("one shared context", merged.all { it.context == merged[0].context })
        assertEquals(mapOf("title" to "B"), (merged[0].context["notifications"] as Map<*, *>)["n1"])
        assertEquals("Answer only about notifications.n2. group", out[2][1].decision)
        assertEquals("Answer only about notifications.n0. action", out[0][0].decision)
    }

    @Test fun `a full batch is sent without waiting`() = runTest {
        val inner = EchoModel()
        val model = BatchingModel(inner, windowMs = 60_000, maxItems = 2, scope = backgroundScope)
        listOf("A", "B").map { async { model.decideAll(ask(it)) } }.awaitAll()
        assertTrue(testScheduler.currentTime < 60_000)
        assertEquals(1, inner.calls.size)
    }

    @Test fun `requests about different things are never merged`() = runTest {
        val inner = EchoModel()
        val model = BatchingModel(inner, scope = backgroundScope)
        val scan = ask("App 1") + ask("App 2")
        listOf(async { model.decideAll(scan) }, async { model.decideAll(ask("Sale")) }).awaitAll()
        assertEquals(2, inner.calls.size)
        assertTrue(inner.calls.contains(scan))
    }

    @Test fun `a failure reaches every caller in the batch`() = runTest {
        val model = BatchingModel(EchoModel(error = LlmException("down")), scope = backgroundScope)
        val failures = listOf("A", "B").map { async { runCatching { model.decideAll(ask(it)) } } }.awaitAll()
        assertTrue(failures.all { it.exceptionOrNull() is LlmException })
    }
}
