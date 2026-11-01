package io.github.notisift.engine

import io.github.notisift.llm.DecisionModel
import io.github.notisift.llm.DecisionRequest
import io.github.notisift.llm.DecisionResult
import io.github.notisift.llm.LlmException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class TidyStore(
    val modes: MutableMap<String, AppMode> = mutableMapOf(),
    val cache: MutableMap<String, Verdict> = mutableMapOf(),
) : EngineStore {
    override suspend fun appMode(packageName: String) = modes[packageName]
    override suspend fun appInstructions(packageName: String) = ""
    override suspend fun appCategory(packageName: String) = ""
    override suspend fun cached(templateKey: String) = cache[templateKey]
    override suspend fun remember(templateKey: String, verdict: Verdict) { cache[templateKey] = verdict.copy(source = Source.CACHE) }
}

/** Answers [action] with [p] for every notification; fails from call number [failFrom] on. */
private class PhoneModel(var action: String = "bundle", var p: Double = 0.95, var failFrom: Int = Int.MAX_VALUE) : DecisionModel {
    override val id = "phone"
    var calls = 0
    override suspend fun decide(request: DecisionRequest) = decideAll(listOf(request)).single()
    override suspend fun decideAll(requests: List<DecisionRequest>): List<DecisionResult> {
        if (++calls >= failFrom) throw LlmException("Background use blocked")
        return listOf(DecisionResult(action, p), DecisionResult("promotion", 0.9))
    }
}

class OnPhoneTidyTest {
    private val inbox = EngineSettings(inboxMode = true)
    private fun promo(i: Int, pkg: String = "com.shop") =
        NotificationInput("k$i", pkg, "Shop", "deals", title = "Deal number ${"x".repeat(i)}", text = "Sale on item $i")

    @Test fun `a confident answer is learned for the next one like it`() = runTest {
        val store = TidyStore()
        val engine = DecisionEngine(store, { null })
        val n = promo(1)
        assertEquals(DecisionEngine.Learned.YES, engine.learn(n, inbox, PhoneModel()))
        val next = engine.decide(n.copy(key = "later", text = "Sale on item 99"), inbox)
        assertEquals("the next sale is sorted by what was learned, with no AI", Source.CACHE, next?.source)
        assertEquals(Action.BUNDLE, next?.action)
    }

    @Test fun `unsure answers and failures are not learned`() = runTest {
        val store = TidyStore()
        val engine = DecisionEngine(store, { null })
        assertEquals(DecisionEngine.Learned.NOT_SURE, engine.learn(promo(1), inbox, PhoneModel(p = 0.6)))
        assertEquals(DecisionEngine.Learned.FAILED, engine.learn(promo(2), inbox, PhoneModel(failFrom = 1)))
        assertTrue(store.cache.isEmpty())
    }

    @Test fun `the user's choices and conversations are never second-guessed`() = runTest {
        val store = TidyStore(modes = mutableMapOf("com.fixed" to AppMode.BUNDLE))
        val engine = DecisionEngine(store, { null })
        val model = PhoneModel()
        assertEquals(DecisionEngine.Learned.SKIPPED, engine.learn(promo(1, pkg = "com.fixed"), inbox, model))
        val chat = NotificationInput("c", "com.whatsapp", "WhatsApp", title = "Sam", text = "Are you coming?", isMessagingStyle = true)
        assertEquals(DecisionEngine.Learned.SKIPPED, engine.learn(chat, inbox, model))
        store.cache[promo(2).templateKey] = Verdict(Action.KEEP, Category.PROMOTION, 1.0, Source.CORRECTION, "You chose keep")
        assertEquals(DecisionEngine.Learned.SKIPPED, engine.learn(promo(2), inbox, model))
        assertEquals("nothing was sent to the model", 0, model.calls)
    }

    @Test fun `a pass asks each kind once and at most the limit`() = runTest {
        val engine = DecisionEngine(TidyStore(), { null })
        val tidy = OnPhoneTidy(engine, maxPerPass = 3)
        val model = PhoneModel()
        val recent = (1..5).flatMap { listOf(promo(it), promo(it).copy(key = "dup$it")) }
        assertEquals(3, tidy.run(recent, inbox, model))
        assertEquals("duplicates of one kind are asked once", 3, model.calls)
        assertEquals(2, tidy.run(recent, inbox, model))
        assertEquals(0, tidy.run(recent, inbox, model))
        assertEquals(5, model.calls)
    }

    @Test fun `a failure stops the pass and the rest are tried next time`() = runTest {
        val engine = DecisionEngine(TidyStore(), { null })
        val tidy = OnPhoneTidy(engine)
        val model = PhoneModel(failFrom = 2)
        val recent = (1..4).map { promo(it) }
        assertEquals(1, tidy.run(recent, inbox, model))
        assertEquals("stopped at the first failure", 2, model.calls)
        model.failFrom = Int.MAX_VALUE
        assertEquals("the one that failed isn't retried, so it can't block the others", 2, tidy.run(recent, inbox, model))
    }

    @Test fun `the phone's own guess needs a higher bar and says where it came from`() = runTest {
        val store = TidyStore()
        val engine = DecisionEngine(store, { null })
        assertEquals(DecisionEngine.Learned.NOT_SURE, engine.learn(promo(1), inbox, PhoneModel(p = 0.92)))
        assertEquals(DecisionEngine.Learned.YES, engine.learn(promo(2), inbox, PhoneModel(p = 0.97)))
        assertTrue(store.cache.getValue(promo(2).templateKey).reason.startsWith("On-phone AI"))
    }

    @Test fun `chats, codes and money are never asked about, and a cloud model's answers stand`() {
        assertEquals(false, OnPhoneTidy.worthAsking(Category.PERSONAL))
        assertEquals(false, OnPhoneTidy.worthAsking(Category.SECURITY))
        assertEquals(false, OnPhoneTidy.worthAsking(Category.FINANCE))
        assertEquals(true, OnPhoneTidy.worthAsking(Category.PROMOTION))
        assertEquals(listOf(Source.HEURISTIC), OnPhoneTidy.guessed(hasCloudModel = true))
        assertTrue(Source.INBOX in OnPhoneTidy.guessed(hasCloudModel = false))
    }
}
