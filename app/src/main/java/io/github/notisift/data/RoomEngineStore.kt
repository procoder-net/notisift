package io.github.notisift.data

import io.github.notisift.scan.Offline
import io.github.notisift.engine.Action
import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import io.github.notisift.engine.EngineStore
import io.github.notisift.engine.Source
import io.github.notisift.engine.Verdict

class RoomEngineStore(
    private val dao: SiftDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : EngineStore {
    override suspend fun appMode(packageName: String): AppMode? = dao.policy(packageName)?.mode?.let(AppMode::fromId)
    override suspend fun appInstructions(packageName: String): String = dao.policy(packageName)?.instructions.orEmpty()
    /**
     * What kind of app this is: the built-in app list first (Play files LinkedIn and Webull under "productivity"),
     * then the Play Store type, then the scan's group.
     */
    override suspend fun appCategory(packageName: String): String {
        val p = dao.policy(packageName) ?: return ""
        return Offline.match(p.packageName, p.label)?.second?.takeIf { it != Category.SYSTEM }?.id
            ?: p.appCategory.ifBlank { p.suggestedCategory }
    }

    @Volatile private var relevant: Pair<Long, List<Category>>? = null

    /** Groups relevant to the apps on this phone; recomputed every 10 minutes (new installs, new scans). */
    override suspend fun categories(): List<Category> {
        relevant?.takeIf { System.currentTimeMillis() - it.first < 10 * 60 * 1000L }?.let { return it.second }
        val names = dao.allPolicies().flatMap { listOf(it.packageName, it.label) }
        val seen = dao.seenCategories().mapTo(mutableSetOf()) { Category.fromId(it) }
        return Category.relevant(names, seen).also { relevant = System.currentTimeMillis() to it }
    }

    override suspend fun cached(templateKey: String): Verdict? {
        val c = dao.cached(templateKey) ?: return null
        if (!c.pinned && clock() - c.createdAt > CACHE_TTL_MS) return null
        val action = Action.fromId(c.action) ?: return null
        val source = if (c.pinned) Source.CORRECTION else Source.CACHE
        return Verdict(action, Category.fromId(c.category), c.confidence, source, c.reason)
    }

    override suspend fun remember(templateKey: String, verdict: Verdict) {
        val existing = dao.cached(templateKey)
        if (existing?.pinned == true) return
        dao.cache(
            CachedDecision(
                templateKey, verdict.action.id, verdict.category.id, verdict.confidence,
                verdict.source.name, verdict.reason, clock(),
            ),
        )
    }

    /** A user correction beats every automatic decision for the same template. */
    suspend fun pinCorrection(templateKey: String, action: Action, category: Category) {
        dao.cache(CachedDecision(templateKey, action.id, category.id, 1.0, Source.CORRECTION.name, "You chose ${action.label.lowercase()}", clock(), pinned = true))
    }

    companion object {
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
