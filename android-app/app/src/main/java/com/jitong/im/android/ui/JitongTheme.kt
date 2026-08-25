package com.jitong.im.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal object JitongColors {
    val blue = Color(0xFF1683E8)
    val blueDark = Color(0xFF0E6AC2)
    val header = Color(0xFFF1F4FF)
    val page = Color(0xFFF7F8FC)
    val chatPage = Color(0xFFF3F4F7)
    val surface = Color.White
    val divider = Color(0xFFE8EAF0)
    val text = Color(0xFF1F2329)
    val secondaryText = Color(0xFF8A9099)
    val tertiaryText = Color(0xFFB0B5BD)
    val incomingBubble = Color.White
    val outgoingBubble = Color(0xFF1683E8)
    val success = Color(0xFF19B97A)
    val warning = Color(0xFFFF9D32)
    val danger = Color(0xFFE95050)
    val ai = Color(0xFF7B61FF)
}

private val LightColors = lightColorScheme(
    primary = JitongColors.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F1FF),
    onPrimaryContainer = JitongColors.blueDark,
    secondary = Color(0xFF5D6B82),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EEF7),
    onSecondaryContainer = JitongColors.text,
    background = JitongColors.page,
    onBackground = JitongColors.text,
    surface = JitongColors.surface,
    onSurface = JitongColors.text,
    surfaceVariant = Color(0xFFF0F1F5),
    onSurfaceVariant = JitongColors.secondaryText,
    outline = Color(0xFFD7DAE2),
    error = JitongColors.danger,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF78B9FF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF074A7E),
    onPrimaryContainer = Color(0xFFD3E8FF),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF272B33),
    onSurfaceVariant = Color(0xFFB7BDC8),
    outline = Color(0xFF454B56),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF5C0000),
)

private val JitongTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontSize = 23.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
internal fun JitongTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = JitongTypography,
        content = content,
    )
}
