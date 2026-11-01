/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** True when the iOS / Apple Music style UI is enabled in settings. */
val LocalIosStyle = staticCompositionLocalOf { false }

/**
 * Marks this layout as the content that glass bars blur.
 * Does nothing when [state] is null (iOS glass UI disabled).
 */
fun Modifier.iosGlassSource(
    state: HazeState?,
    background: Color,
): Modifier =
    if (state == null) {
        this
    } else {
        this.hazeSource(state).background(background)
    }

/**
 * Turns this layout into a frosted-glass bar that blurs whatever is drawn
 * by [iosGlassSource] behind it, then lays a translucent [tint] on top.
 * Does nothing when [state] is null (iOS glass UI disabled).
 */
fun Modifier.iosGlassBar(
    state: HazeState?,
    tint: Color,
    radius: Dp = 28.dp,
): Modifier =
    if (state == null) {
        this
    } else {
        this
            .hazeEffect(state) {
                blurRadius = radius
            }.background(tint)
    }

/**
 * iOS-style press feedback: shrinks slightly while pressed and springs back.
 */
@Composable
fun Modifier.iosPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.88f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "iosPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
