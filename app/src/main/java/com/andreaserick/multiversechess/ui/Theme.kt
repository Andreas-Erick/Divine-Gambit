package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andreaserick.multiversechess.R

/** Colours of the multiverse view and the app chrome: warm parchment, ink and amethyst. */
internal object Palette {
    // Floor of the multiverse and app background.
    val floor = Color(0xFFDDD8CC)
    val floorBand = Color(0xFFD5CFC1)
    val parchment = Color(0xFFECE7DC)
    val card = Color(0xFFF7F3EA)
    val ink = Color(0xFF2C2A33)
    val inkSoft = Color(0xFF6B6672)
    val slab = Color(0xFF34313B)

    // The present: a pale column across all timelines.
    val present = Color(0xFFF1EEE6)
    val presentEdge = Color(0xFFB9B2A2)

    // Boards.
    val lightSquare = Color(0xFFF1DDB9)
    val darkSquare = Color(0xFFC59C6E)
    val whiteBoardFrame = Color(0xFFF7F3EA)
    val blackBoardFrame = Color(0xFF2C2A33)

    // Timelines.
    val ribbon = Color(0xFF8C6CB0)
    val ribbonInactive = Color(0xFFADA6B8)

    // Accents and highlights.
    val amethyst = Color(0xFF6A4C96)
    val gold = Color(0xFFD9A63A)
    val lastMove = Color(0xA6E8D35C)
    val travel = Color(0xB39996EC)
    val selection = Color(0xCC7DA557)
    val target = Color(0x8C7DA557)
    val targetDot = Color(0xCC4E7A34)
    val travelArrow = Color(0xFF8E8AEF)
    val threat = Color(0xFFD04848)
}

@OptIn(ExperimentalTextApi::class)
private val lora = FontFamily(
    Font(R.font.lora, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.lora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.lora, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.lora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Serif type for headings and body; the game is meant to feel like a well-made board game. */
internal val SerifFamily = lora

private fun Typography.serif(): Typography {
    fun TextStyle.s() = copy(fontFamily = lora)
    return copy(
        displayLarge = displayLarge.s(), displayMedium = displayMedium.s(), displaySmall = displaySmall.s(),
        headlineLarge = headlineLarge.s().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.s().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.s().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.s().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.s().copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = titleSmall.s().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s().copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = labelMedium.s(), labelSmall = labelSmall.s(),
    )
}

private val colors = lightColorScheme(
    primary = Palette.amethyst,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DAF0),
    onPrimaryContainer = Color(0xFF2A1A45),
    secondary = Palette.gold,
    onSecondary = Color(0xFF2B2000),
    secondaryContainer = Color(0xFFF4E3BC),
    onSecondaryContainer = Color(0xFF3A2A00),
    background = Palette.parchment,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = Color(0xFFE5DFD3),
    onSurfaceVariant = Palette.inkSoft,
    surfaceContainerHigh = Palette.card,
    surfaceContainerHighest = Color(0xFFEFEAE0),
    surfaceContainer = Palette.card,
    outline = Color(0xFFB9B2A2),
    outlineVariant = Color(0xFFD6D0C3),
    error = Color(0xFFB3261E),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MultiverseChessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography().serif(), shapes = shapes, content = content)
}
