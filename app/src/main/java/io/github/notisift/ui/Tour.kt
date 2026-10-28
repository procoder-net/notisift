package io.github.notisift.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** One stop on the first-run tour. [target] names a [tourTarget]; null (or not on screen) shows a centred card. */
data class TourStep(val target: String?, val title: String, val body: String)

val firstRunTour = listOf(
    TourStep(null, "Welcome to NotiSift", "It keeps what matters on top of your notification drawer and puts the noise aside. Here's a 30-second tour."),
    TourStep("status", "Start here", "This card shows what NotiSift is doing and the one thing to do next: allow access, set up your apps, then preview before anything moves."),
    TourStep("tiers", "Everything it sorted", "⭐ Important stays on top of your drawer. 🕓 Later waits here, grouped. 🗑️ Junk is hidden but kept in case you need it."),
    TourStep("swipe", "Fix a mistake", "Swipe right to put something back on top, left when you're done. Every change can be undone."),
    TourStep("nav-apps", "Choose per app", "Set any app to Important, Smart, Later or Junk. Most apps work best on Smart."),
    TourStep("nav-settings", "Settings and AI", "AI is optional. Without it NotiSift uses built-in rules on your phone; add a key here for smarter sorting."),
    TourStep("help", "Help anytime", "Tap ? for a short guide to every part of NotiSift, or to take this tour again."),
)

/** Where tour targets are on screen, in root coordinates, so the overlay can spotlight them. */
@Stable
class TourTargets {
    val bounds = mutableStateMapOf<String, Rect>()
}

val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/** Marks this element as the tour stop [id]. */
fun Modifier.tourTarget(id: String): Modifier = composed {
    val targets = LocalTourTargets.current
    if (targets == null) this else onGloballyPositioned { targets.bounds[id] = it.boundsInRoot() }
}

/** Dims the app, cuts a spotlight around the current step's target and explains it in a card beside it. */
@Composable
fun TourOverlay(steps: List<TourStep>, targets: TourTargets, onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]
    val last = index == steps.lastIndex
    val spot = step.target?.let { targets.bounds[it] }?.takeIf { it.width > 0 && it.height > 0 }
    val pad = with(LocalDensity.current) { 8.dp.toPx() }
    val scrim = Color.Black.copy(alpha = 0.72f)
    val highlight = MaterialTheme.colorScheme.primary
    BackHandler { if (index > 0) index-- else onDone() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // Taps outside the card are swallowed so the app underneath can't be used mid-tour.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(scrim)
            spot?.let { r ->
                val topLeft = Offset(r.left - pad, r.top - pad)
                val size = Size(r.width + 2 * pad, r.height + 2 * pad)
                drawRoundRect(Color.Transparent, topLeft, size, CornerRadius(pad * 2), blendMode = BlendMode.Clear)
                drawRoundRect(highlight, topLeft, size, CornerRadius(pad * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = pad / 2))
            }
        }
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val card = @Composable { modifier: Modifier ->
            Card(modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1} of ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(step.body, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!last) TextButton(onClick = onDone) { Text("Skip") }
                        Spacer(Modifier.weight(1f))
                        if (index > 0) TextButton(onClick = { index-- }) { Text("Back") }
                        Button(onClick = { if (last) onDone() else index++ }) { Text(if (last) "Got it" else "Next") }
                    }
                }
            }
        }
        when {
            spot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { card(Modifier) }
            // Below the spotlight when it is in the top part of the screen, above it otherwise.
            spot.center.y < heightPx * 0.5f -> card(Modifier.offset { IntOffset(0, (spot.bottom + pad * 2).toInt()) })
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                card(Modifier.offset { IntOffset(0, -(heightPx - spot.top + pad * 2).toInt()) })
            }
        }
    }
}
