package io.github.notisift.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.ClipboardManager
import io.github.notisift.llm.ProviderDetector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Where to get a key for each hosted provider. Free tiers first: they're enough for one phone. */
private val keyPages = listOf(
    "Gemini (free tier)" to "https://aistudio.google.com/apikey",
    "Groq (free tier)" to "https://console.groq.com/keys",
    "Perplexity" to "https://www.perplexity.ai/account/api/keys",
    "OpenAI" to "https://platform.openai.com/api-keys",
    "Anthropic" to "https://console.anthropic.com/settings/keys",
    "OpenRouter" to "https://openrouter.ai/keys",
)

/**
 * How to add a model: one-tap OpenRouter sign-in (no copying), or a provider's key page, after which a key left on
 * the clipboard is offered back. The clipboard is only read after the user went to fetch a key here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiKeySteps(vm: MainViewModel, onUseKey: (String) -> Unit) {
    val links = LocalUriHandler.current
    val context = LocalContext.current
    val connecting by vm.connecting.collectAsState()
    var fetching by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(fetching) {
        if (fetching) {
            val text = runCatching {
                context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            }.getOrNull()
            copied = text?.takeIf { it.length in 20..300 && ' ' !in it && ProviderDetector.fromKey(it) != null }
        }
        onPauseOrDispose { }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Fastest: sign in to OpenRouter and NotiSift gets its own key, no copying. One account reaches Claude, GPT, Gemini, Perplexity Sonar and more.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { vm.connectOpenRouter() }, enabled = !connecting) { Text(if (connecting) "Waiting for OpenRouter…" else "Connect OpenRouter") }
        Text("Or get a key from one provider (opens your browser), copy it and come back:", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            keyPages.forEach { (name, url) -> AssistChip(onClick = { fetching = true; runCatching { links.openUri(url) } }, label = { Text(name) }) }
        }
        copied?.let { key ->
            Button(onClick = { onUseKey(key); copied = null; fetching = false }) { Text("Use the key you copied (${key.take(7)}…)") }
        }
        Text("Paste it in the API key box. NotiSift works out which provider it is.", style = MaterialTheme.typography.bodyMedium)
        Text("Then tap Save & test. A green line means it's working.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Only notifications from apps set to 🤖 Smart are sent, as title and text. Codes, chats, calls and money never leave the phone. " +
                "Running your own Ollama or LM Studio? Pick it as the provider instead; no key needed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Home's one-time offer to add a model, shown once the apps are set up and no model is configured. */
@Composable
fun AiKeyCard(onAddKey: () -> Unit, onNotNow: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🤖 Make sorting smarter (optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "NotiSift is sorting with built-in rules on this phone. Add an AI decision model and it reads each notification from " +
                    "your Smart apps, so a bank alert and a bank promo land in different places. Free keys from Gemini or Groq are enough.",
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddKey) { Text("Add a key") }
                TextButton(onClick = onNotNow) { Text("Not now") }
            }
        }
    }
}
