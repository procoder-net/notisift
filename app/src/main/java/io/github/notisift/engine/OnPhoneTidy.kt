package io.github.notisift.engine

import io.github.notisift.llm.DecisionModel

/**
 * What the phone's own AI does while NotiSift is open: it re-reads recent notifications the offline rules sorted
 * and teaches NotiSift the confident answers, so later ones like them are sorted that way in the background too.
 * Each kind of notification is asked about once per app run, and a pass asks at most [maxPerPass] (AICore limits
 * how often one app may use the model).
 */
class OnPhoneTidy(private val engine: DecisionEngine, private val maxPerPass: Int = MAX_PER_PASS) {
    private val asked = HashSet<String>()

    /** [recent] newest first. Returns how many kinds of notification were learned; stops at the first model failure. */
    suspend fun run(recent: List<NotificationInput>, settings: EngineSettings, model: DecisionModel): Int {
        val fresh = synchronized(asked) {
            recent.distinctBy { it.templateKey }.filter { it.templateKey !in asked }.take(maxPerPass).onEach { asked += it.templateKey }
        }
        var learned = 0
        for ((i, n) in fresh.withIndex()) {
            when (engine.learn(n, settings, model)) {
                DecisionEngine.Learned.YES -> learned++
                DecisionEngine.Learned.FAILED -> {
                    // Busy, over quota or sent to the background: the rest wait for the next open. This one isn't
                    // retried, so a notification the model can't answer never blocks the others.
                    synchronized(asked) { asked -= fresh.drop(i + 1).map { it.templateKey }.toSet() }
                    break
                }
                else -> Unit
            }
        }
        return learned
    }

    companion object {
        const val MAX_PER_PASS = 10
        /**
         * Sources worth a second opinion: what the offline rules guessed. With the user's own AI model set up, its
         * answers (and its doubts) stand; only what it never saw is asked about.
         */
        fun guessed(hasCloudModel: Boolean) =
            if (hasCloudModel) listOf(Source.HEURISTIC) else listOf(Source.HEURISTIC, Source.FALLBACK, Source.INBOX)

        /** Chats, codes and money are never learned from, whatever the rules guessed for them. */
        fun worthAsking(category: Category) = category != Category.PERSONAL && !category.protected && category != Category.FINANCE
    }
}
