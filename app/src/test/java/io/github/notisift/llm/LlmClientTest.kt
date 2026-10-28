package io.github.notisift.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LlmClientTest {
    private lateinit var server: MockWebServer
    private val request = JsonRequest("sys", "user text", "decision", buildJsonObject { put("type", "object") }, 100)

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun base(path: String = "") = server.url(path).toString().trimEnd('/')
    private fun client(kind: ProviderKind, baseUrl: String = base(), model: String = "m1", key: String = "k") =
        LlmClient(ModelConfig(kind, baseUrl, key, model))

    @Test fun `perplexity uses agent endpoint without web search and reads output_text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"output_text":"{\"action\":\"keep\"}"}"""))
        val out = client(ProviderKind.PERPLEXITY_AGENT).completeJson(request)
        assertEquals("keep", out["action"]?.jsonPrimitive?.content)
        val sent = server.takeRequest()
        assertEquals("/v1/agent", sent.path)
        assertEquals("Bearer k", sent.getHeader("Authorization"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("m1", body["model"]?.jsonPrimitive?.content)
        assertEquals("user text", body["input"]?.jsonPrimitive?.content)
        assertFalse("must not enable web search", "tools" in body)
        assertEquals("json_schema", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun `perplexity base url with v1 suffix is not doubled`() = runTest {
        server.enqueue(MockResponse().setBody("""{"output":[{"type":"reasoning","content":[{"type":"text","text":"{\"a\":0}"}]},{"type":"message","content":[{"type":"output_text","text":"{\"a\":1}"}]}]}"""))
        val out = client(ProviderKind.PERPLEXITY_AGENT, base("/v1")).completeJson(request)
        assertEquals("1", out["a"]?.jsonPrimitive?.content)
        assertEquals("/v1/agent", server.takeRequest().path)
    }

    @Test fun `schema rejection retries once without response_format`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"response_format not supported"}}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Sure! ```json\n{\"action\":\"block\"}\n```"}}]}"""))
        val out = client(ProviderKind.OLLAMA).completeJson(request)
        assertEquals("block", out["action"]?.jsonPrimitive?.content)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("response_format" in first)
        assertFalse("response_format" in second)
    }

    @Test fun `server errors are surfaced with provider message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            client(ProviderKind.OPENAI).completeJson(request)
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(401, e.httpCode)
            assertTrue(e.message!!.contains("bad key"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `anthropic uses x-api-key and messages endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"{\"action\":\"bundle\"}"}]}"""))
        val out = client(ProviderKind.ANTHROPIC).completeJson(request)
        assertEquals("bundle", out["action"]?.jsonPrimitive?.content)
        val sent = server.takeRequest()
        assertEquals("/v1/messages", sent.path)
        assertEquals("k", sent.getHeader("x-api-key"))
        assertNull(sent.getHeader("Authorization"))
    }

    @Test fun `gemini calls generateContent for the model`() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"action\":\"silence\"}"}]}}]}"""))
        val out = client(ProviderKind.GEMINI, model = "gemini-x").completeJson(request)
        assertEquals("silence", out["action"]?.jsonPrimitive?.content)
        val sent = server.takeRequest()
        assertEquals("/v1beta/models/gemini-x:generateContent", sent.path)
        assertEquals("k", sent.getHeader("x-goog-api-key"))
    }

    @Test fun `blank model is auto-picked from the provider list`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"big-model"},{"id":"text-embedding-3"},{"id":"fast-mini"}]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"ok\":true}"}}]}"""))
        client(ProviderKind.OPENROUTER, model = "").completeJson(request)
        assertEquals("/models", server.takeRequest().path)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("fast-mini", body["model"]?.jsonPrimitive?.content)
    }

    private val context = mapOf("title" to "sale", "count" to 2)
    private val actionReq = DecisionRequest(context, listOf("keep", "block"), mapOf(DecisionParams.INSTRUCTIONS to "What to do?", DecisionParams.OPTION_DESCRIPTIONS to mapOf("keep" to "Keep it")))
    private val categoryReq = DecisionRequest(context, listOf("promotion", "other"))

    @Test fun `perplexity decisions batches same-context requests and reads probabilities`() = runTest {
        server.enqueue(MockResponse().setBody("""{"model":"pplx-decider-latest","answers":{"q0":{"type":"choice","choice":"block","confidence":0.8,"probabilities":{"keep":0.1,"block":0.9}},
            "q1":{"type":"choice","choice":"nonsense","confidence":0.9,"probabilities":{}}},"usage":{"input_tokens":10,"output_tokens":2}}"""))
        val c = client(ProviderKind.PERPLEXITY, model = "")
        val (action, category) = c.decideAll(listOf(actionReq, categoryReq))
        assertEquals("block", action.decision)
        assertEquals(0.9, action.confidence!!, 1e-9)
        assertEquals("perplexity:pplx-decider-latest", action.metadata["model"])
        assertNull("choices outside the options become no decision", category.decision)
        assertEquals(1, server.requestCount)
        val sent = server.takeRequest()
        assertEquals("/v1/decisions", sent.path)
        assertEquals("Bearer k", sent.getHeader("Authorization"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "state", "questions"), body.keys)
        assertEquals("sale", body["state"]!!.jsonObject["title"]?.jsonPrimitive?.content)
        val q = body["questions"]!!.jsonObject["q0"]!!.jsonObject
        assertEquals("choice", q["type"]?.jsonPrimitive?.content)
        assertEquals("Keep it", q["criteria"]!!.jsonObject["keep"]?.jsonPrimitive?.content)
        assertEquals(kotlinx.serialization.json.JsonNull, q["criteria"]!!.jsonObject["block"])
        assertFalse("blank instructions are omitted", "instructions" in body["questions"]!!.jsonObject["q1"]!!.jsonObject)
    }

    @Test fun `different contexts are separate calls and yes-no is the default`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"answers":{"q0":{"choice":"yes","confidence":0.6,"probabilities":{"yes":0.8,"no":0.2}},"q1":{"choice":"no","confidence":0.6,"probabilities":{"yes":0.2,"no":0.8}}}}""")) }
        val out = client(ProviderKind.PERPLEXITY).decideAll(listOf(DecisionRequest(mapOf("a" to 1)), DecisionRequest(mapOf("a" to 2))))
        assertEquals(listOf("yes", "no"), out.map { it.decision })
        assertEquals(2, server.requestCount)
    }

    @Test fun `jev uses systemone path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"answers":{"q0":{"type":"choice","choice":"keep","confidence":0.5,"probabilities":{"keep":0.75,"block":0.25}}}}"""))
        val out = client(ProviderKind.JEV, base("/v1")).decide(actionReq)
        assertEquals(0.75, out.confidence!!, 1e-9)
        assertEquals("/v1/systemone", server.takeRequest().path)
    }

    @Test fun `chat models answer the same requests through json`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"q0\":{\"choice\":\"keep\",\"confidence\":0.7},\"q1\":{\"choice\":\"bogus\",\"confidence\":1}}"}}]}"""))
        val (action, category) = client(ProviderKind.OPENAI).decideAll(listOf(actionReq, categoryReq))
        assertEquals("keep", action.decision)
        assertEquals(0.7, action.confidence!!, 1e-9)
        assertNull(category.decision)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("Question \"q0\": What to do?"))
        assertTrue(system.contains("untrusted"))
    }

    @Test fun `plain http is refused for public hosts`() {
        try {
            LlmClient(ModelConfig(ProviderKind.CUSTOM, "http://example.com/v1", "", "m"))
            fail("expected refusal")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("HTTPS"))
        }
        LlmClient(ModelConfig(ProviderKind.OLLAMA, "http://192.168.0.20:11434/v1", "", "m"))
    }

    @Test fun `non JSON text from model is an error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"I cannot help"}}]}"""))
        try {
            client(ProviderKind.OPENAI).completeJson(request)
            fail("expected error")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }
}

