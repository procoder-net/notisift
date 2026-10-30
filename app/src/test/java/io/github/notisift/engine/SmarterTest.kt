package io.github.notisift.engine

import io.github.notisift.data.Entry
import io.github.notisift.llm.GroupSummaries
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private class ChatStore(var today: Int = 0) : EngineStore {
    override suspend fun appMode(packageName: String): AppMode? = null
    override suspend fun appInstructions(packageName: String) = ""
    override suspend fun appCategory(packageName: String) = ""
    override suspend fun cached(templateKey: String): Verdict? = null
    override suspend fun remember(templateKey: String, verdict: Verdict) = Unit
    override suspend fun recentChatMessages(packageName: String, chat: String) = today
}

class SmartGroupsTest {
    private val group = NotificationInput("g", "com.whatsapp", "WhatsApp", title = "Suit Updates (40 messages): Shop", text = "Lovely day", isMessagingStyle = true)
    private val friend = NotificationInput("f", "com.whatsapp", "WhatsApp", title = "Sam", text = "🔴🔴🔴 50% off sale", isMessagingStyle = true)

    @Test fun `sale posts and forwards in groups wait in later`() = runTest {
        val engine = DecisionEngine(ChatStore(), { null })
        for (text in listOf("Forwarded many times", "NEW STOCK 🔥🔥🔥", "🔴🔴🔴 Today only", "Kurtas ₹499 https://shop.example/x", "Flat 40% off today")) {
            val v = engine.decide(group.copy(text = text), EngineSettings())
            assertEquals(text, Action.BUNDLE, v?.action)
            assertEquals(Source.SMART_GROUP, v?.source)
        }
        assertEquals(Action.KEEP, engine.decide(group, EngineSettings())?.action)
    }

    @Test fun `one to one chats are never quieted`() = runTest {
        val engine = DecisionEngine(ChatStore(today = 500), { null })
        assertEquals(Action.KEEP, engine.decide(friend, EngineSettings())?.action)
    }

    @Test fun `busy groups keep only mentions on top`() = runTest {
        val store = ChatStore(today = GroupSmarts.BUSY_PER_DAY - 2)
        val engine = DecisionEngine(store, { null })
        assertEquals(Action.KEEP, engine.decide(group, EngineSettings())?.action)
        store.today = GroupSmarts.BUSY_PER_DAY
        assertEquals(Action.BUNDLE, engine.decide(group.copy(text = "another"), EngineSettings())?.action)
        assertEquals(Action.KEEP, engine.decide(group.copy(text = "@Mo can you check?"), EngineSettings(myNames = listOf("Mo")))?.action)
        assertEquals("a promo that mentions you still comes through", Action.KEEP, engine.decide(group.copy(text = "@Mo 50% off sale"), EngineSettings(myNames = listOf("Mo")))?.action)
    }

    @Test fun `any mention is kept while the user's names are unknown`() = runTest {
        val engine = DecisionEngine(ChatStore(today = 100), { null })
        assertEquals(Action.KEEP, engine.decide(group.copy(text = "@Mohammed are you coming?"), EngineSettings())?.action)
        assertEquals(Action.KEEP, engine.decide(group.copy(text = "@Mohammed 50% off sale"), EngineSettings())?.action)
        assertEquals("known names: someone else's mention can wait", Action.BUNDLE, engine.decide(group.copy(text = "@Ali are you coming?"), EngineSettings(myNames = listOf("Mo")))?.action)
    }

    @Test fun `priority conversations never pull in apps left alone`() = runTest {
        val store = object : EngineStore by ChatStore() { override suspend fun appMode(packageName: String) = AppMode.UNMANAGED }
        assertNull(DecisionEngine(store, { null }).decide(group.copy(isPriorityConversation = true), EngineSettings()))
    }

    @Test fun `turning smart groups off and explicit choices win`() = runTest {
        val engine = DecisionEngine(ChatStore(today = 100), { null })
        assertEquals(Action.KEEP, engine.decide(group.copy(text = "50% off"), EngineSettings(smartGroups = false))?.action)
        val always = EngineSettings(chatRules = mapOf(ChatRules.key(group.packageName, group.title) to ChatMode.ALWAYS))
        assertEquals(Source.CHAT, engine.decide(group.copy(text = "50% off"), always)?.source)
    }

    @Test fun `priority conversations always stay on top`() = runTest {
        val engine = DecisionEngine(ChatStore(today = 100), { null })
        assertEquals(Action.KEEP, engine.decide(group.copy(text = "50% off", isPriorityConversation = true), EngineSettings())?.action)
    }

    @Test fun `ordinary group talk is not a promo`() {
        listOf("See you at 7", "I got the sale done at work", "Who's bringing snacks?", "lol 😂😂😂", "🔥🔥🔥 goal!").forEach { assertFalse(it, GroupSmarts.isPromo(it)) }
    }
}

