package io.github.notisift.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.notisift.R
import io.github.notisift.engine.Tier

/**
 * Compact Home's status while all is well: one line instead of a card, so the notifications start on the first
 * screen. Anything that needs the user (access, setup, preview, a hidden drawer) still gets the full card.
 */
@Composable
fun CompactStatus(onTop: Int, movedToday: Int, where: String, onClean: () -> Unit, more: List<Pair<String, () -> Unit>>) {
    val summary = stringResource(R.string.home_status_summary, onTop, movedToday)
    val spoken = stringResource(R.string.home_status_summary_spoken, onTop, movedToday)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null)
            val title = stringResource(R.string.home_status_sorting)
            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $spoken. $where" },
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("$summary · $where", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClean) { Icon(Icons.Outlined.Refresh, stringResource(R.string.home_clean_again)) }
            MoreMenu(more)
        }
    }
}

/** Compact Home's offer to add an AI model: one line with Add and ✕, instead of a paragraph above the list. */
@Composable
fun AiKeyBanner(onAddKey: () -> Unit, onNotNow: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_ai_banner), Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAddKey) { Text(stringResource(R.string.home_ai_add)) }
            IconButton(onClick = onNotNow) { Icon(Icons.Outlined.Close, stringResource(R.string.home_ai_not_now)) }
        }
    }
}

/**
 * The tier chips. On compact Home they're pinned while the list scrolls, on the screen's own background so rows
 * pass cleanly underneath.
 */
@Composable
fun TierBar(selected: Tier?, cleared: Boolean, counts: Map<Tier, Int>, clearedCount: Int, pinned: Boolean, onTier: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = if (pinned) MaterialTheme.colorScheme.background else Color.Transparent) {
        LazyRow(
            Modifier.padding(vertical = if (pinned) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            items(Tier.entries) { t ->
                FilterChip(selected = selected == t, onClick = { onTier(t.id) }, label = { Text("${t.emoji} ${t.label} ${counts[t] ?: 0}") })
            }
            item { FilterChip(selected = cleared, onClick = { onTier(HomeView.CLEARED) }, label = { Text("↩️ Cleared $clearedCount") }) }
        }
    }
}
