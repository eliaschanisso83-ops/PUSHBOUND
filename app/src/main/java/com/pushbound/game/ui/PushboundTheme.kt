package com.pushbound.game.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WarmNeutral = Color(0xFFE7DECE)
private val InkBrown = Color(0xFF2B2622)

/**
 * UI colors stay inside the approved palette: warm neutrals from the board
 * materials, ink brown from the control artwork. No new hues are introduced.
 */
@Composable
fun PushboundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = InkBrown,
            onPrimary = Color(0xFFF3EDE2),
            background = WarmNeutral,
            onBackground = InkBrown,
            surface = Color(0xFFF1EADD),
            onSurface = InkBrown
        ),
        content = content
    )
}
