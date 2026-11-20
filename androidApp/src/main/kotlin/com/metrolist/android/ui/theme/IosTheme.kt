package com.metrolist.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Apple Music inspired palette: true black / system greys with the iOS blue as accent. */
fun iosColorScheme(
    base: ColorScheme,
    darkTheme: Boolean,
    accent: Color = if (darkTheme) Color(0xFF0A84FF) else Color(0xFF007AFF),
): ColorScheme =
    if (darkTheme) {
        base.copy(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.Black, 0.65f),
            onPrimaryContainer = lerp(accent, Color.White, 0.8f),
            secondary = Color(0xFF8E8E93),
            secondaryContainer = Color(0xFF2C2C2E),
            onSecondaryContainer = Color.White,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = Color(0xFF8E8E93),
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0F0F10),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C),
            outline = Color(0xFF48484A),
            outlineVariant = Color(0xFF38383A),
        )
    } else {
        base.copy(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.White, 0.82f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.55f),
            secondary = Color(0xFF6C6C70),
            secondaryContainer = Color(0xFFE5E5EA),
            onSecondaryContainer = Color.Black,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFE5E5EA),
            onSurfaceVariant = Color(0xFF6C6C70),
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F7FA),
            surfaceContainer = Color(0xFFF2F2F7),
            surfaceContainerHigh = Color(0xFFE5E5EA),
            surfaceContainerHighest = Color(0xFFD1D1D6),
            outline = Color(0xFFC7C7CC),
            outlineVariant = Color(0xFFD1D1D6),
        )
    }

/** iOS style shapes: capsule chips, rounded cards. */
val IosShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(percent = 50),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
