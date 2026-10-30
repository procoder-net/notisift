package io.github.notisift.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.notisift.engine.Tier

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Inbox),
    APPS("Apps", Icons.Outlined.Apps),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** True on the Fold's inner screen, tablets and landscape: switch to rail + two panes. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiftRoot(requestedTier: String?, onTierShown: () -> Unit, vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var inboxCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var inboxTier by rememberSaveable { mutableStateOf(Tier.LATER.id) }
    var homeSearch by rememberSaveable { mutableStateOf(false) }
    // Tapping Home again scrolls it back to the top, as in most apps.
    var homeTop by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()

    LaunchedEffect(requestedTier) {
        if (requestedTier != null) {
            tab = Tab.HOME
            val (tierId, categoryId) = requestedTier.split('/').let { it[0] to it.getOrNull(1) }
            Tier.fromId(tierId)?.let { inboxTier = it.id }
            // Tapped from the drawer: show that tier, not an old search.
            vm.searchHome("")
            inboxCategory = categoryId
            onTierShown()
        }
    }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.consumeToast() }
    }
    val undo by vm.undo.collectAsState()
    LaunchedEffect(undo) {
        undo?.let { notice ->
            // A newer notice restarts this effect and replaces the snackbar; consume only once this one is over.
            val result = snackbar.showSnackbar(notice.message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) vm.performUndo(notice)
            vm.consumeUndo()
        }
    }

    val settings by vm.settings.collectAsState()
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    var replayTour by rememberSaveable { mutableStateOf(false) }
    // New users get the full-screen setup; the overlay tour is only replayed from Help.
    val touring = replayTour
    val targets = remember { TourTargets() }
    // The tour explains Home, so it always starts there.
    LaunchedEffect(touring) { if (touring) tab = Tab.HOME }

    val wide = isWide()
    if (settings?.tourDone == false) {
        Onboarding(vm, onDone = { tab = Tab.HOME; vm.finishTour() })
        return
    }
    CompositionLocalProvider(LocalTourTargets provides targets) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text(if (tab == Tab.HOME) "NotiSift" else tab.label) },
                        actions = {
                            if (tab == Tab.HOME) IconButton(onClick = { if (homeSearch) vm.searchHome(""); homeSearch = !homeSearch }) {
                                Icon(if (homeSearch) Icons.Outlined.Close else Icons.Outlined.Search, if (homeSearch) "Close search" else "Search notifications")
                            }
                            IconButton(onClick = { helpOpen = true }, Modifier.tourTarget("help")) {
                                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help")
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!wide) NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { if (tab == t && t == Tab.HOME) homeTop++; tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                                modifier = Modifier.tourTarget("nav-${t.name.lowercase()}"),
                            )
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) NavigationRail {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t, onClick = { if (tab == t && t == Tab.HOME) homeTop++; tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                                modifier = Modifier.tourTarget("nav-${t.name.lowercase()}"),
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        val content = Modifier.fillMaxSize().let { if (wide && tab != Tab.HOME && tab != Tab.APPS) it.widthIn(max = 840.dp) else it }
                        when (tab) {
                            Tab.HOME -> HomeScreen(vm, content, inboxTier, { inboxTier = it; inboxCategory = null }, inboxCategory, onCategory = { inboxCategory = it }, onAddAiKey = { vm.requestAiSetup(); tab = Tab.SETTINGS }, searchOpen = homeSearch, onSearchOpen = { homeSearch = it }, scrollToTop = homeTop)
                            Tab.APPS -> AppsScreen(vm, content)
                            Tab.SETTINGS -> SettingsScreen(vm, content)
                        }
                    }
                }
            }
            if (helpOpen) HelpSheet(
                vm,
                onOpen = { tab = it; helpOpen = false },
                onTour = { helpOpen = false; replayTour = true },
                onDismiss = { helpOpen = false },
            )
            if (touring) TourOverlay(firstRunTour, targets) { replayTour = false; vm.finishTour() }
        }
    }
}
