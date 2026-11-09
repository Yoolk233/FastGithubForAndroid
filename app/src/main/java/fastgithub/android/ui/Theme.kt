package fastgithub.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF06214D),
    secondary = Color(0xFF3C6A5E),
    secondaryContainer = Color(0xFFD6EDE4),
    onSecondaryContainer = Color(0xFF0B2B22),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFE2E6EE),
    background = Color(0xFFF7F9FD),
    error = Color(0xFFB3261E)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CC4FF),
    onPrimary = Color(0xFF0A2E63),
    primaryContainer = Color(0xFF1D4C8F),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFF9FD6C7),
    secondaryContainer = Color(0xFF26483E),
    onSecondaryContainer = Color(0xFFD6EDE4),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF232A31),
    background = Color(0xFF0B0F13),
    error = Color(0xFFF2B8B5)
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    )
}

@Composable
fun FastGithubTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** 状态色（深浅色各一套，避免浅色主题下荧光绿看不清） */
@Composable
fun statusColor(state: String): Color {
    val dark = isSystemInDarkTheme()
    return when (state) {
        "GOOD", "QUICK", "CACHE" -> if (dark) Color(0xFF7FD795) else Color(0xFF1B7F3B)
        "WEAK" -> if (dark) Color(0xFFF4C663) else Color(0xFF8A6100)
        "BAD", "FAIL" -> if (dark) Color(0xFFF08A8A) else Color(0xFFB3261E)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
