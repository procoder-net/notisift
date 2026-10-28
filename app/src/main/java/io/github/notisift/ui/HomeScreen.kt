package io.github.notisift.ui

import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.notisift.data.Entry
import io.github.notisift.engine.BusyChat
import io.github.notisift.engine.Category
import io.github.notisift.engine.ChatMode
import io.github.notisift.engine.ChatRules
import io.github.notisift.engine.Tier

/**
 * Home: everything NotiSift moved out of the shade, and nothing that is still in it. Each notification
 * lives in exactly one place, so important ones are not listed here again.
 */
@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier, tier: String, onTier: (String) -> Unit, selectedCategory: String?, onCategory: (String?) -> Unit) {
    val entries by vm.entries.collectAsState()
    val shown = Tier.fromId(tier) ?: Tier.LATER
    val counts = remember(entries) { entries.groupingBy { Tier.of(it.action) }.eachCount() }
    val filtered = remember(entries, shown) { entries.filter { Tier.of(it.action) == shown } }
    val groups = remember(filtered) { filtered.groupBy { Category.fromId(it.category) }.toSortedMap(compareBy { it.ordinal }) }
    val selected = selectedCategory?.let(Category::fromId)?.takeIf { it in groups }
    val visible = if (selected != null) groups.filterKeys { it == selected } else groups
    val settings by vm.settings.collectAsState()
    val byDay = settings?.groupByDay == true
    val busy by vm.busyChats.collectAsState()
    var busyDismissed by rememberSaveable { mutableStateOf(false) }
    // Same notifications either way: by group (Orders, News…) or split into Today, Yesterday, Monday…
    val sections = remember(visible, byDay) {
        if (byDay) {
            byDay(visible.values.flatten(), Entry::postedAt, System.currentTimeMillis())
                .map { Section("d-${it.day}", it.title, it.items, showGroup = true) }
        } else {
            visible.map { (c, list) -> Section("h-${c.id}", "${c.emoji()}  ${c.label}", list, showGroup = false) }
        }
    }
    val wide = isWide()

    val header: LazyListScope.() -> Unit = {
        item(key = "status") { Box(Modifier.tourTarget("status")) { StatusCard(vm) } }
        // Tiers scroll on their own row so big counts and large text never squeeze them; Clear all sits below.
        item(key = "tiers") {
            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 4.dp).tourTarget("tiers"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Tier.entries) { t ->
                    FilterChip(selected = shown == t, onClick = { onTier(t.id) }, label = { Text("${t.emoji} ${t.label} ${counts[t] ?: 0}") })
                }
            }
        }
        if (busy.isNotEmpty() && !busyDismissed) item(key = "busy") { BusyChatsCard(busy, onMentions = { vm.setChatModes(busy, ChatMode.MENTIONS) }, onLater = { busyDismissed = true }) }
        if (filtered.isNotEmpty()) item(key = "clear-all") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !byDay, onClick = { vm.setGroupByDay(false) }, label = { Text("By group") })
                FilterChip(selected = byDay, onClick = { vm.setGroupByDay(true) }, label = { Text("By day") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.clearTier(filtered) }) { Text("Clear all ${filtered.size}") }
            }
        }
        if (filtered.isEmpty()) item(key = "empty") {
            Text(
                when (shown) {
                    Tier.IMPORTANT -> "Nothing important right now. Chats, codes and anything that matters go on top of your drawer."
                    Tier.LATER -> "All caught up. Promotions, news and updates wait here instead of in your drawer."
                    Tier.JUNK -> "No junk. Repeated ads and spam land here instead of your drawer."
                },
                Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!wide && groups.size > 1) item(key = "cats") {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = selected == null, onClick = { onCategory(null) }, label = { Text("Everything") }) }
                items(groups.keys.toList()) { c ->
                    FilterChip(selected = selected == c, onClick = { onCategory(c.id) }, label = { Text("${c.emoji()} ${c.label} ${groups.getValue(c).size}") })
                }
            }
        }
    }

    if (wide && groups.isNotEmpty()) {
        Row(modifier) {
            LazyColumn(Modifier.width(280.dp).fillMaxHeight()) {
                item { CategoryRow(null, filtered.size, selected == null) { onCategory(null) } }
                items(groups.keys.toList()) { c -> CategoryRow(c, groups.getValue(c).size, selected == c) { onCategory(c.id) } }
            }
            EntryList(vm, sections, shown, Modifier.weight(1f), header)
        }
    } else {
        EntryList(vm, sections, shown, modifier, header)
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

/** A titled run of entries on Home: one group, or one calendar day. */
private data class Section(val key: String, val title: String, val entries: List<Entry>, val showGroup: Boolean)

@Composable
private fun EntryList(vm: MainViewModel, sections: List<Section>, tier: Tier, modifier: Modifier, header: LazyListScope.() -> Unit) {
    // Tap a header to fold it away, like a stack in the drawer.
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var opened by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header()
        if (sections.isNotEmpty()) item(key = "hint") {
            Text("Swipe → to show in the drawer · swipe ← when done", Modifier.tourTarget("swipe"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sections.forEach { section ->
            val list = section.entries
            val folded = section.key in collapsed
            item(key = section.key) {
                Row(
                    Modifier.fillMaxWidth().clickable { collapsed = if (folded) collapsed - section.key else collapsed + section.key }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (folded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (folded) "Expand" else "Collapse")
                    Text("${section.title} · ${list.size}", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.clearTier(list) }) { Text("Clear") }
                }
            }
            if (folded) return@forEach
            if (!section.showGroup) {
                items(list, key = { it.id }) { e -> SwipeableEntry(vm, e, tier, showGroup = false) }
                return@forEach
            }
            // By day, each day opens into its groups first, like the drawer's day row; tap a group to see its notifications.
            biggestFirst(list) { Category.fromId(it.category) }.forEach { (category, items) ->
                val sub = "${section.key}/${category.id}"
                val open = sub in opened
                item(key = sub) {
                    DayGroupRow(category, items, open, onToggle = { opened = if (open) opened - sub else opened + sub }, onClear = { vm.clearTier(items) })
                }
                if (open) items(items, key = { it.id }) { e -> SwipeableEntry(vm, e, tier, showGroup = true) }
            }
        }
    }
}

/** One group inside a day: "📰 News, weather & sports · 54 · inshorts, Google News +2". Closed until tapped. */
@Composable
private fun DayGroupRow(category: Category, items: List<Entry>, open: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    val apps = items.map { it.appLabel }.distinct()
    Card(Modifier.fillMaxWidth().padding(start = 12.dp).clickable(onClick = onToggle), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Collapse" else "Expand")
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text("${category.emoji()}  ${category.label} · ${items.size}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(apps.take(3).joinToString(", ") + if (apps.size > 3) " +${apps.size - 3}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/** "This chat:" choices on a message card. Tapping the selected one again clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatChoice(vm: MainViewModel, e: Entry) {
    val rules by vm.chatRules.collectAsState()
    val current = rules[ChatRules.key(e.packageName, e.title)]
    Text("${ChatRules.chatName(e.title)}:", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ChatMode.ALWAYS to "⭐ Always on top", ChatMode.MENTIONS to "@ Only when mentioned", ChatMode.MUTE to "🔕 Mute").forEach { (mode, label) ->
            FilterChip(selected = current == mode, onClick = { vm.setChatMode(e, if (current == mode) null else mode) }, label = { Text(label) })
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

/** Swipe right: back to the drawer as important. Swipe left: done. */
@Composable
private fun SwipeableEntry(vm: MainViewModel, e: Entry, tier: Tier, showGroup: Boolean) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> if (tier == Tier.IMPORTANT) false else { vm.markImportant(e); true }
            SwipeToDismissBoxValue.EndToStart -> { vm.dismiss(e); true }
            SwipeToDismissBoxValue.Settled -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = tier != Tier.IMPORTANT,
        backgroundContent = {
            val toDrawer = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalArrangement = if (toDrawer) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (toDrawer) "⭐ Show in drawer" else "✓ Done", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        },
    ) { EntryCard(vm, e, tier, showGroup) }
}

@Composable
private fun EntryCard(vm: MainViewModel, e: Entry, tier: Tier, showGroup: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.packageName, 20.dp)
                Text(e.appLabel, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                // By day the headers above already give the date and group, so the card shows the clock time.
                Text(
                    if (showGroup) DateUtils.formatDateTime(LocalContext.current, e.postedAt, DateUtils.FORMAT_SHOW_TIME)
                    else DateUtils.getRelativeTimeSpanString(e.postedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (e.title.isNotBlank() && e.title != e.appLabel) Text(e.title, fontWeight = FontWeight.SemiBold, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis)
            if (!e.applied) Text("Preview: still in the drawer, nothing moved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                Text("Why: ${e.reason.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.category == Category.PERSONAL.id) ChatChoice(vm, e)
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.open(e) }) { Text("Open") }
                    if (tier != Tier.IMPORTANT) TextButton(onClick = { vm.markImportant(e) }) { Text("Show in drawer") }
                    if (tier != Tier.JUNK) TextButton(onClick = { vm.markJunk(e) }) { Text("Junk") }
                    TextButton(onClick = { vm.dismiss(e) }) { Text("Done") }
                }
            }
        }
    }
}