class JsonExtractTest {
    @Test fun `finds object inside prose and fences`() {
        val o = JsonExtract.firstObject("Here you go:\n```json\n{\"a\": \"x}y\", \"b\": {\"c\": 1}}\n``` thanks")
        assertEquals("x}y", o?.get("a")?.jsonPrimitive?.content)
    }

    @Test fun `skips broken candidates`() {
        val o = JsonExtract.firstObject("{not json} then {\"ok\": true}")
        assertEquals("true", o?.get("ok")?.jsonPrimitive?.content)
    }

    @Test fun `returns null when nothing parses`() {
        assertNull(JsonExtract.firstObject("no braces"))
    }
}

class ProviderDetectorTest {
    @Test fun `key prefixes map to providers`() {
        assertEquals(ProviderKind.PERPLEXITY, ProviderDetector.detect(apiKey = "pplx-abc"))
        assertEquals(ProviderKind.ANTHROPIC, ProviderDetector.detect(apiKey = "sk-ant-abc"))
        assertEquals(ProviderKind.OPENROUTER, ProviderDetector.detect(apiKey = "sk-or-v1-abc"))
        assertEquals(ProviderKind.OPENAI, ProviderDetector.detect(apiKey = "sk-proj-abc"))
        assertEquals(ProviderKind.GEMINI, ProviderDetector.detect(apiKey = "AIzaSy"))
        assertEquals(ProviderKind.GROQ, ProviderDetector.detect(apiKey = "gsk_abc"))
    }

