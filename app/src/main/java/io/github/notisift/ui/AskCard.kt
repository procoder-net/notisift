package io.github.notisift.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.notisift.data.Entry
import io.github.notisift.engine.Assistant
import io.github.notisift.engine.AssistantReply
import io.github.notisift.engine.Proposal

/**
 * "Talk to NotiSift": say a rule in your own words ("Mute Instagram") and confirm the cards it becomes, or ask about
 * your notifications ("Did my package ship?"). Nothing changes until the user taps Add.
 */
@Composable
fun AskCard(vm: MainViewModel) {
    val state by vm.ask.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val thinking = state == MainViewModel.AskState.Thinking
    val send = { if (text.isNotBlank()) vm.ask(text.trim()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(Assistant.MAX_MESSAGE) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !thinking,
            label = { Text("Tell NotiSift or ask") },
            placeholder = { Text("Mute Instagram · Did my package ship?", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                if (thinking) CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Thinking"; liveRegion = LiveRegionMode.Polite }, strokeWidth = 2.dp)
                else IconButton(onClick = send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            },
        )
        (state as? MainViewModel.AskState.Done)?.let { done ->
            ReplyCard(done, onApply = { vm.applyProposals(it); text = "" }, onClose = { vm.clearAsk() })
        }
    }
}

@Composable
private fun ReplyCard(done: MainViewModel.AskState.Done, onApply: (List<Proposal>) -> Unit, onClose: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val reply = done.reply) {
                is AssistantReply.Rules -> {
                    Text(if (reply.proposals.size == 1) "Add this rule?" else "Add these rules?", style = MaterialTheme.typography.titleSmall)
                    reply.proposals.forEach { Text("• ${it.description}", style = MaterialTheme.typography.bodyMedium) }
                    if (reply.skipped.isNotEmpty()) Note("Couldn't find ${reply.skipped.joinToString(", ") { "\"$it\"" }}, so it's left out.")
                    Note("Messages from people, codes and calls are always kept on top.")
                }
                is AssistantReply.Answer -> {
                    Text(reply.text, style = MaterialTheme.typography.bodyLarge)
                    reply.sources.take(MAX_SOURCES_SHOWN).forEach { SourceRow(it) }
                }
                is AssistantReply.Unclear -> Text(reply.message, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note("By ${done.by}")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(if (done.reply is AssistantReply.Rules) "Cancel" else "Close") }
                (done.reply as? AssistantReply.Rules)?.let { rules -> Button(onClick = { onApply(rules.proposals) }) { Text("Add") } }
            }
        }
    }
}

@Composable
private fun SourceRow(e: Entry) {
    Text(
        "${e.appLabel} · ${e.title}" + if (e.text.isNotBlank()) " — ${e.text}" else "",
        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
}

/** "Moved up 2": what the on-phone AI took out of Later because it looked like it needs the user today. */
@Composable
fun MovedUpCard(moved: List<Entry>, onUndo: () -> Unit, onOk: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Moved up ${moved.size} that may need you today", style = MaterialTheme.typography.titleSmall)
            moved.forEach { Text("• ${it.appLabel}: ${it.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onUndo) { Text("Put back in Later") }
                TextButton(onClick = onOk) { Text("OK") }
            }
        }
    }
}

private const val MAX_SOURCES_SHOWN = 5
