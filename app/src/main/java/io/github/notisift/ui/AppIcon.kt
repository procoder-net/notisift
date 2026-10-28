package io.github.notisift.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val icons = LruCache<String, ImageBitmap>(128)

/** The installed app's own icon: recognising an app by its icon is faster than reading its name. */
@Composable
fun AppIcon(packageName: String, size: Dp = 24.dp) {
    val context = LocalContext.current
    val icon by produceState(icons.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }
                    .getOrNull()?.also { icons.put(packageName, it) }
            }
        }
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) } ?: Spacer(Modifier.size(size))
}