class IgnoredGroupsTest {
    private fun msg(chat: String, opened: Boolean = false) = ChatActivity("com.whatsapp", "WhatsApp", "$chat (3 messages): Ann", "hi", opened)

    @Test fun `groups with many unopened messages are offered`() {
        val history = List(GroupSmarts.IGNORED_PER_WEEK) { msg("Deals") } + List(30) { msg("Family", opened = it == 0) } + List(3) { msg("Book club") }
        val out = GroupSmarts.ignoredGroups(history, emptyMap(), emptySet())
        assertEquals(listOf("Deals"), out.map { it.chat })
    }

    @Test fun `chosen and skipped chats are not asked again`() {
        val history = List(20) { msg("Deals") }
        val key = ChatRules.key("com.whatsapp", "Deals")
        assertTrue(GroupSmarts.ignoredGroups(history, mapOf(key to ChatMode.ALWAYS), emptySet()).isEmpty())
        assertTrue(GroupSmarts.ignoredGroups(history, emptyMap(), GroupSmarts.parseSkipped(GroupSmarts.formatSkipped(listOf(key)))).isEmpty())
    }
}

class LaterScheduleTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(h: Int, m: Int = 0, day: Int = 29) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `parses hours and ignores junk`() {
        assertEquals(listOf(9, 18), LaterSchedule.parse("18, 9,x,25,9"))
        assertTrue(LaterSchedule.parse("").isEmpty())
    }

    @Test fun `last and next slot`() {
        val hours = listOf(9, 18)
        assertEquals(at(9), LaterSchedule.lastSlot(at(12), hours, zone))
        assertEquals(at(18, day = 28), LaterSchedule.lastSlot(at(7), hours, zone))
        assertEquals(at(18), LaterSchedule.nextSlot(at(12), hours, zone))
        assertEquals(at(9, day = 30), LaterSchedule.nextSlot(at(19), hours, zone))
        assertNull(LaterSchedule.lastSlot(at(12), emptyList(), zone))
    }
}

class GroupSummariesTest {
    private fun entry(id: Long) = Entry(id = id, key = "k$id", packageName = "p", appLabel = "Shop", channelId = "", title = "Sale $id", text = "Big deal",
        postedAt = 0, category = "promotion", action = "bundle", source = "MODEL", confidence = 1.0, reason = "", templateKey = "t", applied = true)

    @Test fun `summarises once per change and parses one line`() = runTest {
        var now = 0L
        var calls = 0
        val s = GroupSummaries({ calls++; buildJsonObject { put("summary", JsonPrimitive("  3 sale posts,\n nothing for you ")) } }, { now })
        val three = listOf(entry(1), entry(2), entry(3))
        assertFalse("too few to summarise", s.isStale("g", three.take(2)))
        assertTrue(s.summarize("g", "Promotions", three))
        assertEquals("3 sale posts, nothing for you", s.cached("g", three))
        assertNull("a line never shows over items it didn't read", s.cached("g", three + entry(4)))
        assertFalse("unchanged group isn't asked again", s.summarize("g", "Promotions", three))
        assertFalse("changed but too soon", s.summarize("g", "Promotions", three + entry(4)))
        now += GroupSummaries.MIN_GAP_MS
        assertTrue(s.summarize("g", "Promotions", three + entry(4)))
        assertEquals(2, calls)
    }

    @Test fun `a failing model leaves no line`() = runTest {
        val s = GroupSummaries({ error("offline") })
        assertFalse(s.summarize("g", "News", listOf(entry(1), entry(2), entry(3))))
        assertNull(s.cached("g", listOf(entry(1), entry(2), entry(3))))
        assertNull(GroupSummaries.parse(buildJsonObject { put("summary", JsonPrimitive(" ")) }))
    }
}

class ChatThreadTest {
    private fun e(id: Long, title: String, key: String = "k$id", category: String = "personal") = Entry(id = id, key = key, packageName = "com.sms", appLabel = "Messages",
        channelId = "", title = title, text = "x", postedAt = id, category = category, action = "keep", source = "PROTECTED", confidence = 1.0, reason = "", templateKey = "t", applied = true)
    private fun thread(entry: Entry) = io.github.notisift.service.ImportantStack.threadOf(entry)

    @Test fun `messages from one named chat share a row`() {
        assertEquals(thread(e(1, "Family (3 messages): Ann")), thread(e(2, "Family: Bob")))
        assertTrue(thread(e(1, "Ann")) != thread(e(2, "Bob")))
    }

    @Test fun `generic titles and non-chats never merge`() {
        for (t in listOf("New message", "2 new messages", "Messages", "", "+1 (555) 010-2000")) {
            assertTrue(t, thread(e(1, t)) != thread(e(2, t)))
        }
        assertTrue(thread(e(1, "Ann", category = "orders")) != thread(e(2, "Ann", category = "orders")))
    }
}
