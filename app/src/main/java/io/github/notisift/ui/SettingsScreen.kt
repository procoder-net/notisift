package io.github.notisift.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextOverflow
import io.github.notisift.engine.ChatRules
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.notisift.BuildConfig
import io.github.notisift.data.AppSettings
import io.github.notisift.engine.Action
import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import io.github.notisift.engine.CategoryChoices
import io.github.notisift.engine.GroupChoice
import io.github.notisift.engine.DecisionEngine
import io.github.notisift.engine.KeywordRule
import io.github.notisift.engine.Tier
import io.github.notisift.llm.ProviderDetector
import io.github.notisift.llm.ProviderKind

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val saved by vm.settings.collectAsState()
    val s = saved ?: return
    var advanced by remember { mutableStateOf(vm.takeAiSetupRequest()) }
    val suggestions by vm.ruleSuggestions.collectAsState()
    val groups by vm.groups.collectAsState()
    val policies by vm.policies.collectAsState()
    val fixed = policies.filter { AppMode.fromId(it.mode).let { m -> m != null && m != AppMode.AUTO && m != AppMode.UNMANAGED } }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Your choices") {
            Text(
                "Your choices always win over the AI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rules = KeywordRule.parse(s.keywordRules)
            rules.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Says \"${r.keyword}\"", Modifier.weight(1f))
                    Text("→ ${Tier.of(r.action).let { "${it.emoji} ${choiceLabel(it)}" }}", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.updateSettings { it.copy(keywordRules = KeywordRule.format(rules.filterIndexed { j, _ -> j != i })) } }) { Text("Remove") }
                }
            }
            AddChoice { rule -> vm.updateSettings { it.copy(keywordRules = KeywordRule.format(rules.filter { r -> r.keyword != rule.keyword } + rule)) } }
            ChatChoices(vm, s)
        }

        Section("Groups") {
            Text(
                "Choose what happens to each kind of notification. 🤖 lets the AI decide each one. Groups for things you don't use are hidden.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val choices = CategoryChoices.parse(s.categoryChoices)
            groups.forEach { (c, count) -> GroupRow(c, count, choices[c] ?: GroupChoice.AI) { vm.setGroupChoice(c, it) } }
        }

        if (suggestions.isNotEmpty()) Section("Make it a rule") {
            Text(
                "The AI keeps deciding these the same way. A fixed rule decides instantly, on the phone, without the AI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            suggestions.take(8).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.appLabel, fontWeight = FontWeight.SemiBold)
                        Text("${r.tier.emoji} ${choiceLabel(r.tier)} · ${r.times} times in a row", style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { vm.acceptRule(r) }) { Text("Make rule") }
                }
            }
        }

        if (fixed.isNotEmpty()) Section("App rules") {
            fixed.forEach { p ->
                val mode = AppMode.fromId(p.mode) ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, Modifier.weight(1f))
                    Text(appRuleLabel(mode), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.setMode(p.packageName, p.label, AppMode.AUTO) }) { Text("Remove") }
                }
            }
        }

        Section("Move to a new phone") { BackupChoices(vm) }

        Section("Advanced") {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Show advanced settings") }
            if (advanced) AdvancedSettings(vm, s)
        }

        Text(
            "Everything stays on this phone and is excluded from backups. With a cloud AI, the title and text of notifications " +
                "from apps set to Smart are sent to it. Codes, calls, alarms and ongoing notifications are decided on the phone and never sent.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One group with its 🤖 / ⭐ / 🕓 / 🗑️ choice. Protected groups only offer 🤖 and ⭐. */
@Composable
private fun GroupRow(c: Category, count: Int, choice: GroupChoice, onPick: (GroupChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${c.emoji}  ${c.label}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(if (c.protected) "🔒 never hidden" else "$count in 2 weeks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val options = GroupChoice.entries.filter { CategoryChoices.allowed(c, it) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = choice == o, onClick = { onPick(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {},
                ) { Text(groupChoiceLabel(o), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
    }
}

private fun groupChoiceLabel(o: GroupChoice) = when (o) {
    GroupChoice.AI -> "🤖 AI"
    GroupChoice.IMPORTANT -> "⭐ Important"
    GroupChoice.LATER -> "🕓 Later"
    GroupChoice.JUNK -> "🗑️ Junk"
}

private fun appRuleLabel(mode: AppMode) = when (mode) {
    AppMode.ALWAYS_KEEP -> "${Tier.IMPORTANT.emoji} Important"
    AppMode.BUNDLE -> "${Tier.LATER.emoji} Later"
    AppMode.SILENCE, AppMode.BLOCK -> "${Tier.JUNK.emoji} Junk"
    AppMode.AUTO, AppMode.UNMANAGED -> mode.label
}

private fun choiceLabel(tier: Tier) = when (tier) {
    Tier.IMPORTANT -> "Important"
    Tier.LATER -> "Later"
    Tier.JUNK -> "Junk"
}

/** "When it says ___ → Important / Later / Junk", one tap to add. */
/** Names for @mentions, and the chats the user gave a choice to (set from a message card on Home). */
@Composable
private fun ChatChoices(vm: MainViewModel, s: AppSettings) {
    var names by remember(s.myNames) { mutableStateOf(s.myNames) }
    OutlinedTextField(
        names, { names = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Names people @mention you by") }, placeholder = { Text("e.g. Sam, Sam Lee") },
        supportingText = { Text("For chats set to \"Only when mentioned\". Separate names with commas.") },
        trailingIcon = { if (names != s.myNames) TextButton(onClick = { vm.updateSettings { it.copy(myNames = names) } }) { Text("Save") } },
    )
    val chats = ChatRules.rows(s.chatRules)
    if (chats.isEmpty()) {
        Text(
            "Tip: open a chat message on Home to keep it always on top, show it only when you're mentioned, or mute it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    chats.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💬 ${c.chat}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ ${c.mode.label}", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { vm.updateSettings { it.copy(chatRules = ChatRules.format(chats - c)) } }) { Text("Remove") }
        }
    }
}

@Composable
private fun AddChoice(onAdd: (KeywordRule) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("When a notification says…") }, placeholder = { Text("e.g. flash sale, your order, invoice") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Action.KEEP to Tier.IMPORTANT, Action.BUNDLE to Tier.LATER, Action.BLOCK to Tier.JUNK).forEach { (action, tier) ->
                FilledTonalButton(
                    onClick = { onAdd(KeywordRule(text.trim().lowercase(), action)); text = "" },
                    enabled = text.isNotBlank(),
                ) { Text("${tier.emoji} ${choiceLabel(tier)}") }
            }
        }
    }
}

