package io.github.notisift.llm

import io.github.notisift.data.Entry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnPhoneModelTest {
    private val action = DecisionRequest(mapOf("title" to "Flash sale"), listOf("keep", "bundle"), mapOf(DecisionParams.INSTRUCTIONS to "What to do?"))
    private val group = DecisionRequest(mapOf("title" to "Flash sale"), listOf("shopping", "news"))

    @Test fun `answers every question about one notification in one prompt`() = runTest {
        val prompts = mutableListOf<String>()
        val model = OnPhoneModel { system, user, _ ->
            prompts += system + user
            // Small models like to chat around the JSON; it is still found.
            "Sure! ```json\n{\"q0\":{\"choice\":\"bundle\",\"confidence\":0.92},\"q1\":{\"choice\":\"shopping\",\"confidence\":0.8}}\n```"
        }
        val results = model.decideAll(listOf(action, group))
        assertEquals(1, prompts.size)
        assertTrue("the schema is spelled out", "JSON Schema" in prompts[0] && "\"enum\"" in prompts[0])
        assertTrue("the notification is fenced off as data", "<state>" in prompts[0])
        assertEquals(listOf("bundle", "shopping"), results.map { it.decision })
        assertEquals(0.92, results[0].confidence ?: 0.0, 1e-9)
        assertEquals(false, results[0].metadata["calibrated"])
    }

    @Test fun `answers outside the options are dropped`() = runTest {
        val model = OnPhoneModel { _, _, _ -> "{\"q0\":{\"choice\":\"delete everything\",\"confidence\":1}}" }
        assertEquals(null, model.decide(action).decision)
    }

    @Test fun `no JSON is an error so the caller can stop`() = runTest {
        val model = OnPhoneModel { _, _, _ -> "I can't help with that." }
        try {
            model.decide(action)
            fail("expected an error")
        } catch (e: LlmException) {
            assertTrue(e.message.orEmpty().contains("JSON"))
        }
    }

    @Test fun `writes group summaries`() = runTest {
        val model = OnPhoneModel { _, _, _ -> "{\"summary\": \"3 sale posts, nothing for you\"}" }
        val entries = (1..3).map { Entry(id = it.toLong(), key = "k$it", packageName = "p", appLabel = "Shop", channelId = "", title = "Sale $it", text = "", postedAt = 0, category = "shopping", action = "bundle", source = "INBOX", confidence = 1.0, reason = "", templateKey = "t$it", applied = true) }
        val summaries = GroupSummaries(model::completeJson)
        assertTrue(summaries.summarize("shopping", "Shopping", entries))
        assertEquals("3 sale posts, nothing for you", summaries.cached("shopping", entries))
    }
}
