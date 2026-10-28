package io.github.notisift.engine

import io.github.notisift.llm.DecisionModel
import io.github.notisift.llm.DecisionRequest
import io.github.notisift.llm.DecisionResult
import io.github.notisift.service.ImportantStack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun note(title: String, text: String = "", pkg: String = "com.example.app", label: String = "Example") =
    NotificationInput("$pkg|$title", pkg, label, title = title, text = text)

private class StubStore(private val mode: AppMode? = null, private val type: String = "") : EngineStore {
    override suspend fun appMode(packageName: String): AppMode? = mode
    override suspend fun appInstructions(packageName: String) = ""
    override suspend fun appCategory(packageName: String) = type
    override suspend fun cached(templateKey: String): Verdict? = null
    override suspend fun remember(templateKey: String, verdict: Verdict) = Unit
}

private class StubModel(private val action: String, private val category: String) : DecisionModel {
    override val id = "stub"
    override suspend fun decide(request: DecisionRequest) = decideAll(listOf(request)).single()
    override suspend fun decideAll(requests: List<DecisionRequest>) = requests.mapIndexed { i, _ ->
        if (i == 0) DecisionResult(action, 0.95) else DecisionResult(category, 0.9)
    }
}

class CategoryTest {
    @Test fun `content picks the specific group`() {
        assertEquals(Category.MARKETS, Heuristics.category(note("Price alert", "AAPL crossed 52-week high")))
        assertEquals(Category.HOME, Heuristics.category(note("Front door", "Motion detected")))
        assertEquals(Category.TRAVEL, Heuristics.category(note("Your flight", "Boarding starts at gate B12")))
        assertEquals(Category.HEALTH, Heuristics.category(note("Nice!", "You hit 10,000 steps")))
        assertEquals(Category.MEDIA, Heuristics.category(note("New episode", "Season 2 is here")))
        assertEquals(Category.NEWS, Heuristics.category(note("Tonight", "Rain expected, 54°")))
        assertEquals(Category.SCHOOL, Heuristics.category(note("Missing assignment", "Math worksheet")))
        assertEquals(Category.ORDERS, Heuristics.category(note("Out for delivery", "Your package arrives today")))
    }

    @Test fun `the app decides when the text says nothing`() {
        assertEquals(Category.HOME, Heuristics.category(note("Front camera", pkg = "com.hualai", label = "Wyze")))
        assertEquals(Category.MARKETS, Heuristics.category(note("REKR", pkg = "com.devexperts.tos", label = "thinkorswim")))
        assertEquals(Category.GAMES, Heuristics.category(note("Come back!"), appCategory = "game"))
        assertEquals(Category.OTHER, Heuristics.category(note("Hello there")))
    }

    @Test fun `price alerts are not money movement`() {
        assertFalse(Heuristics.isMoneyMovement(note("Price alert", "TSLA is up 4%")))
        assertTrue(Heuristics.isMoneyMovement(note("Payment received", "\$40 from Sam")))
    }

    @Test fun `optional groups appear only when a matching app is installed`() {
        val none = Category.relevant(listOf("com.android.chrome"))
        assertFalse(Category.HOME in none)
        assertTrue(Category.PROMOTION in none)
        assertTrue(Category.HOME in Category.relevant(listOf("com.wyze.app")))
        assertTrue(Category.SCHOOL in Category.relevant(emptyList(), seen = setOf(Category.SCHOOL)))
    }

    @Test fun `choices round trip and ignore junk input`() {
        val choices = CategoryChoices.parse("news=junk; home=important;bogus=later;media=nope;")
        assertEquals(mapOf(Category.NEWS to GroupChoice.JUNK, Category.HOME to GroupChoice.IMPORTANT), choices)
        assertEquals("news=junk;home=important", CategoryChoices.format(choices))
    }

    @Test fun `protected groups can only be pinned, never hidden`() {
        assertTrue(CategoryChoices.allowed(Category.FINANCE, GroupChoice.IMPORTANT))
        assertFalse(CategoryChoices.allowed(Category.FINANCE, GroupChoice.JUNK))
        assertFalse(CategoryChoices.allowed(Category.PERSONAL, GroupChoice.LATER))
        assertTrue(CategoryChoices.allowed(Category.NEWS, GroupChoice.JUNK))
    }

    @Test fun `a group choice overrides the model`() = runTest {
        val engine = DecisionEngine(StubStore(), { StubModel("keep", "news") })
        val settings = EngineSettings(inboxMode = false, categoryChoices = mapOf(Category.NEWS to GroupChoice.JUNK))
        val v = engine.decide(note("Headline", "Something happened"), settings)
        assertEquals(Action.BLOCK, v?.action)
        assertEquals(Source.GROUP, v?.source)
    }

