package io.github.notisift.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import io.github.notisift.data.Policy
import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import io.github.notisift.scan.Suggestion

/** [active] holds apps that posted recently, so the default list is the handful that actually notify. */
private enum class AppFilter(val label: String, val matches: (Policy, Set<String>) -> Boolean) {
    ACTIVE("Active", { p, active -> p.packageName in active }),
    PENDING("Suggestions", { p, _ -> p.mode.isBlank() && p.suggestedMode.isNotBlank() && p.suggestedMode != AppMode.UNMANAGED.id }),
    MANAGED("Chosen", { p, _ -> AppMode.fromId(p.mode)?.let { m -> m != AppMode.UNMANAGED } == true }),
    ALL("All", { _, _ -> true }),
}

@Composable
fun AppsScreen(vm: MainViewModel, modifier: Modifier) {
    val scan by vm.scan.collectAsState()
    val policies by vm.policies.collectAsState()
    val active by vm.activeApps.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ACTIVE) }
    var editing by remember { mutableStateOf<Policy?>(null) }
    val shown = remember(policies, active, query, filter) {
        policies.filter { filter.matches(it, active) }.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    val pending = policies.filter { AppFilter.PENDING.matches(it, active) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 340.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose per app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "⭐ Important: always on top · 🤖 Smart: the AI sorts each one · 🕓 Later: waits in NotiSift · 🗑️ Junk: hidden. " +
                            "New apps start on Smart. Scan to get a suggestion for every app; nothing changes until you use it. " +
                            (vm.cloudProvider()?.let { "With AI on, the scan sends app names and a few notification titles to $it, never chats, codes, money or calls." }
                                ?: "The scan runs on this phone."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.scanDevice() }, enabled = !scan.running) {
                            Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (scan.running) "Scanning…" else "Suggest for all apps")
                        }
                        if (pending.isNotEmpty() && !scan.running) {
                            FilledTonalButton(onClick = { vm.applySuggestions(pending.map(::toSuggestion)) }) { Text("Use all ${pending.size}") }
                        }
                    }
                    if (scan.running && scan.total > 0) LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
                    scan.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppFilter.entries) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ${policies.count { f.matches(it, active) }}") })
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when {
                        policies.isEmpty() -> "Run a scan to list your apps."
                        filter == AppFilter.ACTIVE && query.isBlank() -> "No app has notified in the last 2 weeks. See All for every app."
                        else -> "No apps match."
                    },
                    Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.packageName }) { p -> AppCard(vm, p, onEdit = { editing = p }) }
    }

    editing?.let { p -> InstructionsDialog(p, onDismiss = { editing = null }, onSave = { vm.setInstructions(p.packageName, p.label, it); editing = null }) }
}

private fun toSuggestion(p: Policy) = Suggestion(
    p.packageName, p.label, AppMode.fromId(p.suggestedMode) ?: AppMode.AUTO, Category.fromId(p.suggestedCategory), p.suggestionReason, true,
)

/** The four choices, in the same words as the rest of the app. */
private enum class AppChoice(val label: String, val mode: AppMode, val explain: String) {
    IMPORTANT("⭐ Important", AppMode.ALWAYS_KEEP, "Always on top of your drawer."),
    SMART("🤖 Smart", AppMode.AUTO, "The AI sorts each one: a delivery update goes on top, a sale waits in Later."),
    LATER("🕓 Later", AppMode.BUNDLE, "Always waits in NotiSift. Nothing on top."),
    JUNK("🗑️ Junk", AppMode.BLOCK, "Always hidden. Still findable in Junk."),
    ;

    companion object {
        fun of(mode: AppMode?): AppChoice? = when (mode) {
            null, AppMode.AUTO -> SMART
            AppMode.ALWAYS_KEEP -> IMPORTANT
            AppMode.BUNDLE -> LATER
            AppMode.SILENCE, AppMode.BLOCK -> JUNK
            AppMode.UNMANAGED -> null
        }
    }
}

@Composable
private fun AppCard(vm: MainViewModel, p: Policy, onEdit: () -> Unit) {
    val context = LocalContext.current
    val mode = AppMode.fromId(p.mode)
    val choice = AppChoice.of(mode)
    val suggested = AppChoice.of(AppMode.fromId(p.suggestedMode))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(p.packageName)
                Text(p.label, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppChoice.entries.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = choice == c,
                        onClick = { vm.setMode(p.packageName, p.label, c.mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, AppChoice.entries.size),
                        icon = {},
                    ) { Text(c.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
            }
            Text(
                choice?.explain ?: "Left alone: NotiSift doesn't touch this app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suggested != null && suggested != choice) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Suggested: ${suggested.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    TextButton(onClick = { vm.setMode(p.packageName, p.label, suggested.mode) }) { Text("Use it") }
                }
            }
            Row {
                TextButton(onClick = onEdit) { Text(if (p.instructions.isBlank()) "Tell the AI about this app" else "Edit what the AI knows") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, p.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Android settings") }
            }
        }
    }
}

@Composable
private fun InstructionsDialog(p: Policy, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(p.instructions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should the AI know about ${p.label}?") },
        text = {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(), minLines = 4,
                placeholder = { Text("e.g. Put order and delivery updates on top. Anything about deals or recommendations is junk.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
