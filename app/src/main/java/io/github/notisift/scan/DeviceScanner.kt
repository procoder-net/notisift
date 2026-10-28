package io.github.notisift.scan

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.notisift.data.SiftDao
import io.github.notisift.engine.AppMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds an inventory of every app on the device plus what NotiSift has observed it post.
 * Android does not let ordinary apps read other apps' notification channels, so observed
 * history fills that gap and the scan gets better the longer the listener runs.
 */
class DeviceScanner(private val context: Context, private val dao: SiftDao) {

    suspend fun scan(windowDays: Int = 14): List<AppProfile> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val since = System.currentTimeMillis() - windowDays * 24L * 60 * 60 * 1000
        val counts = dao.countsByApp(since).associateBy { it.packageName }
        val channels = dao.channelCounts(since).groupBy { it.packageName }
        val titles = dao.sampleTitles(since).groupBy({ it.packageName }, { it.title })
        val policies = dao.allPolicies().associateBy { it.packageName }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = queryActivities(pm, launcher).map { it.activityInfo.packageName }.toSet()

        installed(pm).asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.packageName in launchable || it.packageName in counts }
            .map { info ->
                val pkg = info.packageName
                AppProfile(
                    packageName = pkg,
                    label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                    appCategory = categoryName(info.category),
                    launchable = pkg in launchable,
                    notificationCount = counts[pkg]?.total ?: 0,
                    channels = channels[pkg].orEmpty().sortedByDescending { it.total }.map { it.channelId }.filter { it.isNotBlank() },
                    sampleTitles = titles[pkg].orEmpty().distinct().take(6),
                    currentMode = policies[pkg]?.mode?.let(AppMode::fromId),
                )
            }
            .sortedWith(compareByDescending<AppProfile> { it.notificationCount }.thenBy { it.label.lowercase() })
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun installed(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else pm.getInstalledApplications(0)

    @Suppress("DEPRECATION")
    private fun queryActivities(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentActivities(intent, 0)

    private fun categoryName(c: Int): String = when (c) {
        ApplicationInfo.CATEGORY_GAME -> "game"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "image"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        else -> ""
    }
}
