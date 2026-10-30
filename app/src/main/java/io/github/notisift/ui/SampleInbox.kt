package io.github.notisift.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.notisift.engine.Tier

/** Made-up examples: what a new user's notifications will turn into, before any real ones have arrived. */
private val samples = listOf(
    Tier.IMPORTANT to listOf("Alex: are we still on for 7?", "Your code is 48•••", "Package arriving today by 8 pm"),
    Tier.LATER to listOf("Your weekly news roundup", "Ride receipt: \$14.20", "Front door camera: motion"),
    Tier.JUNK to listOf("48 hours only: 40% off", "People you may know", "Rate your last order"),
)

/** Shown on Home until the first real notification is sorted, so the first minute isn't an empty screen. */
@Composable
fun SampleInbox() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What sorting looks like", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "An example, not your notifications. Yours show up here as they arrive.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            samples.forEach { (tier, lines) ->
                Text("${tier.emoji} ${tier.label}: ${tierHint(tier)}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                lines.forEach { line ->
                    Row(Modifier.padding(start = 8.dp)) {
                        Text("· $line", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun tierHint(tier: Tier) = when (tier) {
    Tier.IMPORTANT -> "on top of your drawer"
    Tier.LATER -> "one quiet row per group"
    Tier.JUNK -> "hidden, kept here"
}
