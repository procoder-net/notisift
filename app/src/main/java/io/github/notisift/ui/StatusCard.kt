package io.github.notisift.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The one thing the user needs to know: is my shade being cleaned, and what do I do if not. */
@Composable
fun StatusCard(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val connected by vm.listenerConnected.collectAsState()
    val removed by vm.todayRemoved.collectAsState()
    val inShade by vm.shadeCount.collectAsState()
    val policies by vm.policies.collectAsState()
    val scan by vm.scan.collectAsState()
    var access by remember { mutableStateOf(vm.hasAccess()) }
    var healthy by remember { mutableStateOf(vm.drawerHealthy()) }
    var skippedSetup by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { access = vm.hasAccess(); healthy = vm.drawerHealthy(); vm.refreshShadeCount(); onPauseOrDispose { } }
    val s = settings ?: return

    // Posting rows is only needed once sorting starts, so the permission is asked for then, not at first launch.
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setActive(true) }
    val turnOn: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setActive(true)
        }
    }
    val where = vm.cloudProvider()?.let { "Decisions use $it" } ?: "Decisions are made on this phone with built-in rules (an AI is optional, see Help)"

    when {
        !access || !connected -> Status(
            Icons.Outlined.Warning, if (!access) "Step 1 of 3 · Allow access" else "One step to go",
            if (!access) "Allow NotiSift to read notifications so it can sort your drawer. Anything it moves stays in the app."
            else "Android hasn't started NotiSift yet. Turn it off and on in the list.",
            MaterialTheme.colorScheme.errorContainer,
        ) {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Allow access")
            }
        }
        s.stopped -> Status(
            Icons.Outlined.PauseCircle, "Off",
            "NotiSift isn't reading or sorting notifications, and nothing is sent anywhere.",
            MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Button(onClick = { vm.setStopped(false) }) { Text("Turn back on") }
        }
        // First run: nothing scanned yet. One tap gets every app a sensible choice before anything moves.
        s.observe && policies.isEmpty() && !skippedSetup -> Status(
            Icons.Outlined.AutoAwesome, "Step 2 of 3 · Your apps",
            if (scan.running) scan.message ?: "Looking at your apps…"
            else "NotiSift looks at the apps on your phone and picks a starting choice for each. You can change any of them later in Apps.",
            MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            if (scan.running) {
                LinearProgressIndicator(
                    progress = { if (scan.total > 0) scan.done.toFloat() / scan.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(onClick = { vm.setupApps() }) { Text("Set up my apps") }
                TextButton(onClick = { skippedSetup = true }) { Text("Skip") }
            }
        }
        s.observe -> Status(
            Icons.Outlined.PauseCircle, "Step 3 of 3 · Preview",
            "Nothing moves yet: below is what NotiSift would do. $where. Turn on when it looks right.",
            MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Button(onClick = turnOn) { Text("Start sorting") }
            TextButton(onClick = { vm.setStopped(true) }) { Text("Turn off") }
        }
        !healthy -> Status(
            Icons.Outlined.Warning, "Needs attention",
            "Android is hiding NotiSift's notifications, so what it moves can only be seen in this app. Turn them back on.",
            MaterialTheme.colorScheme.errorContainer,
        ) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Fix in Android settings") }
            TextButton(onClick = { vm.setActive(false) }) { Text("Preview only") }
        }
        else -> Status(
            Icons.Outlined.CheckCircle, "Sorting",
            "⭐ $inShade important on top · $removed moved here today. $where.",
            MaterialTheme.colorScheme.primaryContainer,
        ) {
            FilledTonalButton(onClick = { vm.cleanNow() }) { Text("Clean again") }
            // One everyday action; pausing is rarer, so it waits behind ⋯ (the Quick Settings tile does it too).
            MoreMenu(listOf("Preview only" to { vm.setActive(false) }, "Turn off" to { vm.setStopped(true) }))
        }
    }
}

@Composable
private fun Status(icon: ImageVector, title: String, body: String, color: androidx.compose.ui.graphics.Color, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            // Wraps onto a second line in narrow panes (the Fold's two-pane Home) instead of squeezing buttons.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        }
    }
}

@Composable
private fun MoreMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "More choices") }
    DropdownMenu(open, onDismissRequest = { open = false }) {
        items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
    }
}
