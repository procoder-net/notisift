package io.github.notisift.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entries", indices = [Index("postedAt"), Index("packageName"), Index("action")])
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val packageName: String,
    val appLabel: String,
    val channelId: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val category: String,
    val action: String,
    val source: String,
    val confidence: Double,
    val reason: String,
    val templateKey: String,
    /** False in observe mode: the decision was recorded but the notification was left alone. */
    val applied: Boolean,
    val dismissed: Boolean = false,
    /** When it was cleared (swiped, Clear, opened), for "Recently cleared". 0 while it isn't. */
    @ColumnInfo(defaultValue = "0") val dismissedAt: Long = 0,
    /** When the user opened it (from NotiSift or its drawer row); 0 = never. Teaches which chats they read. */
    @ColumnInfo(defaultValue = "0") val openedAt: Long = 0,
)

@Entity(tableName = "policies")
data class Policy(
    @PrimaryKey val packageName: String,
    val label: String,
    val mode: String,
    val instructions: String = "",
    val appCategory: String = "",
    val suggestedMode: String = "",
    val suggestedCategory: String = "",
    val suggestionReason: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cache")
data class CachedDecision(
    @PrimaryKey val templateKey: String,
    val action: String,
    val category: String,
    val confidence: Double,
    val source: String,
    val reason: String,
    val createdAt: Long,
    /** User corrections never expire. */
    val pinned: Boolean = false,
)

data class ActionCount(val action: String, val total: Int)
data class CategoryCount(val category: String, val total: Int)
data class AppCount(val packageName: String, val appLabel: String, val total: Int, val removed: Int)
data class SampleTitle(val packageName: String, val title: String)
data class ChannelCount(val packageName: String, val channelId: String, val total: Int)

@Dao
interface SiftDao {
    @Insert suspend fun insert(entry: Entry): Long

    /** The newest open notifications, for busy-chat detection and the demo; Home pages with [entriesIn]. */
    @Query("SELECT * FROM entries WHERE dismissed = 0 ORDER BY postedAt DESC LIMIT 1000")
    fun entries(): Flow<List<Entry>>

    /** One Home tier, newest first; Home asks for more as you scroll. */
    @Query("SELECT * FROM entries WHERE dismissed = 0 AND action IN (:actions) ORDER BY postedAt DESC LIMIT :limit")
    fun entriesIn(actions: List<String>, limit: Int): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE dismissed = 0 AND action IN (:actions)")
    suspend fun allIn(actions: List<String>): List<Entry>

    /** Everything cleared, swiped or opened (from the app or the drawer), most recently cleared first. */
    @Query("SELECT * FROM entries WHERE dismissed = 1 ORDER BY dismissedAt DESC, postedAt DESC LIMIT :limit")
    fun cleared(limit: Int): Flow<List<Entry>>

    /** [pattern] is a LIKE pattern that escapes with a backslash (see HomeView.likePattern). */
    @Query("SELECT * FROM entries WHERE dismissed = 0 AND (title LIKE :pattern ESCAPE '\\' OR text LIKE :pattern ESCAPE '\\' OR appLabel LIKE :pattern ESCAPE '\\') ORDER BY postedAt DESC LIMIT :limit")
    fun search(pattern: String, limit: Int): Flow<List<Entry>>

    @Query("SELECT action, COUNT(*) AS total FROM entries WHERE dismissed = 0 GROUP BY action")
    fun openCounts(): Flow<List<ActionCount>>

    @Query("SELECT COUNT(*) FROM entries WHERE dismissed = 1")
    fun clearedCount(): Flow<Int>

    @Query("SELECT * FROM entries WHERE id = :id") suspend fun entry(id: Long): Entry?

    @Query("UPDATE entries SET openedAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id IN (:ids)")
    suspend fun markOpened(ids: List<Long>)

    @Query("UPDATE entries SET openedAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE `key` = :key AND postedAt >= :since")
    suspend fun markOpenedKey(key: String, since: Long)

    /** Chat titles from one app since [since] that contain [prefix] (a LIKE pattern, backslash escapes). */
    @Query("SELECT title FROM entries WHERE packageName = :pkg AND category = 'personal' AND postedAt >= :since AND title LIKE :prefix ESCAPE '\\'")
    suspend fun chatTitlesSince(pkg: String, prefix: String, since: Long): List<String>

    /**
     * Chat messages of the last week that NotiSift showed in its own stack (so a tap was seen), with whether they were
     * opened: feeds "you never open this group".
     */
    @Query("SELECT packageName, appLabel, title, text, openedAt > 0 AS opened FROM entries WHERE category = 'personal' AND applied = 1 AND postedAt >= :since ORDER BY postedAt DESC LIMIT 5000")
    fun chatActivity(since: Long): Flow<List<io.github.notisift.engine.ChatActivity>>

    /** Group messages NotiSift quieted on its own today and nobody has handled yet. */
    @Query("SELECT * FROM entries WHERE source = 'SMART_GROUP' AND dismissed = 0 AND postedAt >= :since ORDER BY postedAt DESC")
    fun smartQuieted(since: Long): Flow<List<Entry>>
    @Query("SELECT * FROM entries WHERE action != 'keep' AND applied = 1 AND dismissed = 0 AND postedAt >= :since ORDER BY postedAt DESC")
    suspend fun removedEntriesSince(since: Long): List<Entry>

    /** Important notifications NotiSift re-showed in its stack and the user hasn't tapped or swiped yet. */
    @Query("SELECT * FROM entries WHERE action = 'keep' AND applied = 1 AND dismissed = 0 AND postedAt >= :since ORDER BY postedAt DESC")
    suspend fun shownImportantSince(since: Long): List<Entry>

    @Query("UPDATE entries SET dismissed = :dismissed, dismissedAt = CASE WHEN :dismissed THEN CAST(strftime('%s','now') AS INTEGER) * 1000 ELSE 0 END WHERE id IN (:ids)")
    suspend fun setDismissed(ids: List<Long>, dismissed: Boolean)

    @Query("SELECT id FROM entries WHERE `key` = :key AND action = 'keep' AND dismissed = 0")
    suspend fun openImportantIds(key: String): List<Long>

    /** Other Important rows still showing for the same kind of notification ("Motion detected" again and again). */
    @Query("SELECT * FROM entries WHERE templateKey = :template AND `key` != :key AND action = 'keep' AND applied = 1 AND dismissed = 0 AND postedAt >= :since")
    suspend fun shownLike(template: String, key: String, since: Long): List<Entry>

    @Query("SELECT COUNT(*) FROM entries WHERE templateKey = :template AND action = 'keep' AND postedAt >= :since")
    suspend fun countLike(template: String, since: Long): Int

    @Query("UPDATE entries SET dismissed = 1, dismissedAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE `key` = :key AND action = 'keep' AND dismissed = 0") suspend fun dismissImportant(key: String)
    @Query("UPDATE entries SET dismissed = 1, dismissedAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE dismissed = 0 AND action != 'keep' AND action != 'silence' AND action != 'block'") suspend fun dismissAllLater()

    /** AI-made decisions per app, including handled ones; feeds "make it a rule" suggestions. */
    @Query("SELECT packageName, appLabel, action, COUNT(*) AS total FROM entries WHERE postedAt >= :since AND source IN ('MODEL', 'INBOX', 'CACHE') GROUP BY packageName, action")
    fun decisionTallies(since: Long): Flow<List<io.github.notisift.engine.DecisionTally>>

    @Query("SELECT DISTINCT category FROM entries") suspend fun seenCategories(): List<String>

    @Query("SELECT category, COUNT(*) AS total FROM entries WHERE postedAt >= :since GROUP BY category")
    fun countByCategory(since: Long): Flow<List<CategoryCount>>

    @Query("SELECT action, COUNT(*) AS total FROM entries WHERE postedAt >= :since GROUP BY action")
    fun countByAction(since: Long): Flow<List<ActionCount>>
    @Query("UPDATE entries SET dismissed = 1, dismissedAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = :id") suspend fun dismiss(id: Long)
    @Query("UPDATE entries SET applied = :applied WHERE id = :id") suspend fun setApplied(id: Long, applied: Boolean)
    @Query("UPDATE entries SET dismissed = 1, dismissedAt = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE dismissed = 0 AND category = :category AND action IN (:actions)") suspend fun dismissCategory(category: String, actions: List<String>)
    @Query("UPDATE entries SET action = :action, source = 'CORRECTION' WHERE id = :id") suspend fun correct(id: Long, action: String)
    @Query("DELETE FROM entries WHERE postedAt < :before") suspend fun prune(before: Long)
    /** Drops rows another NotiSift build posted (the demo's sample rows) that older versions sorted by mistake. */
    @Query("DELETE FROM entries WHERE packageName = :base OR packageName LIKE :base || '.%'") suspend fun deleteFromNotiSift(base: String)

    @Query("SELECT COUNT(*) FROM entries WHERE postedAt >= :since") fun countSince(since: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM entries WHERE postedAt >= :since AND action != 'keep'") fun removedSince(since: Long): Flow<Int>

    @Query(
        "SELECT packageName, appLabel, COUNT(*) AS total, SUM(CASE WHEN action != 'keep' THEN 1 ELSE 0 END) AS removed " +
            "FROM entries WHERE postedAt >= :since GROUP BY packageName ORDER BY total DESC LIMIT :limit",
    )
    fun noisiest(since: Long, limit: Int = 8): Flow<List<AppCount>>

    @Query("SELECT packageName, appLabel, COUNT(*) AS total, 0 AS removed FROM entries WHERE postedAt >= :since GROUP BY packageName")
    suspend fun countsByApp(since: Long): List<AppCount>

    @Query("SELECT packageName, channelId, COUNT(*) AS total FROM entries WHERE postedAt >= :since GROUP BY packageName, channelId")
    suspend fun channelCounts(since: Long): List<ChannelCount>

    /** Sample titles for the app scan. Never from people, codes, money or calls: these may be sent to a cloud model. */
    @Query("SELECT DISTINCT packageName, title FROM entries WHERE postedAt >= :since AND title != '' AND category NOT IN ('personal', 'security', 'finance', 'alerts') AND source NOT IN ('PROTECTED', 'OTP') ORDER BY postedAt DESC LIMIT 2000")
    suspend fun sampleTitles(since: Long): List<SampleTitle>

    @Upsert suspend fun upsertPolicy(policy: Policy)
    @Upsert suspend fun upsertPolicies(policies: List<Policy>)
    @Query("SELECT * FROM policies WHERE packageName = :pkg") suspend fun policy(pkg: String): Policy?
    @Query("SELECT * FROM policies ORDER BY label COLLATE NOCASE") fun policies(): Flow<List<Policy>>
    @Query("UPDATE entries SET category = :category WHERE id IN (:ids)") suspend fun setCategory(ids: List<Long>, category: String)
    @Query("SELECT DISTINCT packageName FROM entries WHERE postedAt >= :since") fun activePackages(since: Long): Flow<List<String>>
    @Query("SELECT * FROM policies") suspend fun allPolicies(): List<Policy>

    @Query("SELECT * FROM cache WHERE templateKey = :key") suspend fun cached(key: String): CachedDecision?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(decision: CachedDecision)
    @Query("DELETE FROM cache WHERE pinned = 0 AND createdAt < :before") suspend fun pruneCache(before: Long)
    @Query("DELETE FROM cache WHERE pinned = 0") suspend fun clearLearned()
    /** Your corrections: what NotiSift learned from you, kept forever and carried over in a backup. */
    @Query("SELECT * FROM cache WHERE pinned = 1") suspend fun corrections(): List<CachedDecision>
    @Upsert suspend fun upsertCache(decisions: List<CachedDecision>)
    @Query("DELETE FROM cache WHERE templateKey = :key") suspend fun uncache(key: String)
    @Query("UPDATE entries SET action = :action, source = :source WHERE id = :id") suspend fun restoreDecision(id: Long, action: String, source: String)
    @Query("DELETE FROM entries") suspend fun clearHistory()
    @Query("SELECT * FROM entries WHERE dismissed = 0 AND action = 'keep' AND packageName IN (:packages)") suspend fun keptFrom(packages: List<String>): List<Entry>
    @Query("UPDATE entries SET action = :action, source = :source WHERE id IN (:ids)") suspend fun setDecision(ids: List<Long>, action: String, source: String)
}

@Database(entities = [Entry::class, Policy::class, CachedDecision::class], version = 3, exportSchema = true)
abstract class SiftDatabase : RoomDatabase() {
    abstract fun dao(): SiftDao

    companion object {
        fun create(context: Context): SiftDatabase =
            Room.databaseBuilder(context, SiftDatabase::class.java, "notisift.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        /** 0.1.2: remember when each notification was cleared, so cleared ones can be found and restored. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN dismissedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 0.1.2: remember what was opened, so NotiSift can offer to quiet chats that are never read. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN openedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
