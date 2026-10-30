package io.github.notisift.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.notisift.engine.Tier
import kotlinx.coroutines.delay

/** First-run setup: why → access (with the Android warning explained first) → apps → your own drawer, sorted. */
private enum class Step { WELCOME, ACCESS, APPS, PREVIEW }

/**
 * Full-screen first run that replaces the tour-over-an-empty-Home. Every step does one thing, and it ends on the
 * user's real drawer sorted ("12 now → 3 stay on top"), with Start sorting one tap away. Skippable at every step;
 * Home's status card picks up wherever the user left off.
 */
@Composable
fun Onboarding(vm: MainViewModel, onDone: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    // Access already granted: the access step would just bounce forward again, so Back skips it.
    val back = Step.entries.getOrNull(step.ordinal - 1)?.let { if (it == Step.ACCESS && vm.hasAccess()) Step.WELCOME else it }
    BackHandler(enabled = back != null) { back?.let { step = it } }
    val next: () -> Unit = {
        step = when (step) {
            Step.WELCOME -> if (vm.hasAccess()) Step.APPS else Step.ACCESS
            Step.ACCESS -> Step.APPS
            Step.APPS -> Step.PREVIEW
            Step.PREVIEW -> Step.PREVIEW
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepDots(step)
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                label = "onboarding",
            ) { s ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                        when (s) {
                            Step.WELCOME -> WelcomeStep(onNext = next, onSkip = onDone)
                            Step.ACCESS -> AccessStep(vm, onGranted = next, onSkip = onDone)
                            Step.APPS -> AppsStep(vm, onNext = next)
                            Step.PREVIEW -> PreviewStep(vm, onDone = onDone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Step) {
    Row(
        Modifier.padding(top = 16.dp, bottom = 8.dp).clearAndSetSemantics { contentDescription = "Step ${step.ordinal + 1} of ${Step.entries.size}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Step.entries.forEach { s ->
            val active = s.ordinal <= step.ordinal
            Box(
                Modifier.height(6.dp).width(if (s == step) 28.dp else 12.dp).background(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun Title(text: String, sub: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A step's content scrolls; its buttons stay pinned at the bottom, so the next action is always on screen, even
 * with large text or a long preview.
 */
@Composable
private fun StepLayout(actions: @Composable () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { body() }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)) { actions() }
        }
    }
}

/** The primary action at the bottom of a step, full width so it is the obvious thing to tap. */
@Composable
private fun Actions(primary: String, onPrimary: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {}, enabled: Boolean = true) {
    Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(primary, style = MaterialTheme.typography.titleMedium) }
    if (secondary != null) TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(secondary) }
}

// ── 1. Welcome ──────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep(onNext: () -> Unit, onSkip: () -> Unit) {
    StepLayout(actions = {
        Actions("Get started", onNext, "Skip setup", onSkip)
    }) {
        BeforeAfter()
        Title("Your drawer, sorted", "NotiSift keeps what matters on top and quietly sets the rest aside. Nothing is deleted.")
    }
}

private val messy = listOf(
    "🛍️" to "48 hours only: 40% off everything", "💬" to "Alex: are we still on for 7?", "📰" to "Your morning briefing",
    "🎮" to "Your energy is full!", "🔐" to "Your code is 48••••", "👥" to "People you may know",
)

/** A looping before/after of a drawer: six mixed notifications become one short stack on top and two quiet rows. */
@Composable
private fun BeforeAfter() {
    var sorted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (sorted) 3200 else 2200)
            sorted = !sorted
        }
    }
    Card(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Example: six mixed notifications become two important ones on top, with news and games waiting and promotions in junk"
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (sorted) "After NotiSift" else "Your drawer today",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            AnimatedContent(sorted, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(300)) }, label = "drawer") { after ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!after) messy.forEach { (emoji, text) -> MiniRow(emoji, text) }
                    else {
                        MiniRow("⭐", "Important · 2", strong = true, tint = MaterialTheme.colorScheme.primaryContainer)
                        MiniRow("💬", "Alex: are we still on for 7?", indent = true)
                        MiniRow("🔐", "Your code is 48••••", indent = true)
                        MiniRow("🕓", "Later · News 1 · Games 1", tint = MaterialTheme.colorScheme.secondaryContainer)
                        MiniRow("🗑️", "Junk · 2 kept out of sight", tint = MaterialTheme.colorScheme.surfaceVariant)
                        // Same number of rows as "before", so the text below doesn't jump on every loop.
                        MiniRow("✨", "4 fewer interruptions", tint = MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniRow(emoji: String, text: String, strong: Boolean = false, indent: Boolean = false, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 20.dp else 0.dp).background(tint, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji)
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── 2. Access ───────────────────────────────────────────────────────────────

@Composable
private fun AccessStep(vm: MainViewModel, onGranted: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    // Coming back from Android's settings with access on moves straight to the next step.
    LifecycleResumeEffect(Unit) {
        if (vm.hasAccess()) onGranted()
        onPauseOrDispose { }
    }
    StepLayout(actions = {
        Actions(
            "Open Android settings",
            { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            "Not now", onSkip,
        )
    }) {
        Title("Let NotiSift see your notifications", "It needs this to sort them. Android will show a strong warning next; here's what NotiSift actually does.")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Promise(Icons.Outlined.Lock, "Stays on this phone", "Sorting runs on the phone. An AI is optional and only used if you add one.")
                Promise(Icons.Outlined.TouchApp, "Never replies or taps for you", "Android's warning covers replying and buttons. NotiSift only reads and tidies.")
                Promise(Icons.Outlined.Restore, "Nothing is deleted", "Everything it moves waits in the app, and any choice can be undone.")
            }
        }
        Text(
            "On the next screen: tap NotiSift, turn on Allow notification access, then Allow.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

// ── 3. Apps ─────────────────────────────────────────────────────────────────

@Composable
private fun AppsStep(vm: MainViewModel, onNext: () -> Unit) {
    val scan by vm.scan.collectAsState()
    val policies by vm.policies.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    // Starts by itself: there is nothing to decide here, only to wait a few seconds.
    LaunchedEffect(Unit) {
        if (!started && policies.isEmpty()) {
            started = true
            vm.setupApps()
        }
    }
    val done = !scan.running && (scan.suggestions.isNotEmpty() || policies.isNotEmpty())
    StepLayout(actions = {
        Actions("Continue", onNext, enabled = done)
    }) {
        Title("Setting up your apps", "NotiSift gives every app a starting choice. Chats, codes and money always stay on top.")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(scan.message ?: "Looking at your apps…", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    LinearProgressIndicator(progress = { if (scan.total > 0) scan.done.toFloat() / scan.total else 0f }, modifier = Modifier.fillMaxWidth())
                } else {
                    val byTier = scan.suggestions.groupBy { tierOf(it.mode) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "${scan.suggestions.size.takeIf { it > 0 } ?: policies.size} apps ready",
                            Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (byTier.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("⭐ On top" to byTier[Tier.IMPORTANT], "🤖 Smart" to byTier[null], "🕓 Later" to byTier[Tier.LATER]).forEach { (label, list) ->
                            Stat(label, list?.size ?: 0, Modifier.weight(1f))
                        }
                    }
                    Text("Change any of them later in Apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun tierOf(mode: io.github.notisift.engine.AppMode): Tier? = when (mode) {
    io.github.notisift.engine.AppMode.ALWAYS_KEEP -> Tier.IMPORTANT
    io.github.notisift.engine.AppMode.BUNDLE -> Tier.LATER
    io.github.notisift.engine.AppMode.SILENCE, io.github.notisift.engine.AppMode.BLOCK -> Tier.LATER
    else -> null
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier, container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface) {
    val shown by animateIntAsState(value, tween(900), label = "stat")
    Column(
        modifier.background(container, RoundedCornerShape(16.dp)).padding(vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = "$label: $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$shown", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ── 4. Your drawer, sorted ──────────────────────────────────────────────────

@Composable
private fun PreviewStep(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val preview by vm.preview.collectAsState()
    var tries by remember { mutableIntStateOf(0) }
    // Android may take a moment to connect NotiSift after access is granted; ask again briefly until it does.
    LaunchedEffect(tries) {
        vm.loadPreview().join()
        if (vm.preview.value?.rows.isNullOrEmpty() && tries < 3) {
            delay(1500)
            tries++
        }
    }
    val start: () -> Unit = { vm.setActive(true); onDone() }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val turnOn: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else start()
    }
    val p = preview
    val rows = p?.rows.orEmpty()
    StepLayout(actions = {
        Actions("Start sorting", turnOn, "Just watch for now", onDone, enabled = p != null)
        OutlinedHint()
    }) {
        if (p == null || rows.isEmpty()) {
            Title(
                if (p == null) "Looking at your drawer…" else "You're all set",
                if (p == null) "Seeing what NotiSift would do with what's there now."
                else "Your drawer is clear right now. New notifications will be sorted the moment they arrive.",
            )
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Title(
                "${rows.size} in your drawer → ${p.count(Tier.IMPORTANT)} on top",
                "This is your drawer right now, sorted. Nothing has moved yet.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tier.entries.forEach { t -> Stat("${t.emoji} ${t.label}", p.count(t), Modifier.weight(1f), tierContainer(t)) }
            }
            // A few from each tier: what gets set aside is the point, not just what stays.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Tier.entries.forEach { t ->
                        val list = rows.filter { it.tier == t }
                        if (list.isEmpty()) return@forEach
                        Text(
                            "${t.emoji} ${tierHeading(t)}", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                        list.take(PER_TIER).forEach { r -> PreviewLine(r) }
                        if (list.size > PER_TIER) Text(
                            "+${list.size - PER_TIER} more", Modifier.padding(start = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Reassurance under the buttons: the decision is cheap to reverse. */
@Composable
private fun OutlinedHint() {
    Text(
        "You can pause or undo anything later. \"Just watch\" keeps your drawer as it is and shows what NotiSift would do.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val PER_TIER = 3

private fun tierHeading(t: Tier) = when (t) {
    Tier.IMPORTANT -> "Stays on top"
    Tier.LATER -> "Waits quietly in Later"
    Tier.JUNK -> "Out of sight in Junk"
}

@Composable
private fun tierContainer(t: Tier) = when (t) {
    Tier.IMPORTANT -> MaterialTheme.colorScheme.primaryContainer
    Tier.LATER -> MaterialTheme.colorScheme.secondaryContainer
    Tier.JUNK -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun PreviewLine(r: PreviewRow) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        Text(r.app, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
