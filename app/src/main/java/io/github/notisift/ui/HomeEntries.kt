package io.github.notisift.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.notisift.data.Entry
import io.github.notisift.engine.Category
import io.github.notisift.engine.ChatMode
import io.github.notisift.engine.ChatRules
import io.github.notisift.engine.Tier

/**
 * Swipe right: back to the drawer as important (or, in Cleared, back where it was). Swipe left: done.
 * The same actions are offered to TalkBack, so nobody needs the gesture.
 */
@Composable
internal fun SwipeableEntry(vm: MainViewModel, e: Entry, cleared: Boolean, clockTime: Boolean) {
    val tier = Tier.of(e.action)
    val canRight = cleared || tier != Tier.IMPORTANT
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> when {
                cleared -> { vm.restore(listOf(e)); true }
                canRight -> { vm.markImportant(e); true }
                else -> false
            }
            SwipeToDismissBoxValue.EndToStart -> if (cleared) false else { vm.dismiss(e); true }
            SwipeToDismissBoxValue.Settled -> false
        }
    })
    val actions = buildList {
        if (cleared) add(CustomAccessibilityAction("Restore") { vm.restore(listOf(e)); true })
        else {
            if (tier != Tier.IMPORTANT) add(CustomAccessibilityAction("Show in drawer") { vm.markImportant(e); true })
            add(CustomAccessibilityAction("Done") { vm.dismiss(e); true })
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = canRight,
        enableDismissFromEndToStart = !cleared,
        backgroundContent = {
            val right = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when { !right -> "✓ Done"; cleared -> "↩️ Restore"; else -> "⭐ Show in drawer" },
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) { EntryCard(vm, e, tier, cleared, clockTime, actions) }
}

@Composable
private fun EntryCard(vm: MainViewModel, e: Entry, tier: Tier, cleared: Boolean, clockTime: Boolean, actions: List<CustomAccessibilityAction>) {
    var expanded by remember { mutableStateOf(false) }
    // TalkBack lands on the card, so the swipe actions live here too.
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }.semantics { customActions = actions }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.packageName, 20.dp)
                Text(e.appLabel, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                // By day the headers above already give the date and group, so the card shows the clock time.
                Text(
                    if (clockTime) DateUtils.formatDateTime(LocalContext.current, e.postedAt, DateUtils.FORMAT_SHOW_TIME)
                    else DateUtils.getRelativeTimeSpanString(e.postedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (e.title.isNotBlank() && e.title != e.appLabel) Text(e.title, fontWeight = FontWeight.SemiBold, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 12 else 2, overflow = TextOverflow.Ellipsis)
            if (cleared) Text("Was in ${tier.emoji} ${tier.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (!e.applied) Text("Preview: still in the drawer, nothing moved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                Text("Why: ${e.reason.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!cleared && e.category == Category.PERSONAL.id) ChatChoice(vm, e)
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.open(e) }) { Text("Open") }
                    if (cleared) {
                        TextButton(onClick = { vm.restore(listOf(e)) }) { Text("Restore") }
                    } else {
                        if (tier != Tier.IMPORTANT) TextButton(onClick = { vm.markImportant(e) }) { Text("Show in drawer") }
                        if (tier != Tier.JUNK) TextButton(onClick = { vm.markJunk(e) }) { Text("Junk") }
                        TextButton(onClick = { vm.dismiss(e) }) { Text("Done") }
                    }
                }
            }
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
