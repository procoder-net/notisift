package io.github.notisift.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One help topic: what it is, how it helps, and optionally a button that takes you there. */
private data class Topic(val title: String, val lines: List<String>, val action: Pair<String, Tab>? = null)

private fun topics(cloudProvider: String?) = listOf(
    Topic(
        "📥 What NotiSift does",
        listOf(
            "It sorts your notification drawer: what matters stays on top, the rest waits quietly until you want it.",
            "⭐ Important: copies of the originals at the top of the drawer. Tapping one opens the exact chat or item.",
            "🕓 Later: one silent row per group (News, Promotions…). Open NotiSift to read them.",
            "🗑️ Junk: never shown in the drawer, kept in the app in case you need it.",
            "It sorts after a notification arrives, so an app's own sound can still play first. Make noisy apps' channels silent in Apps → Android settings.",
        ),
    ),
    Topic(
        "🚀 Getting started",
        listOf(
            "1. Allow access: Android asks you to let NotiSift read notifications. Nothing leaves the phone unless you add an AI.",
            "2. Set up my apps: every app gets a starting choice. You can change any of them later.",
            "3. Preview: NotiSift shows what it would do without moving anything. When it looks right, tap Start sorting.",
        ),
    ),
    Topic(
        "🤖 AI is optional",
        listOf(
            if (cloudProvider == null) "Right now everything is decided on this phone with built-in rules: codes, people, money and calls stay on top; promotions, news and updates go to Later; repeats go to Junk."
            else "Right now decisions use $cloudProvider. Without it, NotiSift falls back to its built-in rules on this phone.",
            "Adding an AI makes sorting smarter for apps set to Smart: it reads each notification and picks Important, Later or Junk, and a group.",
            "Paste one key in Settings → Advanced (Perplexity, OpenAI, Anthropic, Gemini, OpenRouter, Groq, or your own Ollama / LM Studio). NotiSift works out the provider from the key. Save & test checks it.",
            "Only the title and text of notifications from Smart apps are sent. Codes, chats, calls, alarms and ongoing notifications are always decided on the phone.",
        ),
        "Open Settings" to Tab.SETTINGS,
    ),
    Topic(
        "📱 Choosing per app",
        listOf(
            "⭐ Important: always on top. Best for apps where every notification matters (authenticators).",
            "🤖 Smart: NotiSift decides each one. A delivery update goes on top, a sale from the same app waits in Later. Most apps belong here.",
            "🕓 Later: always waits in NotiSift. Good for news and shopping apps.",
            "🗑️ Junk: always hidden. Good for games and apps that only advertise.",
            "Active lists apps that notified in the last 2 weeks. Suggestions are the scan's picks; Use all applies them.",
        ),
        "Open Apps" to Tab.APPS,
    ),
    Topic(
        "🏷️ Groups",
        listOf(
            "Every notification gets a group: People & messages, Codes, Money, Orders, News, Promotions and more.",
            "News, social, video and game apps keep their app's group, so a headline about a flight stays in News.",
            "Settings → Groups lets you send a whole group somewhere: for example News always to Later. People, codes, money and calls can never be hidden this way.",
        ),
        "Open Settings" to Tab.SETTINGS,
    ),
    Topic(
        "👆 Gestures and Undo",
        listOf(
            "In the app, swipe → to put something back on top of the drawer, swipe ← when you're done with it.",
            "Show in drawer and Junk also teach NotiSift: similar notifications go the same way next time. The Undo button takes back both.",
            "Clear empties a group; Clear all empties the tab. Both can be undone.",
            "In the drawer, swipe any row away as usual. Tap the count on a stack to expand it (Samsung).",
            "Anything cleared, in the app or the drawer, waits in ↩️ Cleared on Home. Swipe → or tap Restore to put it back.",
            "🔍 on Home searches every notification by app, sender or text.",
        ),
    ),
    Topic(
        "⏯️ Preview, Sorting and Off",
        listOf(
            "Preview: NotiSift decides and lists what it would do, but leaves your drawer alone. Safe for trying it out.",
            "Sorting: notifications are moved. The status card shows how many are on top and how many moved.",
            "Off: NotiSift reads nothing, logs nothing and sends nothing.",
            "Add the NotiSift tile to Quick Settings to switch between Sorting and Preview from the shade.",
        ),
    ),
    Topic(
        "📌 In your drawer",
        listOf(
            "⭐ Important · N is NotiSift's stack: newest first, at most 20 rows. Older ones stay in the app.",
            "N waiting in NotiSift holds one row per Later group. Tap a group to open it in the app, or Clear to mark it read.",
            "Busy group chats? Open a chat message on Home and pick ⭐ Always on top, @ Only when mentioned, or 🔕 Mute. Home also suggests this for groups that flood the top. Add the names people @mention you by in Settings.",
            "Prefer dates? Tap By day on Home: the drawer then shows 🕓 Today, 📅 Yesterday and earlier days (a week back) instead of groups, so older items don't drop out.",
            "📌 Running in background holds always-on notifications (car apps, automations). Show brings them back.",
            "After NotiSift updates, Android removes its rows. They're rebuilt, but those rebuilt rows open the app rather than the exact chat. New messages link to the chat again.",
        ),
    ),
    Topic(
        "🔋 Keep it running",
        listOf(
            "Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add NotiSift.",
            "Xiaomi: App info → Battery saver → No restrictions, and turn on Autostart. OnePlus / Oppo: allow background activity.",
            "If Home says access is needed after an update, turn NotiSift off and on in Notification access.",
        ),
    ),
    Topic(
        "🔒 Privacy",
        listOf(
            "Everything is stored on this phone and excluded from backups.",
            "History is kept for 14 days by default (Settings), then deleted. Settings → Delete notification history removes it now.",
            "Notification text is treated as untrusted: NotiSift never follows instructions inside it.",
        ),
    ),
)

/**
 * Help: what each part of NotiSift is, how it helps, and where to set it up. A sheet over whatever screen is
 * open, so reading it never loses your place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(vm: MainViewModel, onOpen: (Tab) -> Unit, onTour: () -> Unit, onDismiss: () -> Unit) {
    // Open topics, first one open; kept across rotation and folding.
    var open by rememberSaveable { mutableStateOf(listOf(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("How NotiSift works", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Tap a topic to read it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onTour) { Text("Take the tour again") }
                }
            }
            items(topics(vm.cloudProvider()).withIndex().toList(), key = { it.index }) { (i, t) ->
                val expanded = i in open
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") {
                            open = if (expanded) open - i else open + i
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        t.action?.let { (label, tab) -> FilledTonalButton(onClick = { onOpen(tab) }) { Text(label) } }
                    }
                }
            }
        }
    }
}
