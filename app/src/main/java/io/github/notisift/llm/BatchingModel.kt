package io.github.notisift.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fewer AI calls for bursts (a group chat waking up, a reconnect sweep): notifications decided within
 * [windowMs] of each other go to the model as one call. Their contexts are merged under
 * `notifications.n0`, `n1`…, and each question names the one it is about. A lone notification goes
 * through unchanged, so the common case keeps the exact single-notification prompt.
 */
class BatchingModel(
    private val inner: DecisionModel,
    private val windowMs: Long = 300,
    private val maxItems: Int = 8,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DecisionModel {
    override val id: String get() = inner.id

    private class Pending(val requests: List<DecisionRequest>) {
        val result = CompletableDeferred<List<DecisionResult>>()
    }

    private val lock = Mutex()
    private var queue = emptyList<Pending>()

    override suspend fun decide(request: DecisionRequest): DecisionResult = decideAll(listOf(request)).single()

    override suspend fun decideAll(requests: List<DecisionRequest>): List<DecisionResult> {
        if (requests.isEmpty()) return emptyList()
        val me = Pending(requests)
        val size = lock.withLock { queue = queue + me; queue.size }
        // The flush runs in our own scope, so a caller timing out never strands the others in its batch.
        if (size == 1) scope.launch { delay(windowMs); flush() }
        if (size >= maxItems) scope.launch { flush() }
        return me.result.await()
    }

    private suspend fun flush() {
        val batch = lock.withLock { queue.also { queue = emptyList() } }
        // Only callers asking about one thing can share a merged context; anything else (an app scan) goes alone.
        val (mergeable, solo) = batch.partition { p -> p.requests.all { it.context == p.requests[0].context } }
        solo.forEach { run(listOf(it)) }
        if (mergeable.isNotEmpty()) run(mergeable)
    }

    private suspend fun run(batch: List<Pending>) {
        try {
            if (batch.size == 1) {
                batch[0].result.complete(inner.decideAll(batch[0].requests))
                return
            }
            val answers = inner.decideAll(merge(batch.map { it.requests }))
            var at = 0
            batch.forEach { p -> p.result.complete(answers.subList(at, at + p.requests.size).toList()); at += p.requests.size }
        } catch (e: CancellationException) {
            batch.forEach { it.result.cancel(e) }
            throw e
        } catch (e: Exception) {
            batch.forEach { it.result.completeExceptionally(e) }
        }
    }

    companion object {
        /** One shared context for the whole batch, so providers answer every question in one call. */
        fun merge(batch: List<List<DecisionRequest>>): List<DecisionRequest> {
            val context = mapOf("notifications" to batch.withIndex().associate { (i, rs) -> "n$i" to rs.first().context })
            return batch.withIndex().flatMap { (i, rs) ->
                rs.map { r -> r.copy(context = context, parameters = r.parameters + (DecisionParams.INSTRUCTIONS to "Answer only about notifications.n$i. ${r.instructions}")) }
            }
        }
    }
}
