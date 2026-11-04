/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score
import androidx.compose.ui.graphics.lerp

val DefaultThemeColor = Color(0xFFED5564)

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    iosStyle: Boolean = false,
    iosAccentIndex: Int = 0,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Determine if system dynamic colors should be used (Android S+ and default theme color)
    val useSystemDynamicColor = (themeColor == DefaultThemeColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    // Select the appropriate color scheme generation method
    val baseColorScheme = if (useSystemDynamicColor) {
        // Use standard Material 3 dynamic color functions for system wallpaper colors
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // Use materialKolor only when a specific seed color is provided
        rememberDynamicColorScheme(
            seedColor = themeColor, // themeColor is guaranteed non-default here
            isDark = darkTheme,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            style = PaletteStyle.TonalSpot // Keep existing style
        )
    }

    // Apply pureBlack modification if needed, similar to original logic
    val colorScheme = remember(baseColorScheme, pureBlack, darkTheme, iosStyle, iosAccentIndex) {
        val scheme =
            if (darkTheme && pureBlack) {
                baseColorScheme.pureBlack(true)
            } else {
                baseColorScheme
            }
        if (iosStyle) iosColorScheme(scheme, darkTheme, IosAccents.colorFor(iosAccentIndex, darkTheme)) else scheme
    }

    // Use standard MaterialTheme instead of MaterialExpressiveTheme
    if (iosStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = IosShapes,
            typography = IosTypography,
            content = content,
        )
    } else {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

/** Apple Music inspired palette: true black / system grays with iOS blue accent. */
fun iosColorScheme(
    base: ColorScheme,
    darkTheme: Boolean,
    accent: Color = Color(0xFF0A84FF),
): ColorScheme =
    if (darkTheme) {
        base.copy(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.Black, 0.65f),
            onPrimaryContainer = lerp(accent, Color.White, 0.8f),
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

private val IosShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(percent = 50),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

private val IosTypography =
    Typography().let { t ->
        t.copy(
            headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }

fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, DefaultThemeColor.toArgb())
        .first()
)

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}


/** Accent colours selectable for the iOS style (index is stored in the IosAccentKey preference). */
object IosAccents {
    private val dark = listOf(0xFF0A84FF, 0xFFFA2D48, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFFBF5AF2)
    private val light = listOf(0xFF007AFF, 0xFFFA243C, 0xFFFF2D55, 0xFFFF9500, 0xFF34C759, 0xFFAF52DE)

    val count: Int get() = dark.size

    fun colorFor(
        index: Int,
        darkTheme: Boolean,
    ): Color {
        val list = if (darkTheme) dark else light
        return Color(list[index.coerceIn(0, list.size - 1)])
    }
}
