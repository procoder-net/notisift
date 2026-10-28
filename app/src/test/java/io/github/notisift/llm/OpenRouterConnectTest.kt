package io.github.notisift.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterConnectTest {
    @Test fun `S256 challenge is base64url SHA-256 without padding`() {
        // Expected value computed independently: base64.urlsafe_b64encode(sha256(verifier)).rstrip("=")
        assertEquals("oqxSbbie_qaqaFbkLMA40uPvvNTiKGi_6YHzRxVTK5k", OpenRouterConnect.challenge("dBjftJeZ4CVP-mJ92K9-EZKGt4OfPBTKMUtsv9bL6p8"))
    }

    @Test fun `auth url carries an encoded localhost callback, challenge and state`() {
        val url = OpenRouterConnect.authUrl("http://localhost:51423/callback", "abc-_", "st8")
        assertTrue(url.startsWith("https://openrouter.ai/auth?"))
        assertTrue(url.contains("callback_url=http%3A%2F%2Flocalhost%3A51423%2Fcallback"))
        assertTrue(url.contains("code_challenge=abc-_&code_challenge_method=S256"))
        assertTrue(url.contains("state=st8"))
    }

    @Test fun `callback gives the code only when the state matches`() {
        assertEquals("c0de", OpenRouterConnect.parseCallback("GET /callback?code=c0de&state=st8 HTTP/1.1", "st8")?.getOrNull())
        assertTrue(OpenRouterConnect.parseCallback("GET /callback?code=c0de&state=evil HTTP/1.1", "st8")!!.isFailure)
        assertTrue(OpenRouterConnect.parseCallback("GET /callback?error=access_denied HTTP/1.1", "st8")!!.isFailure)
        assertNull(OpenRouterConnect.parseCallback("GET /favicon.ico HTTP/1.1", "st8"))
        assertNull(OpenRouterConnect.parseCallback("", "st8"))
    }

    @Test fun `key is read from the exchange response`() {
        assertEquals("sk-or-v1-abc", OpenRouterConnect.parseKey("""{"key":"sk-or-v1-abc","user_id":"u"}"""))
        assertNull(OpenRouterConnect.parseKey("""{"error":"bad"}"""))
        assertNull(OpenRouterConnect.parseKey("not json"))
    }
}
