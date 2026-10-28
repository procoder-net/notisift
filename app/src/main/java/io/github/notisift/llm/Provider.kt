package io.github.notisift.llm

/**
 * How a provider is spoken to on the wire. DECISIONS = classification APIs that return a choice with
 * probabilities (state + questions → answers). The rest are chat models prompted for JSON.
 */
enum class Protocol { DECISIONS, PERPLEXITY_AGENT, OPENAI_CHAT, ANTHROPIC_MESSAGES, GEMINI }

enum class ProviderKind(
    val id: String,
    val label: String,
    val protocol: Protocol,
    val defaultBaseUrl: String,
    /** Blank means "pick automatically from the provider's model list". */
    val defaultModel: String,
    val suggestedModels: List<String> = emptyList(),
    val needsKey: Boolean = true,
    /** Path after /v1/ for DECISIONS providers. */
    val decisionPath: String = "decisions",
) {
    PERPLEXITY(
        "perplexity", "Perplexity Decisions", Protocol.DECISIONS, "https://api.perplexity.ai", "pplx-decider-latest",
        listOf("pplx-decider-latest", "pplx-decider-flash-latest"),
    ),
    JEV("jev", "Jev (typesafe.ai)", Protocol.DECISIONS, "https://api.typesafe.ai", "jev-latest", listOf("jev-latest"), decisionPath = "systemone"),
    FASTJEV("fastjev", "FastJev (self-hosted)", Protocol.DECISIONS, "http://127.0.0.1:8000", "fastjev-local", needsKey = false, decisionPath = "systemone"),
    PERPLEXITY_AGENT(
        "perplexity-agent", "Perplexity Agent (chat models)", Protocol.PERPLEXITY_AGENT, "https://api.perplexity.ai", "openai/gpt-6-luna",
        listOf(
            "openai/gpt-6-luna", "anthropic/claude-haiku-5-5", "perplexity/glm-5.3-flash",
            "google/gemini-3.8-flash", "perplexity/nemotron-3.5-super-120b-a12b", "perplexity/sonar",
            "anthropic/claude-sonnet-5-5", "openai/gpt-6.1-sol",
        ),
    ),
    OPENAI("openai", "OpenAI", Protocol.OPENAI_CHAT, "https://api.openai.com/v1", ""),
    ANTHROPIC(
        "anthropic", "Anthropic", Protocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-haiku-5-5",
        listOf("claude-haiku-5-5", "claude-sonnet-5-5"),
    ),
    GEMINI("gemini", "Google Gemini", Protocol.GEMINI, "https://generativelanguage.googleapis.com", "gemini-3.8-flash"),
    OPENROUTER("openrouter", "OpenRouter", Protocol.OPENAI_CHAT, "https://openrouter.ai/api/v1", ""),
    GROQ("groq", "Groq", Protocol.OPENAI_CHAT, "https://api.groq.com/openai/v1", ""),
    OLLAMA("ollama", "Ollama / LM Studio (local)", Protocol.OPENAI_CHAT, "http://192.168.0.10:11434/v1", "", needsKey = false),
    CUSTOM("custom", "Custom OpenAI-compatible", Protocol.OPENAI_CHAT, "", "", needsKey = false),
    ;

    companion object {
        fun fromId(id: String?): ProviderKind? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** Fully resolved connection settings. */
data class ModelConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isUsable: Boolean get() = baseUrl.isNotBlank() && (!kind.needsKey || apiKey.isNotBlank())

    /** Never let the key reach logs or crash reports. */
    override fun toString() = "ModelConfig(kind=$kind, baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "<none>" else "<redacted>"}, model=$model)"
}

/**
 * Works out which provider a user means from whatever they typed: an explicit id, an env-var
 * name, the key's prefix or the base URL. Users should be able to paste a key and go.
 */
object ProviderDetector {
    private val envNames = mapOf(
        "PERPLEXITY_API_KEY" to ProviderKind.PERPLEXITY, "PPLX_API_KEY" to ProviderKind.PERPLEXITY,
        "JEV_API_KEY" to ProviderKind.JEV, "OPENAI_API_KEY" to ProviderKind.OPENAI, "ANTHROPIC_API_KEY" to ProviderKind.ANTHROPIC,
        "GEMINI_API_KEY" to ProviderKind.GEMINI, "GOOGLE_API_KEY" to ProviderKind.GEMINI,
        "OPENROUTER_API_KEY" to ProviderKind.OPENROUTER, "GROQ_API_KEY" to ProviderKind.GROQ,
    )

    fun detect(explicit: String? = null, apiKey: String = "", baseUrl: String = "", keyEnvName: String = ""): ProviderKind {
        ProviderKind.fromId(explicit)?.let { return it }
        fromUrl(baseUrl)?.let { return it }
        fromKey(apiKey)?.let { return it }
        envNames[keyEnvName]?.let { return it }
        return if (baseUrl.isNotBlank()) ProviderKind.CUSTOM else ProviderKind.PERPLEXITY
    }

    fun fromKey(key: String): ProviderKind? {
        val k = key.trim()
        return when {
            k.isEmpty() -> null
            k.startsWith("pplx-") -> ProviderKind.PERPLEXITY
            k.startsWith("sk-ant-") -> ProviderKind.ANTHROPIC
            k.startsWith("sk-or-") -> ProviderKind.OPENROUTER
            k.startsWith("gsk_") -> ProviderKind.GROQ
            k.startsWith("AIza") -> ProviderKind.GEMINI
            k.startsWith("sk-") -> ProviderKind.OPENAI
            else -> null
        }
    }

    fun fromUrl(url: String): ProviderKind? {
        val u = url.trim().lowercase()
        return when {
            u.isEmpty() -> null
            "perplexity.ai" in u -> ProviderKind.PERPLEXITY
            "typesafe.ai" in u -> ProviderKind.JEV
            ":8000" in u -> ProviderKind.FASTJEV
            "anthropic.com" in u -> ProviderKind.ANTHROPIC
            "generativelanguage.googleapis.com" in u -> ProviderKind.GEMINI
            "openrouter.ai" in u -> ProviderKind.OPENROUTER
            "groq.com" in u -> ProviderKind.GROQ
            "api.openai.com" in u -> ProviderKind.OPENAI
            ":11434" in u || ":1234" in u -> ProviderKind.OLLAMA
            else -> null
        }
    }

    /** Fills blanks with the provider's defaults so a key alone is enough. */
    fun resolve(explicit: String?, apiKey: String, baseUrl: String, model: String, keyEnvName: String = ""): ModelConfig {
        val kind = detect(explicit, apiKey, baseUrl, keyEnvName)
        return ModelConfig(
            kind = kind,
            baseUrl = baseUrl.trim().ifBlank { kind.defaultBaseUrl }.trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim().ifBlank { kind.defaultModel },
        )
    }

    /** Prefer small/fast models when we have to choose one from a list for the user. */
    fun pickCheapModel(models: List<String>): String? {
        val hints = listOf("luna", "haiku", "flash-lite", "flash", "mini", "nano", "small", "8b", "7b", "lite")
        val usable = models.filterNot { m -> listOf("embed", "whisper", "tts", "image", "dall", "moderation", "audio", "vision-only").any { it in m.lowercase() } }
        return hints.firstNotNullOfOrNull { h -> usable.firstOrNull { h in it.lowercase() } } ?: usable.firstOrNull()
    }
}
