package io.github.notisift.llm

import kotlinx.serialization.json.JsonObject

/**
 * The AI built into the phone (Gemini Nano), as a [DecisionModel] and a summary writer. Nothing leaves the phone and
 * no key is needed. Android only lets it answer while NotiSift is on screen, so it teaches the rules when the app is
 * opened instead of sorting live (see [io.github.notisift.engine.OnPhoneTidy]).
 *
 * [generate] is the raw model call (system text, user text, max tokens → reply), so tests can stand in for it.
 */
class OnPhoneModel(private val generate: suspend (system: String, user: String, maxTokens: Int) -> String) : DecisionModel {
    override val id: String get() = ID

    override suspend fun decide(request: DecisionRequest): DecisionResult = decideAll(listOf(request)).single()

    override suspend fun decideAll(requests: List<DecisionRequest>): List<DecisionResult> =
        ChatDecisions.decideAll(requests, id, calibrated = false) { state, questions -> ChatDecisions.answers(state.toString(), questions, ::completeJson) }

    /** Small models follow a schema in the prompt better than a bare instruction, so it is always spelled out. */
    suspend fun completeJson(request: JsonRequest): JsonObject {
        val system = request.system + "\n\nReply with ONLY one JSON object matching this JSON Schema, no prose:\n" + request.schema
        val text = generate(system, request.user, request.maxTokens)
        return JsonExtract.firstObject(text) ?: throw LlmException("On-phone AI did not return JSON")
    }

    companion object {
        const val ID = "on-phone:gemini-nano"
    }
}