    @Test fun `a protected group ignores a hiding choice`() = runTest {
        val engine = DecisionEngine(StubStore(), { StubModel("keep", "finance") })
        val settings = EngineSettings(inboxMode = false, categoryChoices = mapOf(Category.FINANCE to GroupChoice.JUNK))
        assertEquals(Action.KEEP, engine.decide(note("Payment", "\$20 sent to Ana"), settings)?.action)
    }

    @Test fun `newer rows sort first`() {
        assertTrue(ImportantStack.newestFirst(2_000L) < ImportantStack.newestFirst(1_000L))
        assertEquals(19, ImportantStack.newestFirst(System.currentTimeMillis()).length)
    }

    @Test fun `row tags map back to notification keys`() {
        val key = "0|com.app|1|null|10001"
        assertEquals(key, ImportantStack.keyOf(ImportantStack.tag(key)))
        assertNull(ImportantStack.keyOf("m:com.app"))
        assertNull(ImportantStack.keyOf("digest"))
        assertNull(ImportantStack.keyOf(null))
    }

    @Test fun `a news app's headlines stay news whatever they mention`() = runTest {
        val flight = note("inshorts", "Flight attendant spots passenger texting about hijack", "com.nis.app", "inshorts")
        val treat = note("inshorts", "Beggar runs away after being offered a treat", "com.nis.app", "inshorts")
        assertEquals(Category.NEWS, Heuristics.category(flight, "news"))
        assertEquals(Category.NEWS, Heuristics.category(treat, "news"))
        // "Always later" app rule labels by the app's type, not by the headline's words.
        val ruled = DecisionEngine(StubStore(AppMode.BUNDLE, "news"), { null }).decide(flight, EngineSettings())
        assertEquals(Category.NEWS, ruled?.category)
        // The AI reading "flight" as travel is overruled for a news app.
        val ai = DecisionEngine(StubStore(type = "news"), { StubModel("bundle", "travel") }).decide(flight, EngineSettings())
        assertEquals(Category.NEWS, ai?.category)
    }

    @Test fun `apps that aren't content apps are still grouped by what the notification says`() = runTest {
        val trip = note("Your flight", "Boarding starts at gate B12", "com.example.mail", "Mail")
        assertEquals(Category.TRAVEL, Heuristics.category(trip, "productivity"))
        val ai = DecisionEngine(StubStore(type = "productivity"), { StubModel("keep", "travel") }).decide(trip, EngineSettings())
        assertEquals(Category.TRAVEL, ai?.category)
    }

    @Test fun `a scan group works as the app type when Play has none`() {
        assertEquals(Category.NEWS, Heuristics.contentApp("news"))
        assertEquals(Category.GAMES, Heuristics.contentApp("games"))
        assertNull(Heuristics.contentApp("finance"))
        assertEquals(Category.NEWS, io.github.notisift.scan.Offline.match("com.nis.app", "inshorts")?.second)
        assertEquals(Category.MEDIA, io.github.notisift.scan.Offline.match("com.google.android.youtube", "YouTube")?.second)
    }

    @Test fun `waiting items are re-filed with what is known about the app now`() {
        val headline = note("inshorts", "What will happen to India-Israel ties?", "com.nis.app", "inshorts")
        assertEquals(Category.NEWS, Heuristics.regroup(Category.OTHER, headline, "news"))
        assertEquals(Category.NEWS, Heuristics.regroup(Category.TRAVEL, headline, "news"))
        val alert = note("AAPL", "crossed 52-week high", "org.dayup.stocks", "Webull")
        assertEquals("unknown groups get another look", Category.MARKETS, Heuristics.regroup(Category.OTHER, alert, "productivity"))
        assertEquals("known groups of other apps stay", Category.ORDERS, Heuristics.regroup(Category.ORDERS, alert, "productivity"))
        assertEquals(Category.PERSONAL, Heuristics.regroup(Category.PERSONAL, headline, "news"))
    }

    @Test fun `built-in app list knows travel, food delivery and health apps`() {
        val o = io.github.notisift.scan.Offline
        assertEquals(Category.TRAVEL, o.match("com.google.android.apps.maps", "Maps")?.second)
        assertEquals(Category.ORDERS, o.match("com.ubercab.eats", "Uber Eats")?.second)
        assertEquals(Category.TRAVEL, o.match("com.ubercab", "Uber")?.second)
        assertEquals(Category.HEALTH, o.match("com.fitbit.FitbitMobile", "Fitbit")?.second)
        assertEquals(Category.NEWS, o.match("com.google.android.googlequicksearchbox", "Google")?.second)
        assertEquals(Category.CALLS_ALERTS, o.match("com.google.android.calendar", "Calendar")?.second)
    }
}
