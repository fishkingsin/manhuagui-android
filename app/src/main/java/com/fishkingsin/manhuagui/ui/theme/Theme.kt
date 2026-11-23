package com.fishkingsin.manhuagui.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

private fun monochromeColorScheme(background: Color, foreground: Color): ColorScheme =
    lightColorScheme(
        primary = foreground,
        onPrimary = background,
        primaryContainer = background,
        onPrimaryContainer = foreground,
        inversePrimary = background,
        primaryFixed = foreground,
        primaryFixedDim = foreground,
        onPrimaryFixed = background,
        onPrimaryFixedVariant = background,
        secondary = foreground,
        onSecondary = background,
        secondaryContainer = background,
        onSecondaryContainer = foreground,
        secondaryFixed = foreground,
        secondaryFixedDim = foreground,
        onSecondaryFixed = background,
        onSecondaryFixedVariant = background,
        tertiary = foreground,
        onTertiary = background,
        tertiaryContainer = background,
        onTertiaryContainer = foreground,
        tertiaryFixed = foreground,
        tertiaryFixedDim = foreground,
        onTertiaryFixed = background,
        onTertiaryFixedVariant = background,
        background = background,
        onBackground = foreground,
        surface = background,
        onSurface = foreground,
        surfaceVariant = background,
        onSurfaceVariant = foreground,
        surfaceTint = foreground,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = foreground,
        onError = background,
        errorContainer = background,
        onErrorContainer = foreground,
        outline = foreground,
        outlineVariant = foreground,
        scrim = foreground,
        surfaceBright = background,
        surfaceDim = background,
        surfaceContainer = background,
        surfaceContainerHigh = background,
        surfaceContainerHighest = background,
        surfaceContainerLow = background,
        surfaceContainerLowest = background,
    )

private val LightColorScheme = monochromeColorScheme(Color.White, Color.Black)
private val DarkColorScheme = monochromeColorScheme(Color.Black, Color.White)

private object NoOpIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
            }
        }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = 0
}

@Composable
fun ManhuaguiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalIndication provides NoOpIndication) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