    @Test fun `explicit choice beats url which beats key`() {
        assertEquals(ProviderKind.GROQ, ProviderDetector.detect("groq", "pplx-x", "https://api.openai.com/v1"))
        assertEquals(ProviderKind.OPENAI, ProviderDetector.detect(null, "pplx-x", "https://api.openai.com/v1"))
        assertEquals(ProviderKind.OLLAMA, ProviderDetector.detect(baseUrl = "http://10.0.0.5:11434/v1"))
        assertEquals(ProviderKind.CUSTOM, ProviderDetector.detect(baseUrl = "https://llm.mycorp.dev/v1"))
    }

    @Test fun `env var name is a hint when key has no known prefix`() {
        assertEquals(ProviderKind.PERPLEXITY, ProviderDetector.detect(apiKey = "opaque", keyEnvName = "PERPLEXITY_API_KEY"))
        assertEquals(ProviderKind.JEV, ProviderDetector.detect(baseUrl = "https://api.typesafe.ai"))
    }

    @Test fun `resolve fills defaults from a key alone`() {
        val c = ProviderDetector.resolve(null, "pplx-123", "", "")
        assertEquals(ProviderKind.PERPLEXITY, c.kind)
        assertEquals("https://api.perplexity.ai", c.baseUrl)
        assertEquals("pplx-decider-latest", c.model)
        assertTrue(c.isUsable)
    }

    @Test fun `picks cheap chat model and ignores embeddings`() {
        assertEquals("gpt-x-mini", ProviderDetector.pickCheapModel(listOf("text-embedding-mini", "gpt-x", "gpt-x-mini")))
        assertEquals("only", ProviderDetector.pickCheapModel(listOf("only")))
        assertNull(ProviderDetector.pickCheapModel(emptyList()))
    }
}

class PrivateHostTest {
    @Test fun `only literal private IPs and localhost count as private`() {
        assertTrue(LlmClient.isPrivateHost("192.168.0.20"))
        assertTrue(LlmClient.isPrivateHost("localhost"))
        assertFalse(LlmClient.isPrivateHost("10.0.0.1.attacker.com"))
        assertFalse(LlmClient.isPrivateHost("printer.local"))
        assertFalse(LlmClient.isPrivateHost("8.8.8.8"))
        assertFalse(LlmClient.isPrivateHost("192.168.0.999"))
    }

    @Test fun `config toString never shows the key`() {
        assertFalse(ModelConfig(ProviderKind.OPENAI, "https://x", "sk-secret", "m").toString().contains("secret"))
    }
}
