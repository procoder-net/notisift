package io.github.notisift.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.notisift.engine.Action
import io.github.notisift.engine.Category

@Composable
fun SiftTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Material You: follows the Fold's wallpaper colours on Android 12+.
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9FA8DA))
        else -> lightColorScheme(primary = Color(0xFF3F51B5))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun Action.tint(): Color = when (this) {
    Action.KEEP -> Color(0xFF2E7D32)
    Action.BUNDLE -> Color(0xFF1565C0)
    Action.SILENCE -> Color(0xFF6D4C41)
    Action.BLOCK -> Color(0xFFC62828)
}

fun Category.emoji(): String = emoji