@Composable
private fun AdvancedSettings(vm: MainViewModel, s: AppSettings) {
    val connection by vm.connection.collectAsState()
    var draft by remember { mutableStateOf(s) }
    LaunchedEffect(s) { draft = s }
    var showKey by remember { mutableStateOf(false) }
    val resolved = ProviderDetector.resolve(draft.provider, draft.apiKey, draft.baseUrl, draft.model, BuildConfig.ENV_API_KEY_NAME)

    SwitchRow("NotiSift replaces the drawer", "Important ones are re-shown in NotiSift's stack, so nothing else clutters the drawer", s.replaceShade) { on -> vm.updateSettings { it.copy(replaceShade = on) } }
    SwitchRow("Only important on top", "Anything the AI isn't sure about waits in Later", s.inboxMode) { on -> vm.updateSettings { it.copy(inboxMode = on) } }
    SwitchRow("Show waiting groups in the drawer", "Silent stacks: Promotions · 23, News · 12…", s.digest) { on -> vm.updateSettings { it.copy(digest = on) } }
    SwitchRow("Sort new apps automatically", "Apps you haven't set up are decided per notification", s.manageUnlistedApps) { on -> vm.updateSettings { it.copy(manageUnlistedApps = on) } }

    HorizontalDivider()
    Text("AI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    SwitchRow("Use AI", "Off = on-phone rules only", draft.aiEnabled) { draft = draft.copy(aiEnabled = it) }
    OutlinedTextField(draft.rules, { draft = draft.copy(rules = it) }, Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text("Tell the AI what matters to you") })
    ProviderPicker(draft.provider, resolved.kind) { id ->
        val kind = ProviderKind.fromId(id)
        draft = draft.copy(provider = id, baseUrl = if (kind != null) "" else draft.baseUrl, model = if (kind != null) "" else draft.model)
    }
    // No key yet: say how to get one right where it goes.
    if (draft.apiKey.isBlank() && resolved.kind.needsKey) AiKeySteps(vm) { key -> draft = draft.copy(apiKey = key, provider = "", baseUrl = "", model = "") }
    OutlinedTextField(
        draft.apiKey, { draft = draft.copy(apiKey = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
        supportingText = { if (BuildConfig.ENV_API_KEY.isNotBlank()) Text("Default comes from ${BuildConfig.ENV_API_KEY_NAME} in .env") },
    )
    OutlinedTextField(
        draft.baseUrl, { draft = draft.copy(baseUrl = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true,
        placeholder = { Text(resolved.kind.defaultBaseUrl.ifBlank { "https://your-server/v1" }) },
        supportingText = { Text("Plain http:// only works for LAN IPs (e.g. http://192.168.0.10:11434/v1)") },
    )
    ModelField(draft.model, resolved.kind.defaultModel, connection.models.ifEmpty { resolved.kind.suggestedModels }, onChange = { draft = draft.copy(model = it) }, onRefresh = { vm.fetchModels(draft) })
    Text("Using ${resolved.kind.label} · ${resolved.model.ifBlank { "auto-pick" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "Strictness: Junk only when the AI is ${(draft.threshold * 100).toInt()}%+ sure; on top when it's " +
            "${((draft.threshold - DecisionEngine.BUNDLE_MARGIN) * 100).toInt()}%+ sure it matters.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(draft.threshold.toFloat(), { draft = draft.copy(threshold = it.toDouble()) }, valueRange = 0.5f..0.95f, steps = 8)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            vm.updateSettings { it.copy(rules = draft.rules, threshold = draft.threshold, aiEnabled = draft.aiEnabled) }
            vm.testConnection(draft)
        }, enabled = !connection.testing) { Text("Save & test") }
        if (connection.testing) CircularProgressIndicator(Modifier.padding(4.dp))
    }
    connection.result?.let {
        Text(it, color = if (connection.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    HorizontalDivider()
    Text("History kept for ${s.retentionDays} days", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Everything NotiSift moved stays in the app this long, then is deleted. Rows in the drawer go after a day.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(s.retentionDays.toFloat(), { d -> vm.updateSettings { it.copy(retentionDays = d.toInt()) } }, valueRange = 1f..60f)
    OutlinedButton(onClick = { vm.forgetLearned() }) { Text("Forget what NotiSift learned") }
    OutlinedButton(onClick = { vm.deleteHistory() }) { Text("Delete notification history") }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ProviderPicker(selected: String, detected: ProviderKind, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(ProviderKind.fromId(selected)?.label ?: "Auto-detect (${detected.label})")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto-detect from key / URL") }, onClick = { open = false; onPick("") })
            ProviderKind.entries.forEach { k -> DropdownMenuItem(text = { Text(k.label) }, onClick = { open = false; onPick(k.id) }) }
        }
    }
}

@Composable
private fun ModelField(value: String, default: String, options: List<String>, onChange: (String) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true,
            placeholder = { Text(default.ifBlank { "auto-pick a small model" }) },
            trailingIcon = { TextButton(onClick = { open = true }) { Text("List") } },
        )
        Box {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("↻ Fetch from provider") }, onClick = { open = false; onRefresh() })
                options.take(60).forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { open = false; onChange(m) }) }
            }
        }
    }
}

/** Export and import of what NotiSift learned, through Android's file picker (Drive, Downloads, USB…). */
@Composable
private fun BackupChoices(vm: MainViewModel) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportBackup) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBackup) }
    Text(
        "Save your app choices, corrections, keyword and chat rules, groups and settings to a file, then import it on your new phone. " +
            "Your API key and notification history are never included.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { save.launch("notisift-backup-${java.time.LocalDate.now()}.json") }) { Text("Export") }
        OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
    }
}
