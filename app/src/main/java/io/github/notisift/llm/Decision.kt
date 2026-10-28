package io.github.notisift.llm

/**
 * The plugin contract for decision models. Implement this to add any provider: a decision API,
 * a chat model, a local classifier or plain rules.
 *
 * Mirrors:
 * ```ts
 * interface DecisionModel { id: string; decide(request: DecisionRequest): Promise<DecisionResult> }
 * interface DecisionRequest { context: Record<string, unknown>; options?: string[]; parameters?: Record<string, unknown> }
 * interface DecisionResult { decision: unknown; confidence?: number; explanation?: string; metadata?: Record<string, unknown> }
 * ```
 */
interface DecisionModel {
    val id: String

    suspend fun decide(request: DecisionRequest): DecisionResult

    /**
     * Several decisions at once, results in request order. Override to batch: decision APIs answer
     * many questions about the same context in one call and bill the context once.
     */
    suspend fun decideAll(requests: List<DecisionRequest>): List<DecisionResult> = requests.map { decide(it) }
}

data class DecisionRequest(
    /** What is being decided about. Values: String, Number, Boolean, null, List or Map of those. */
    val context: Map<String, Any?>,
    /** Allowed answers. null means a yes/no decision. */
    val options: List<String>? = null,
    /** Provider hints. Well-known keys are in [DecisionParams]; unknown keys are ignored. */
    val parameters: Map<String, Any?> = emptyMap(),
)

data class DecisionResult(
    /** One of the request's options, or null when the model gave no valid answer. */
    val decision: Any?,
    /** Probability of [decision], 0..1. Real probabilities from decision APIs, self-reported by chat models. */
    val confidence: Double? = null,
    val explanation: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun none(why: String) = DecisionResult(decision = null, explanation = why)
    }
}

/** Well-known [DecisionRequest.parameters] keys. */
object DecisionParams {
    /** String: what the decision is about and the rules to follow. */
    const val INSTRUCTIONS = "instructions"

    /** Map<String, String?>: description for each option. */
    const val OPTION_DESCRIPTIONS = "optionDescriptions"
}

val DecisionRequest.instructions: String get() = parameters[DecisionParams.INSTRUCTIONS] as? String ?: ""

/** Option → description, in option order. Yes/no when [DecisionRequest.options] is null. */
val DecisionRequest.optionMap: Map<String, String?>
    get() {
        val descriptions = parameters[DecisionParams.OPTION_DESCRIPTIONS] as? Map<*, *> ?: emptyMap<Any, Any>()
        return (options ?: listOf("yes", "no")).associateWith { descriptions[it] as? String }
    }

/** The chosen option as a string, if it is one of the allowed options. */
fun DecisionResult.choice(request: DecisionRequest): String? = (decision as? String)?.takeIf { it in request.optionMap }
