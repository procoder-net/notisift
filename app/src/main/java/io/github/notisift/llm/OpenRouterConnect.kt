package io.github.notisift.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "Connect OpenRouter": sign in on openrouter.ai and come back with your own key, no copying. OpenRouter's PKCE flow
 * accepts a localhost callback on any port, so NotiSift listens on 127.0.0.1 (this phone only) for that one redirect,
 * checks its state, and trades the code plus the PKCE verifier for the key.
 */
class OpenRouterConnect(private val http: OkHttpClient = LlmClient.defaultHttp, private val random: SecureRandom = SecureRandom()) {

    /** Runs the whole flow: [open] shows the sign-in page; returns the key, or throws with a message for the user. */
    suspend fun connect(appId: String, open: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val verifier = token()
        val state = token()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = WAIT_MS
            open(authUrl("http://localhost:${server.localPort}/callback", challenge(verifier), state))
            val code = awaitCode(server, state, appId)
            exchange(code, verifier)
        }
    }

    /** Waits for the browser's redirect, ignoring stray requests (favicon) until the real callback arrives. */
    private fun awaitCode(server: ServerSocket, state: String, appId: String): String {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                throw IllegalStateException("Sign-in timed out. Tap Connect OpenRouter to try again.")
            }
            socket.use { s ->
                s.soTimeout = 5_000
                val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                val result = parseCallback(line, state)
                val page = if (result == null) null else donePage(result.isSuccess, appId)
                s.getOutputStream().write(httpResponse(page).toByteArray())
                s.getOutputStream().flush()
                result?.let { return it.getOrThrow() }
            }
        }
    }

    private fun exchange(code: String, verifier: String): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(KEYS_URL).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                when (response.code) {
                    403 -> "OpenRouter didn't accept the sign-in (it may have expired). Try again."
                    else -> "OpenRouter answered ${response.code}. Try again."
                }
            }
            return parseKey(text) ?: error("OpenRouter didn't return a key. Try again.")
        }
    }

    private fun token(): String = ByteArray(32).also(random::nextBytes).let(::base64Url)

    companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        /** OpenRouter codes expire after 10 minutes, so there's no point listening longer. */
        private const val WAIT_MS = 10 * 60 * 1000

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** S256: base64url(SHA-256(verifier)), no padding. */
        fun challenge(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        fun authUrl(callback: String, challenge: String, state: String): String =
            "$AUTH_URL?callback_url=${enc(callback)}&code_challenge=${enc(challenge)}&code_challenge_method=S256" +
                "&state=${enc(state)}&key_label=${enc("NotiSift")}"

        /**
         * Reads a request line like "GET /callback?code=abc&state=xyz HTTP/1.1". Null for anything that isn't the
         * callback; a failure when the user said no or the state doesn't match (another page can't inject a code).
         */
        fun parseCallback(requestLine: String, state: String): Result<String>? {
            val target = requestLine.split(' ').getOrNull(1) ?: return null
            if (!target.startsWith("/callback")) return null
            val params = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { dec(it.substringBefore('=')) to dec(it.substringAfter('=')) }
            val code = params["code"]
            return when {
                code.isNullOrBlank() -> Result.failure(IllegalStateException("Sign-in was cancelled."))
                params["state"] != state -> Result.failure(IllegalStateException("That sign-in didn't come from NotiSift. Try again."))
                else -> Result.success(code)
            }
        }

        fun parseKey(json: String): String? = runCatching {
            Json.parseToJsonElement(json).jsonObject["key"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun donePage(ok: Boolean, appId: String): String {
            // Chrome opens an app from an intent: link only on a tap, so the page offers a button back.
            val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=$appId;end"
            val message = if (ok) "Connected. NotiSift is finishing up." else "Sign-in didn't complete."
            return "<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:24px'>" +
                "<h2>$message</h2><p><a href='$back' style='font-size:20px'>Back to NotiSift</a></p></body>"
        }

        private fun httpResponse(page: String?): String {
            val body = page ?: "Not found"
            val status = if (page == null) "404 Not Found" else "200 OK"
            return "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
