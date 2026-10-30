package io.github.notisift.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.notisift.data.Entry
import io.github.notisift.engine.BusyChat
import io.github.notisift.engine.Category
import io.github.notisift.engine.ChatMode
import io.github.notisift.engine.Tier

/** Clear all on more than this many asks first: Undo is easy to miss, and Cleared is a tab away. */
private const val CONFIRM_CLEAR_OVER = 50

/**
 * Home: everything NotiSift moved out of the shade, and nothing that is still in it. Each notification
 * lives in exactly one place, so important ones are not listed here again. Cleared ones wait in Cleared.
 */
@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier, tier: String, onTier: (String) -> Unit, selectedCategory: String?, onCategory: (String?) -> Unit, onAddAiKey: () -> Unit,
    searchOpen: Boolean, onSearchOpen: (Boolean) -> Unit, scrollToTop: Int,
) {
    LaunchedEffect(tier) { vm.showHome(tier) }
    val view by vm.home.collectAsState()
    val entries by vm.homeEntries.collectAsState()
    val counts by vm.openCounts.collectAsState()
    val clearedCount by vm.clearedCount.collectAsState()
    val shown = view.tier
    val groups = remember(entries, shown) { if (shown == null) emptyMap() else entries.groupBy { Category.fromId(it.category) }.toSortedMap(compareBy { it.ordinal }) }
    val selected = selectedCategory?.let(Category::fromId)?.takeIf { it in groups }
    val visible = remember(entries, groups, selected) { if (selected != null) groups.getValue(selected) else entries }
    val settings by vm.settings.collectAsState()
    val byDay = settings?.groupByDay == true
    val busy by vm.busyChats.collectAsState()
    var busyDismissed by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<Tier?>(null) }
    var confirmRestore by remember { mutableStateOf<List<Entry>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTop) { if (scrollToTop > 0) listState.animateScrollToItem(0) }
    // The search field sits at the top of the list, so bring it into view when search opens.
    LaunchedEffect(searchOpen) { if (searchOpen) listState.scrollToItem(0) }
    val policies by vm.policies.collectAsState()
    // Offered once the apps are set up, never during the first-run steps, and gone for good after "Not now".
    val offerAi = settings?.let { !it.aiTipDismissed && !it.stopped && it.aiEnabled } == true && policies.isNotEmpty() && vm.cloudProvider() == null
    val nothingYet = counts.values.sum() == 0 && clearedCount == 0 && !view.searching
    val sections = remember(visible, view, byDay) { homeSections(visible, view, byDay) }
    val wide = isWide()
    val tierTotal = shown?.let { counts[it] } ?: 0
    val endSearch = { vm.searchHome(""); onSearchOpen(false) }
    val onRestore: (List<Entry>) -> Unit = { list -> if (list.size > CONFIRM_CLEAR_OVER) confirmRestore = list else vm.restore(list) }

    val header: LazyListScope.() -> Unit = {
        item(key = "status") { Box(Modifier.tourTarget("status")) { StatusCard(vm) } }
        // Tiers scroll on their own row so big counts and large text never squeeze them; Clear all sits below.
        item(key = "tiers") {
            LazyRow(Modifier.padding(top = 4.dp).tourTarget("tiers"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(Tier.entries) { t ->
                    FilterChip(selected = !view.searching && shown == t, onClick = { endSearch(); onTier(t.id) }, label = { Text("${t.emoji} ${t.label} ${counts[t] ?: 0}") })
                }
                item { FilterChip(selected = view.cleared, onClick = { endSearch(); onTier(HomeView.CLEARED) }, label = { Text("↩️ Cleared $clearedCount") }) }
            }
        }
        if (searchOpen) item(key = "search") {
            // Opening search means typing next, so the field takes focus and the keyboard comes up.
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                view.search, vm::searchHome, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                label = { Text("Search by app, sender or text") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            )
        }
        if (nothingYet && settings?.stopped == false) item(key = "sample") { SampleInbox() }
        if (offerAi) item(key = "ai") { AiKeyCard(onAddKey = onAddAiKey, onNotNow = { vm.updateSettings { it.copy(aiTipDismissed = true) } }) }
        if (busy.isNotEmpty() && !busyDismissed) item(key = "busy") { BusyChatsCard(busy, onMentions = { vm.setChatModes(busy, ChatMode.MENTIONS) }, onLater = { busyDismissed = true }) }
        if (shown != null && entries.isNotEmpty()) item(key = "clear-all") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !byDay, onClick = { vm.setGroupByDay(false) }, label = { Text("By group") })
                FilterChip(selected = byDay, onClick = { vm.setGroupByDay(true) }, label = { Text("By day") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (tierTotal > CONFIRM_CLEAR_OVER) confirmClear = shown else vm.clearAll(shown) }) { Text("Clear all $tierTotal") }
            }
        }
        if (entries.isEmpty() && !nothingYet) item(key = "empty") {
            Text(emptyText(view, settings?.retentionDays ?: 14), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!wide && groups.size > 1) item(key = "cats") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = selected == null, onClick = { onCategory(null) }, label = { Text("Everything") }) }
                items(groups.keys.toList()) { c ->
                    FilterChip(selected = selected == c, onClick = { onCategory(c.id) }, label = { Text("${c.emoji()} ${c.label} ${groups.getValue(c).size}") })
                }
            }
        }
    }
    val footer: LazyListScope.() -> Unit = {
        // The query hit its limit, so there may be more: load the next page instead of hiding it.
        if (entries.size >= view.limit) item(key = "more") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Showing the newest ${entries.size}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = vm::showMore) { Text("Show more") }
            }
        }
    }

    confirmClear?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Clear all ${counts[t] ?: 0} in ${t.label}?") },
            text = { Text("They move to Cleared, where you can restore them for ${settings?.retentionDays ?: 14} days.") },
            confirmButton = { TextButton(onClick = { vm.clearAll(t); confirmClear = null }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("Cancel") } },
        )
    }

    confirmRestore?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore ${list.size} notifications?") },
            text = { Text("They go back to Home, and important ones back on top of your drawer.") },
            confirmButton = { TextButton(onClick = { vm.restore(list); confirmRestore = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }

    if (wide && groups.isNotEmpty()) {
        Row(modifier) {
            LazyColumn(Modifier.width(280.dp).fillMaxHeight()) {
                item { CategoryRow(null, entries.size, selected == null) { onCategory(null) } }
                items(groups.keys.toList()) { c -> CategoryRow(c, groups.getValue(c).size, selected == c) { onCategory(c.id) } }
            }
            EntryList(vm, sections, view.cleared, Modifier.weight(1f), listState, onRestore, header, footer)
        }
    } else {
        EntryList(vm, sections, view.cleared, modifier, listState, onRestore, header, footer)
    }
}

private fun emptyText(view: HomeView, retentionDays: Int): String = when {
    view.searching -> "No notifications match “${view.search.trim()}”."
    view.cleared -> "Nothing cleared. Anything you clear or swipe away, here or in the drawer, waits here for $retentionDays days so you can put it back."
    view.tier == Tier.IMPORTANT -> "Nothing important right now. Chats, codes and anything that matters go on top of your drawer."
    view.tier == Tier.JUNK -> "No junk. Repeated ads and spam land here instead of your drawer."
    else -> "All caught up. Promotions, news and updates wait here instead of in your drawer."
}

/** A titled run of entries on Home: one group, one calendar day, one tier of search results, or one day of clearing. */
private data class Section(val key: String, val title: String, val entries: List<Entry>, val byGroup: Boolean)

private fun homeSections(visible: List<Entry>, view: HomeView, byDay: Boolean): List<Section> {
    val now = System.currentTimeMillis()
    return when {
        view.searching -> visible.groupBy { Tier.of(it.action) }.toSortedMap(compareBy { it.ordinal })
            .map { (t, list) -> Section("s-${t.id}", "${t.emoji}  ${t.label}", list, byGroup = false) }
        view.cleared -> {
            // Cleared before 0.1.2 recorded when: those have no clearing day, so they share one last section.
            val (dated, undated) = visible.partition { it.dismissedAt > 0 }
            byDay(dated, Entry::dismissedAt, now)
                .map { Section("c-${it.day}", "Cleared ${it.title.lowercase().takeIf { t -> t == "today" || t == "yesterday" } ?: it.title}", it.items, byGroup = false) } +
                listOfNotNull(undated.takeIf { it.isNotEmpty() }?.let { Section("c-earlier", "Cleared earlier", it, byGroup = false) })
        }
        byDay -> byDay(visible, Entry::postedAt, now).map { Section("d-${it.day}", it.title, it.items, byGroup = true) }
        else -> visible.groupBy { Category.fromId(it.category) }.toSortedMap(compareBy { it.ordinal })
            .map { (c, list) -> Section("h-${c.id}", "${c.emoji()}  ${c.label}", list, byGroup = false) }
    }
}

@Composable
private fun CategoryRow(category: Category?, count: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(category?.let { "${it.emoji()}  ${it.label}" } ?: "Everything") },
        trailingContent = { Text("$count") },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun EntryList(
    vm: MainViewModel, sections: List<Section>, cleared: Boolean, modifier: Modifier, listState: LazyListState,
    onRestore: (List<Entry>) -> Unit, header: LazyListScope.() -> Unit, footer: LazyListScope.() -> Unit,
) {
    // Tap a header to fold it away, like a stack in the drawer.
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var opened by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header()
        if (sections.isNotEmpty()) item(key = "hint") {
            Text(
                if (cleared) "Swipe → or tap Restore to put one back" else "Swipe → to show in the drawer · swipe ← when done",
                Modifier.tourTarget("swipe"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sections.forEach { section ->
            val list = section.entries
            val folded = section.key in collapsed
            item(key = section.key) {
                SectionHeader("${section.title} · ${list.size}", folded, onToggle = { collapsed = if (folded) collapsed - section.key else collapsed + section.key }) {
                    if (cleared) TextButton(onClick = { onRestore(list) }) { Text("Restore") }
                    else TextButton(onClick = { vm.clearTier(list) }) { Text("Clear") }
                }
            }
            if (folded) return@forEach
            if (!section.byGroup) {
                items(list, key = { it.id }) { e -> SwipeableEntry(vm, e, cleared, clockTime = false) }
                return@forEach
            }
            // By day, each day opens into its groups first, like the drawer's day row; tap a group to see its notifications.
            biggestFirst(list) { Category.fromId(it.category) }.forEach { (category, items) ->
                val sub = "${section.key}/${category.id}"
                val open = sub in opened
                item(key = sub) {
                    DayGroupRow(category, items, open, onToggle = { opened = if (open) opened - sub else opened + sub }, onClear = { vm.clearTier(items) })
                }
                if (open) items(items, key = { it.id }) { e -> SwipeableEntry(vm, e, cleared = false, clockTime = true) }
            }
        }
        footer()
    }
}

/** A foldable header that tells TalkBack whether it's open and what a tap does. */
@Composable
private fun SectionHeader(title: String, folded: Boolean, onToggle: () -> Unit, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (folded) "Expand" else "Collapse", onClick = onToggle)
            .semantics { stateDescription = if (folded) "Collapsed" else "Expanded" }
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (folded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null)
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        action()
    }
}

/** One group inside a day: "📰 News, weather & sports · 54 · inshorts, Google News +2". Closed until tapped. */
@Composable
private fun DayGroupRow(category: Category, items: List<Entry>, open: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    val apps = items.map { it.appLabel }.distinct()
    Card(
        Modifier.fillMaxWidth().padding(start = 12.dp)
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text("${category.emoji()}  ${category.label} · ${items.size}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(apps.take(3).joinToString(", ") + if (apps.size > 3) " +${apps.size - 3}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/** Offers, never applies: busy group chats that flooded the top today, with one tap to quiet them all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BusyChatsCard(busy: List<BusyChat>, onMentions: () -> Unit, onLater: () -> Unit) {
    val total = busy.sumOf { it.count }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("💬 Busy group chats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${if (busy.size == 1) "This group" else "These ${busy.size} groups"} put $total messages on top today. " +
                    "Show them only when someone mentions you or replies to you? The rest waits in Later.",
                style = MaterialTheme.typography.bodyMedium,
            )
            busy.take(4).forEach { Text("· ${it.chat} (${it.appLabel}) · ${it.count}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (busy.size > 4) Text("· and ${busy.size - 4} more", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onMentions) { Text("Only when mentioned") }
                TextButton(onClick = onLater) { Text("Not now") }
            }
        }
    }
}
