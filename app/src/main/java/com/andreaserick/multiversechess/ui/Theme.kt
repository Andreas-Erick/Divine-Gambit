package com.andreaserick.multiversechess.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object Palette {
    val background = Color(0xFF14121F)
    val surface = Color(0xFF1E1B2E)
    val lightSquare = Color(0xFFE8E0F0)
    val darkSquare = Color(0xFF8B7BB5)
    val whiteBoardFrame = Color(0xFFF2F0F7)
    val blackBoardFrame = Color(0xFF2B2838)
    val gold = Color(0xFFFFD54F)
    val selection = Color(0x99FFD54F)
    val lastMove = Color(0x5566BB6A)
    val target = Color(0xCC43A047)
    val threat = Color(0xFFE53935)
    val travelArrow = Color(0xCC4FC3F7)
    val connector = Color(0x998B7BB5)
    val label = Color(0xFFB0A8C8)
}

private val colors = darkColorScheme(
    primary = Color(0xFFB39DDB),
    onPrimary = Color(0xFF1B1530),
    secondary = Palette.gold,
    onSecondary = Color(0xFF2B2000),
    background = Palette.background,
    onBackground = Color(0xFFE8E4F2),
    surface = Palette.surface,
    onSurface = Color(0xFFE8E4F2),
    error = Color(0xFFFF8A80),
)

@Composable
fun MultiverseChessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
